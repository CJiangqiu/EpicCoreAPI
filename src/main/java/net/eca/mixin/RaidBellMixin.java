package net.eca.mixin;

import net.eca.util.raid.RaidManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BellBlock.class)
public abstract class RaidBellMixin {
    @Inject(method = "use", at = @At("RETURN"))
    private void eca$afterBellUse(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit,
                                 CallbackInfoReturnable<InteractionResult> cir) {
        // 成功的方块交互会消费本次操作，不再尝试另一只手；红石和弹射物不经过此入口。
        if (cir.getReturnValue().consumesAction() && level instanceof ServerLevel serverLevel
                && player instanceof ServerPlayer serverPlayer) {
            RaidManager.onBellUsed(serverLevel, serverPlayer, pos);
        }
    }
}
