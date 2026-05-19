package net.eca.coremod;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime state for the class-restore attack.
 * Restored entities are tracked by UUID so that injected lifecycle-method guards
 * can decide per-instance whether to delegate to the vanilla implementation.
 *
 * Injected bytecode calls {@link #isRestored(UUID)} via INVOKESTATIC.
 * UUID keys (not entity instances) are used so dead entities never pin the Level.
 */
public final class EcaRestoreHook {

    private EcaRestoreHook() {}

    private static final Set<UUID> RESTORED = ConcurrentHashMap.newKeySet();

    // 注入字节码调用：判断该实体实例是否已被类还原
    /**
     * Check whether the entity with the given UUID has been class-restored.
     * Called from injected bytecode at the head of each guarded lifecycle method.
     * @param uuid the entity UUID
     * @return true if the entity should delegate to the vanilla implementation
     */
    public static boolean isRestored(UUID uuid) {
        return uuid != null && RESTORED.contains(uuid);
    }

    static void mark(UUID uuid) {
        if (uuid != null) RESTORED.add(uuid);
    }

    static void unmark(UUID uuid) {
        if (uuid != null) RESTORED.remove(uuid);
    }

    static void clear() {
        RESTORED.clear();
    }
}
