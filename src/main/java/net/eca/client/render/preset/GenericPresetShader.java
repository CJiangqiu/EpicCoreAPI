package net.eca.client.render.preset;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.eca.client.render.shader.EcaShaderInstance;
import net.eca.util.EcaLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/* 单个自定义预设的着色器持有者：一个逻辑预设 id 对应两个 ShaderInstance——BLOCK profile（id+"_block"，
   DefaultVertexFormat.BLOCK）服务 BLOCK 顶点格式的 RenderType（天空盒 / Boss 血条），NEW_ENTITY profile
   （id+"_entity"，NEW_ENTITY）服务 NEW_ENTITY 顶点格式的 RenderType（Boss 实体层 / 物品层 / 实体效果层）。
   两 profile 共享同一 fsh，但必须各按匹配的 VertexFormat 编译，不能共用单一实例。
   reload 在每次 RegisterShadersEvent（或注册晚于该事件时）重建两个 EcaShaderInstance（自带 Iris 深度颜色解锁）。 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("removal")
final class GenericPresetShader {

    private final Profile block;
    private final Profile entity;

    GenericPresetShader(ResourceLocation id) {
        this.block = new Profile(suffixed(id, "block"), DefaultVertexFormat.BLOCK);
        this.entity = new Profile(suffixed(id, "entity"), DefaultVertexFormat.NEW_ENTITY);
    }

    Profile block() {
        return block;
    }

    Profile entity() {
        return entity;
    }

    //从给定资源源（RegisterShadersEvent 的 provider，或运行期的资源管理器）编译两个 profile 并接好标准 uniform
    void reload(ResourceProvider provider) {
        block.reload(provider);
        entity.reload(provider);
    }

    //逻辑预设 id → 单 profile 的 core shader id：eca:foo + "block" → eca:foo_block，对齐作者端导出命名
    private static ResourceLocation suffixed(ResourceLocation id, String suffix) {
        return new ResourceLocation(id.getNamespace(), id.getPath() + "_" + suffix);
    }

    /* 单个 profile 的着色器持有者：持有该 profile 的 core shader id、匹配的顶点格式、当前 ShaderInstance 与一组标准
       uniform 句柄。每个 ShaderInstance 各有独立的 Uniform 对象，故 uniform 句柄必须随实例分别持有。
       applyUniforms 按帧喂 GameTime / 相机朝向 / 抠像 / 物品图集。 */
    static final class Profile {

        private final ResourceLocation location;
        private final VertexFormat format;

        private ShaderInstance shader;
        private Uniform timeUniform;
        private Uniform cameraYawUniform;
        private Uniform cameraPitchUniform;
        private Uniform colorKeyColorUniform;
        private Uniform colorKeyToleranceUniform;
        private Uniform localUvMinUniform;
        private Uniform localUvScaleUniform;

        Profile(ResourceLocation location, VertexFormat format) {
            this.location = location;
            this.format = format;
        }

        ShaderInstance getShader() {
            return shader;
        }

        void reload(ResourceProvider provider) {
            try {
                ShaderInstance instance = EcaShaderInstance.create(provider, location, format);
                this.shader = instance;
                this.timeUniform = instance.getUniform("GameTime");
                this.cameraYawUniform = instance.getUniform("CameraYaw");
                this.cameraPitchUniform = instance.getUniform("CameraPitch");
                this.colorKeyColorUniform = instance.getUniform("ColorKeyColor");
                this.colorKeyToleranceUniform = instance.getUniform("ColorKeyTolerance");
                this.localUvMinUniform = instance.getUniform("LocalUvMin");
                this.localUvScaleUniform = instance.getUniform("LocalUvScale");
            } catch (Exception e) {
                EcaLogger.warn("[ShaderPreset] failed to load shader {}: {}", location, e.toString());
            }
        }

        void applyUniforms() {
            try {
                if (timeUniform != null) {
                    float systemTime = (System.currentTimeMillis() % 1000000L) / 1000.0F;
                    timeUniform.set(systemTime);
                }
                if (cameraYawUniform != null || cameraPitchUniform != null) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.gameRenderer != null && mc.gameRenderer.getMainCamera() != null) {
                        float yaw = (float) Math.toRadians(mc.gameRenderer.getMainCamera().getYRot());
                        float pitch = (float) Math.toRadians(mc.gameRenderer.getMainCamera().getXRot());
                        if (cameraYawUniform != null) {
                            cameraYawUniform.set(yaw);
                        }
                        if (cameraPitchUniform != null) {
                            cameraPitchUniform.set(pitch);
                        }
                    }
                }
                EcaShaderInstance.applyColorKeyUniforms(colorKeyColorUniform, colorKeyToleranceUniform);
                EcaShaderInstance.applyLocalUvBoundsUniforms(localUvMinUniform, localUvScaleUniform);
            } catch (Exception ignored) {
            }
        }
    }
}
