package net.eca.coremod;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Exposes the transformation capabilities available in the basic build. */
public final class EcaTransformerManager {
    public enum Backend { COREMOD, NONE }

    public record HealthTransformResult(Backend backend, boolean confirmed) {}

    public record LoadedClassInfo(String internalName, boolean modifiable,
                                  boolean livingEntity, boolean entityOnly) {}

    private EcaTransformerManager() {}

    public static Backend backend() { return Backend.COREMOD; }

    public static boolean supportsExtendedRuntime() { return false; }

    public static boolean applyLoadCompleteTransforms() { return true; }

    public static boolean retransformClass(Class<?> clazz) { return false; }

    public static HealthTransformResult retransformHealthClass(Class<?> clazz, boolean refreshTerminal) {
        return new HealthTransformResult(Backend.NONE, false);
    }

    public static boolean isHealthTransformConfirmed(Class<?> clazz) { return false; }

    public static boolean isHealthTransformConfirmed(Class<?> clazz, Backend expectedBackend) { return false; }

    static void invalidateHealthTransformReceipt(String internalName) {}

    public static boolean retransformInternalName(String internalName) { return false; }

    public static boolean retransformLoadedInternalNames(Set<String> internalNames) { return false; }

    public static boolean forEachLoadedClass(Consumer<Class<?>> consumer) { return false; }

    public static boolean forEachLoadedInternalName(Consumer<LoadedClassInfo> consumer) { return false; }

    public static boolean retransformExplicitClasses(List<? extends Class<?>> classes) { return false; }
}
