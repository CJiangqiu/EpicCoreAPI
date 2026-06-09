package net.eca.client.render.preset;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.eca.client.render.shader.EcaShaderInstance;
import net.eca.util.EcaLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/* 单个自定义预设的着色器持有者：等价于内置 XxxShader，但按 id 通用化。
   持有当前 ShaderInstance 与标准 uniform 句柄；reload 在每次 RegisterShadersEvent（或注册晚于该事件时）
   重建 EcaShaderInstance（自带 Iris 深度颜色解锁）；applyUniforms 按帧喂 GameTime / 相机朝向 / 抠像 / 物品图集。 */
@OnlyIn(Dist.CLIENT)
@SuppressWarnings("removal")
final class GenericPresetShader {

    private final ResourceLocation id;

    private ShaderInstance shader;
    private Uniform timeUniform;
    private Uniform cameraYawUniform;
    private Uniform cameraPitchUniform;
    private Uniform colorKeyColorUniform;
    private Uniform colorKeyToleranceUniform;
    private Uniform localUvMinUniform;
    private Uniform localUvScaleUniform;

    GenericPresetShader(ResourceLocation id) {
        this.id = id;
    }

    ShaderInstance getShader() {
        return shader;
    }

    //从给定资源源（RegisterShadersEvent 的 provider，或运行期的资源管理器）编译并接好标准 uniform
    void reload(ResourceProvider provider) {
        try {
            ShaderInstance instance = EcaShaderInstance.create(provider, id, DefaultVertexFormat.BLOCK);
            this.shader = instance;
            this.timeUniform = instance.getUniform("GameTime");
            this.cameraYawUniform = instance.getUniform("CameraYaw");
            this.cameraPitchUniform = instance.getUniform("CameraPitch");
            this.colorKeyColorUniform = instance.getUniform("ColorKeyColor");
            this.colorKeyToleranceUniform = instance.getUniform("ColorKeyTolerance");
            this.localUvMinUniform = instance.getUniform("LocalUvMin");
            this.localUvScaleUniform = instance.getUniform("LocalUvScale");
        } catch (Exception e) {
            EcaLogger.warn("[ShaderPreset] failed to load shader {}: {}", id, e.toString());
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
