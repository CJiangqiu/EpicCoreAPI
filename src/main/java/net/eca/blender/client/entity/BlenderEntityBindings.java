package net.eca.blender.client.entity;

import net.eca.blender.entity.BlenderEntityBinding;
import net.eca.blender.entity.BlenderRenderPolicy;
import net.eca.util.EcaLogger;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Explicit core bindings take precedence over the optional compatibility resolver. */
@OnlyIn(Dist.CLIENT)
public final class BlenderEntityBindings {
    private static final Map<EntityType<?>, BlenderEntityBinding> BINDINGS = new ConcurrentHashMap<>();
    private static final Set<Class<?>> FAILURES = ConcurrentHashMap.newKeySet();
    private static volatile Function<LivingEntity, BlenderEntityBinding> fallback = entity -> null;

    private BlenderEntityBindings() { }

    public static boolean register(EntityType<?> type, BlenderEntityBinding binding) {
        return BINDINGS.putIfAbsent(Objects.requireNonNull(type), Objects.requireNonNull(binding)) == null;
    }

    public static boolean unregister(EntityType<?> type, BlenderEntityBinding binding) {
        return BINDINGS.remove(type, binding);
    }

    public static void setFallbackResolver(Function<LivingEntity, BlenderEntityBinding> resolver) {
        fallback = Objects.requireNonNull(resolver);
    }

    public static BlenderEntityBinding resolve(LivingEntity entity) {
        if (entity == null) return null;
        BlenderEntityBinding binding = BINDINGS.get(entity.getType());
        if (binding != null) return binding;
        try {
            return fallback.apply(entity);
        } catch (Throwable failure) {
            if (FAILURES.add(fallback.getClass())) EcaLogger.error("Blender binding resolution failed", failure);
            return null;
        }
    }

    public static boolean replacesBody(BlenderEntityBinding binding) {
        if (binding == null) return false;
        try {
            return binding.renderPolicy() == BlenderRenderPolicy.REPLACE;
        } catch (Throwable failure) {
            if (FAILURES.add(binding.getClass())) EcaLogger.error("Blender render policy failed", failure);
            return false;
        }
    }
}
