package net.eca.client.render.shader;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;

import java.io.IOException;

@SuppressWarnings("removal")
public class HackerShader {

    private static ShaderInstance shader;
    private static Uniform timeUniform;
    private static Uniform cameraYawUniform;
    private static Uniform cameraPitchUniform;

    public static void register(RegisterShadersEvent event) throws IOException {
        ShaderInstance hackerShader = EcaShaderInstance.create(
            event.getResourceProvider(),
            new ResourceLocation("eca", "hacker"),
            DefaultVertexFormat.BLOCK
        );
        event.registerShader(hackerShader, instance -> {
            shader = instance;
            timeUniform = shader.getUniform("GameTime");
            cameraYawUniform = shader.getUniform("CameraYaw");
            cameraPitchUniform = shader.getUniform("CameraPitch");
        });
    }

    public static ShaderInstance getShader() {
        return shader;
    }

    public static void applyUniforms() {
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
        } catch (Exception ignored) {
        }
    }

    public static boolean isAvailable() {
        return shader != null;
    }

    public static void clear() {
        shader = null;
        timeUniform = null;
        cameraYawUniform = null;
        cameraPitchUniform = null;
    }
}
