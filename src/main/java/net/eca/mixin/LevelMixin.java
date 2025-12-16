package net.eca.mixin;

import net.eca.api.EcaAPI;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Mixin(Level.class)
public class LevelMixin {

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("RETURN"), cancellable = true)
    private void onGetEntities(@Nullable Entity except, AABB area, Predicate<? super Entity> predicate, CallbackInfoReturnable<List<Entity>> cir) {
        List<Entity> originalList = cir.getReturnValue();
        if (originalList == null || originalList.isEmpty()) {
            return;
        }

        List<Entity> filteredList = originalList.stream()
            .filter(entity -> !EcaAPI.isInvulnerable(entity))
            .collect(Collectors.toList());

        cir.setReturnValue(filteredList);
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("RETURN"), cancellable = true)
    private <T extends Entity> void onGetEntitiesTyped(EntityTypeTest<Entity, T> typeTest, AABB area, Predicate<? super T> predicate, CallbackInfoReturnable<List<T>> cir) {
        List<T> originalList = cir.getReturnValue();
        if (originalList == null || originalList.isEmpty()) {
            return;
        }

        List<T> filteredList = originalList.stream()
            .filter(entity -> !EcaAPI.isInvulnerable(entity))
            .collect(Collectors.toList());

        cir.setReturnValue(filteredList);
    }
}
