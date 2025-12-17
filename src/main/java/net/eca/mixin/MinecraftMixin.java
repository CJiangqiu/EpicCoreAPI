package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import javax.annotation.Nullable;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Shadow
    @Nullable
    public LocalPlayer player;

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen modifyScreen(Screen screen) {
        // 如果尝试设置死亡界面且玩家无敌，替换为null
        if (screen instanceof DeathScreen && EcaAPI.isInvulnerable(this.player)) {
            return null;
        }
        return screen;
    }
}
