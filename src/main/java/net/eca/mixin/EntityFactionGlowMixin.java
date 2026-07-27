package net.eca.mixin;

import net.eca.client.FactionGlowData;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/*
 * 阵营发光注入 —— 复用原版队伍描边渲染，无需自定义渲染层。
 *
 * 与 EntityMixin 分开是端位要求：Entity 是双端类，EntityMixin 注册在通用段，
 * 不能引用 net.eca.client 下的客户端类。本 mixin 注册在 client 段，只在客户端加载。
 *
 * 集成服务端（单人游戏）下 Entity 仍可能属于 ServerLevel，因此 isClientSide 门控必须保留。
 */
@OnlyIn(Dist.CLIENT)
@Mixin(Entity.class)
public class EntityFactionGlowMixin {

    // 将阵营发光映射中的实体标记为 glowing
    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void eca$factionGlow(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide() && FactionGlowData.isGlowing(self.getId())) {
            cir.setReturnValue(true);
        }
    }

    // 覆盖发光的描边颜色为对应阵营关系颜色
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void eca$factionGlowColor(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide() && FactionGlowData.isGlowing(self.getId())) {
            cir.setReturnValue(FactionGlowData.getColor(self.getId()));
        }
    }
}
