package net.eca.pro.ingot;

import forgevm.forge.FvmCallback;
import forgevm.forge.FvmIngot;
import forgevm.forge.InjectPoint;
import net.eca.coremod.EcaProFallbackHooks;

import java.util.List;

public final class EntityStateFallbackIngots {
    private static final String LIVING = "net.minecraft.world.entity.LivingEntity";
    private static final String ENTITY = "net.minecraft.world.entity.Entity";

    private EntityStateFallbackIngots() {
    }

    public static List<FvmIngot> create() {
        return List.of(
                new GetHealthHead(),
                new GetHealthReturn(),
                new GetMaxHealth(),
                new IsDeadOrDying(),
                new IsAlive(),
                new IsRemoved());
    }

    public static final class GetHealthHead extends LivingIngot {
        public GetHealthHead() {
            super("m_21223_(),getHealth()", HEAD);
        }

        public static void apply(FvmCallback callback) {
            Float value = EcaProFallbackHooks.getHealthHead(callback.getInstance());
            if (value != null) callback.setReturnValue(value);
        }
    }

    public static final class GetHealthReturn extends LivingIngot {
        public GetHealthReturn() {
            super("m_21223_(),getHealth()", RETURN);
        }

        public static void apply(FvmCallback callback) {
            Float value = EcaProFallbackHooks.getHealthReturn(
                    callback.getInstance(), callback.getOriginalReturnValue());
            if (value != null) callback.setReturnValue(value);
        }
    }

    public static final class GetMaxHealth extends LivingIngot {
        public GetMaxHealth() {
            super("m_21233_(),getMaxHealth()", HEAD);
        }

        public static void apply(FvmCallback callback) {
            Float value = EcaProFallbackHooks.getMaxHealth(callback.getInstance());
            if (value != null) callback.setReturnValue(value);
        }
    }

    public static final class IsDeadOrDying extends LivingIngot {
        public IsDeadOrDying() {
            super("m_21224_(),isDeadOrDying()", HEAD);
        }

        public static void apply(FvmCallback callback) {
            Boolean value = EcaProFallbackHooks.isDeadOrDying(callback.getInstance());
            if (value != null) callback.setReturnValue(value);
        }
    }

    public static final class IsAlive extends LivingIngot {
        public IsAlive() {
            super("m_6084_(),isAlive()", HEAD);
        }

        public static void apply(FvmCallback callback) {
            Boolean value = EcaProFallbackHooks.isAlive(callback.getInstance());
            if (value != null) callback.setReturnValue(value);
        }
    }

    public static final class IsRemoved extends FvmIngot {
        public IsRemoved() {
            super(ENTITY, "m_213877_(),isRemoved()", HEAD);
        }

        @Override
        public boolean includeSubclasses() {
            return true;
        }

        public static void apply(FvmCallback callback) {
            Boolean value = EcaProFallbackHooks.isRemoved(callback.getInstance());
            if (value != null) callback.setReturnValue(value);
        }
    }

    private abstract static class LivingIngot extends FvmIngot {
        LivingIngot(String method, InjectPoint point) {
            super(LIVING, method, point);
        }

        @Override
        public boolean includeSubclasses() {
            return true;
        }
    }
}
