package net.eca.client.render.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

// Oculus兼容：覆写apply()，在Oculus锁定DepthColor后立刻解锁，使ECA着色器在光影模式下可见
public class EcaShaderInstance extends ShaderInstance {

    private static final MethodHandle UNLOCK_DEPTH_COLOR;
    private static final MethodHandle RESTORE_BLEND;
    private static final MethodHandle IS_DEPTH_COLOR_LOCKED;
    private static final MethodHandle GET_IRIS_API;
    private static final MethodHandle IS_SHADER_PACK_IN_USE;

    static {
        MethodHandle unlockHandle = null;
        MethodHandle restoreBlendHandle = null;
        MethodHandle isLockedHandle = null;
        MethodHandle getApiHandle = null;
        MethodHandle isInUseHandle = null;
        try {
            Class<?> depthColorStorage = Class.forName("net.irisshaders.iris.gl.blending.DepthColorStorage");
            unlockHandle = MethodHandles.publicLookup().findStatic(
                depthColorStorage, "unlockDepthColor", MethodType.methodType(void.class)
            );
            isLockedHandle = MethodHandles.publicLookup().findStatic(
                depthColorStorage, "isDepthColorLocked", MethodType.methodType(boolean.class)
            );

            Class<?> blendModeStorage = Class.forName("net.irisshaders.iris.gl.blending.BlendModeStorage");
            restoreBlendHandle = MethodHandles.publicLookup().findStatic(
                blendModeStorage, "restoreBlend", MethodType.methodType(void.class)
            );

            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            getApiHandle = MethodHandles.publicLookup().findStatic(
                irisApiClass, "getInstance", MethodType.methodType(irisApiClass)
            );
            isInUseHandle = MethodHandles.publicLookup().findVirtual(
                irisApiClass, "isShaderPackInUse", MethodType.methodType(boolean.class)
            );
        } catch (ClassNotFoundException ignored) {
            // Oculus未安装
        } catch (Exception ignored) {
        }
        UNLOCK_DEPTH_COLOR = unlockHandle;
        RESTORE_BLEND = restoreBlendHandle;
        IS_DEPTH_COLOR_LOCKED = isLockedHandle;
        GET_IRIS_API = getApiHandle;
        IS_SHADER_PACK_IN_USE = isInUseHandle;
    }

    // 检测Oculus光影是否激活（光影包已启用且正在使用）
    public static boolean isOculusShadersActive() {
        if (GET_IRIS_API == null || IS_SHADER_PACK_IN_USE == null) {
            return false;
        }
        try {
            Object api = GET_IRIS_API.invoke();
            return (boolean) IS_SHADER_PACK_IN_USE.invoke(api);
        } catch (Throwable e) {
            return false;
        }
    }

    public EcaShaderInstance(ResourceProvider resourceProvider, ResourceLocation location, VertexFormat format) throws IOException {
        super(resourceProvider, location, format);
    }

    public static EcaShaderInstance create(ResourceProvider resourceProvider, ResourceLocation location, VertexFormat format) throws IOException {
        return new EcaShaderInstance(resourceProvider, location, format);
    }

    @Override
    public void apply() {
        super.apply();
        // Oculus的MixinShaderInstance会在super.apply()的TAIL锁定DepthColor，
        // 导致非ExtendedShader/FallbackShader的着色器无法写入颜色和深度。
        // 在此立刻解锁，使ECA着色器正常渲染。
        if (UNLOCK_DEPTH_COLOR != null && IS_DEPTH_COLOR_LOCKED != null) {
            try {
                boolean wasLocked = (boolean) IS_DEPTH_COLOR_LOCKED.invokeExact();
                if (wasLocked) {
                    UNLOCK_DEPTH_COLOR.invokeExact();

                    // 额外保险：直接使用LWJGL GL调用绕过GlStateManager拦截
                    // 当光影开启时，GlStateManager的_depthMask和_colorMask会被
                    // MixinGlStateManager_DepthColorOverride拦截。即使调用unlockDepthColor()
                    // 内部也是通过GlStateManager，可能再次被拦截。直接GL调用可以彻底绕过。
                    GL11.glDepthMask(true);
                    GL11.glColorMask(true, true, true, true);
                }

                // 恢复混合状态（如果被锁定）
                if (RESTORE_BLEND != null) {
                    RESTORE_BLEND.invokeExact();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
