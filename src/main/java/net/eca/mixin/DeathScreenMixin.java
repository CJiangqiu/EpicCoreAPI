package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.health.HealthLockManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DeathScreen.class)
public class DeathScreenMixin {

    @Unique
    private static boolean eca$shouldCloseDeathScreen(LocalPlayer player) {
        if (player == null) return false;
        if (EcaAPI.isInvulnerable(player)) return true;
        if (EntityUtil.RESURRECTION_TRACKED != null
            && player.getEntityData().get(EntityUtil.RESURRECTION_TRACKED)) return true;
        if (HealthLockManager.getLock(player) != null) return true;
        return false;
    }

    @Inject(method = "init", at = @At("HEAD"), cancellable = true)
    private void onInit(CallbackInfo ci) {
        if (eca$shouldCloseDeathScreen(Minecraft.getInstance().player)) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (eca$shouldCloseDeathScreen(Minecraft.getInstance().player)) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void onTick(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (eca$shouldCloseDeathScreen(minecraft.player)) {
            ci.cancel();
            minecraft.setScreen(null);
        }
    }
}
