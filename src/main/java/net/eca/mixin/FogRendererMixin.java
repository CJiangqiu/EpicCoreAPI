package net.eca.mixin;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.eca.util.entity_extension.EntityExtensionClientState;
import net.eca.util.entity_extension.EntityExtension;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.GlobalFogExtension;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyIn(Dist.CLIENT)
@Mixin(FogRenderer.class)
public class FogRendererMixin {

    @Shadow
    private static float fogRed;

    @Shadow
    private static float fogGreen;

    @Shadow
    private static float fogBlue;

    @Inject(method = "setupColor", at = @At("TAIL"))
    private static void eca$applyGlobalFogColor(Camera camera, float partialTick, ClientLevel level, int renderDistanceChunks, float darkenWorldAmount, CallbackInfo ci) {
        float[] strengthHolder = new float[]{0.0f};
        GlobalFogExtension fog = eca$resolveFogContext(level, camera, strengthHolder);
        if (fog == null) {
            return;
        }
        float strength = strengthHolder[0];

        fogRed = Mth.lerp(strength, fogRed, eca$clampColor(fog.fogRed()));
        fogGreen = Mth.lerp(strength, fogGreen, eca$clampColor(fog.fogGreen()));
        fogBlue = Mth.lerp(strength, fogBlue, eca$clampColor(fog.fogBlue()));
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0f);
    }

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void eca$applyGlobalFogDistance(Camera camera, FogRenderer.FogMode fogMode, float renderDistance, boolean thickFog, float partialTick, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }

        float[] strengthHolder = new float[]{0.0f};
        GlobalFogExtension fog = eca$resolveFogContext(minecraft.level, camera, strengthHolder);
        if (fog == null) {
            return;
        }
        float strength = strengthHolder[0];

        float customStart;
        float customEnd;
        if (fogMode == FogRenderer.FogMode.FOG_SKY) {
            customStart = fog.skyFogStart(renderDistance);
            customEnd = fog.skyFogEnd(renderDistance);
        } else {
            customStart = fog.terrainFogStart(renderDistance);
            customEnd = fog.terrainFogEnd(renderDistance);
        }

        float baseStart = RenderSystem.getShaderFogStart();
        float baseEnd = RenderSystem.getShaderFogEnd();
        float start = Mth.lerp(strength, baseStart, customStart);
        float end = Mth.lerp(strength, baseEnd, customEnd);

        if (end <= start) {
            end = start + 1.0f;
        }

        RenderSystem.setShaderFogStart(start);
        RenderSystem.setShaderFogEnd(end);
        FogShape shape = strength >= 0.999f ? fog.fogShape() : null;
        RenderSystem.setShaderFogShape(shape == null ? FogShape.CYLINDER : shape);
    }

    @Unique
    private static GlobalFogExtension eca$resolveFogContext(ClientLevel level, Camera camera, float[] strengthHolder) {
        if (level == null || camera == null || strengthHolder == null || strengthHolder.length == 0) {
            return null;
        }

        GlobalFogExtension global = eca$resolveGlobalFogContext(level);
        if (global != null) {
            strengthHolder[0] = 1.0f;
            return global;
        }

        return eca$resolveLocalFogContext(level, camera, strengthHolder);
    }

    @Unique
    private static GlobalFogExtension eca$resolveGlobalFogContext(ClientLevel level) {
        if (level == null) {
            return null;
        }

        ResourceLocation dimensionId = level.dimension().location();
        EntityType<?> activeType = EntityExtensionClientState.getActiveType(dimensionId);
        if (activeType == null) {
            return null;
        }

        EntityExtension extension = EntityExtensionManager.getExtension(activeType);
        if (extension == null) {
            return null;
        }

        GlobalFogExtension fog = extension.globalFogExtension();
        if (fog == null || !fog.enabled() || !fog.globalMode()) {
            return null;
        }

        return fog;
    }

    @Unique
    private static GlobalFogExtension eca$resolveLocalFogContext(ClientLevel level, Camera camera, float[] strengthHolder) {
        Vec3 cameraPos = camera.getPosition();
        if (cameraPos == null) {
            return null;
        }

        float bestStrength = 0.0f;
        int bestPriority = Integer.MIN_VALUE;
        GlobalFogExtension bestFog = null;

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }

            EntityExtension extension = EntityExtensionManager.getExtension(living.getType());
            if (extension == null) {
                continue;
            }

            GlobalFogExtension fog = extension.globalFogExtension();
            if (fog == null || !fog.enabled() || fog.globalMode()) {
                continue;
            }

            float radius = fog.radius();
            if (!(radius > 0.0f)) {
                continue;
            }

            float distance = (float)Math.sqrt(living.distanceToSqr(cameraPos));
            if (distance > radius) {
                continue;
            }

            float strength = 1.0f - (distance / radius);
            if (strength > bestStrength || (Math.abs(strength - bestStrength) < 0.0001f && extension.getPriority() > bestPriority)) {
                bestStrength = strength;
                bestPriority = extension.getPriority();
                bestFog = fog;
            }
        }

        if (bestFog == null || bestStrength <= 0.0f) {
            return null;
        }

        strengthHolder[0] = Mth.clamp(bestStrength, 0.0f, 1.0f);
        return bestFog;
    }

    @Unique
    private static float eca$clampColor(float color) {
        return Math.max(0.0f, Math.min(1.0f, color));
    }

}
