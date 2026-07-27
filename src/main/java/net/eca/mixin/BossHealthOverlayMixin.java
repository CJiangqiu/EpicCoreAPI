package net.eca.mixin;

import net.eca.client.render.EcaBossBarRenderer;
import net.eca.util.entity_extension.EntityExtensionClientState;
import net.eca.util.entity_extension.EntityExtension;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.EntityExtensionSafeAccess;
import net.eca.util.entity_extension.BossBarExtension;
import net.eca.util.raid.RaidBarState;
import net.eca.util.raid.RaidBossBarExtension;
import net.eca.util.raid.RaidClientState;
import net.eca.util.raid.RaidDefinition;
import net.eca.util.raid.RaidManager;
import net.eca.util.raid.RaidSafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyIn(Dist.CLIENT)
@Mixin(BossHealthOverlay.class)
public class BossHealthOverlayMixin {

    @Inject(method = "drawBar(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/world/BossEvent;)V", at = @At("HEAD"), cancellable = true)
    private void eca$drawCustomBossBar(GuiGraphics graphics, int x, int y, BossEvent event, CallbackInfo ci) {
        // 实体扩展优先；未命中再尝试袭击血条
        if (eca$tryRenderEntityExtensionBar(graphics, y, event)
                || eca$tryRenderRaidBar(graphics, y, event)) {
            ci.cancel();
        }
    }

    // ==================== 实体扩展血条 ====================

    @Unique
    private boolean eca$tryRenderEntityExtensionBar(GuiGraphics graphics, int y, BossEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        EntityType<?> mappedType = EntityExtensionClientState.getBossEventType(event.getId());
        if (mappedType == null) {
            return false;
        }

        EntityExtension extension = EntityExtensionManager.getExtension(mappedType);
        if (extension == null) {
            return false;
        }

        UUID entityUuid = EntityExtensionClientState.getBossEventEntityUuid(event.getId());
        LivingEntity bossEntity = null;
        if (entityUuid != null) {
            for (Entity e : minecraft.level.entitiesForRendering()) {
                if (e.getUUID().equals(entityUuid) && e instanceof LivingEntity living) {
                    bossEntity = living;
                    break;
                }
            }
        }

        BossBarExtension bossBar = EntityExtensionSafeAccess.bossBarExtension(extension, bossEntity);
        if (bossBar == null || !bossBar.enabled()) {
            return false;
        }
        if (!bossBar.shouldRender(bossEntity)) {
            return true;
        }

        EcaBossBarRenderer.BarAppearance appearance = new EcaBossBarRenderer.BarAppearance();
        appearance.frameTexture = bossBar.getFrameTexture();
        appearance.fillTexture = bossBar.getFillTexture();
        appearance.frameRenderType = bossBar.getFrameRenderType();
        appearance.fillRenderType = bossBar.getFillRenderType();
        appearance.frameWidth = bossBar.getFrameWidth();
        appearance.frameHeight = bossBar.getFrameHeight();
        appearance.fillWidth = bossBar.getFillWidth();
        appearance.fillHeight = bossBar.getFillHeight();
        appearance.frameOffsetX = bossBar.getFrameOffsetX();
        appearance.frameOffsetY = bossBar.getFrameOffsetY();
        appearance.fillOffsetX = bossBar.getFillOffsetX();
        appearance.fillOffsetY = bossBar.getFillOffsetY();
        appearance.frameAlpha = bossBar.getFrameAlpha();
        appearance.fillAlpha = bossBar.getFillAlpha();

        // 启用了 bossBarExtension 但未设置任何自定义渲染 → 隐藏原版 bar，不渲染任何内容
        if (appearance.isEmpty()) {
            return true;
        }

        return EcaBossBarRenderer.draw(graphics, y, event.getProgress(), appearance,
                extension.getClass().getName());
    }

    // ==================== 袭击血条 ====================

    @Unique
    private boolean eca$tryRenderRaidBar(GuiGraphics graphics, int y, BossEvent event) {
        RaidBarState state = RaidClientState.getBarState(event.getId());
        if (state == null) {
            return false;
        }

        RaidDefinition definition = RaidManager.getDefinition(state.getDefinitionId());
        if (definition == null) {
            return false;
        }

        RaidBossBarExtension bossBar = RaidSafeAccess.bossBarExtension(definition);
        if (bossBar == null || !RaidSafeAccess.enabled(bossBar)) {
            return false;
        }
        if (!RaidSafeAccess.shouldRender(bossBar, state)) {
            return true;
        }

        EcaBossBarRenderer.BarAppearance appearance = RaidSafeAccess.resolveAppearance(bossBar, state);

        // 启用了自定义血条但未设置任何可绘制内容 → 隐藏原版 bar
        if (appearance.isEmpty()) {
            return true;
        }

        float progress = RaidSafeAccess.progress(bossBar, state, event.getProgress());
        return EcaBossBarRenderer.draw(graphics, y, progress, appearance,
                definition.getClass().getName());
    }
}
