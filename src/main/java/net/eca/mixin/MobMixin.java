package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.eca.config.EcaConfiguration;
import net.eca.util.entity_extension.ForceLoadingManager;
import net.eca.util.faction.FactionUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public class MobMixin {

    // 阻止将无敌实体或同阵营实体锁定为目标
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void onSetTarget(LivingEntity target, CallbackInfo ci) {
        if (target == null) return;
        Mob self = (Mob) (Object) this;
        // ECA 无敌实体不可被锁定
        if (EcaConfiguration.getDefenceInvulnerableUntargetableSafely() && EcaAPI.isInvulnerable(target)) {
            ci.cancel();
            return;
        }
        // 阵营保护：同阵营或友好阵营不设为目标
        if (!FactionUtil.canAttack(self, target)) {
            ci.cancel();
        }
    }

    //强加载实体：阻止因距离过远导致的自然消失
    @Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
    private void eca$preventForceDespawn(CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        if (ForceLoadingManager.shouldForceLoad(self)) {
            ci.cancel();
        }
    }
}
