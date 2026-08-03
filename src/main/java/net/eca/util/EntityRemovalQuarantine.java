package net.eca.util;

import net.eca.util.selector.EcaEntitySelector;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps entities invisible to normal container reads while physical removal is incomplete.
 */
public final class EntityRemovalQuarantine {

    private static final Object ACTIVE_LOCK = new Object();
    private static final IdentityHashMap<Entity, ServerLevel> ACTIVE = new IdentityHashMap<>();
    private static final ReferenceQueue<Entity> RETIRED_QUEUE = new ReferenceQueue<>();
    private static final ConcurrentHashMap<IdentityWeakReference, Boolean> RETIRED = new ConcurrentHashMap<>();
    private static volatile boolean hasActiveRemovals;
    private static volatile boolean hasRetiredEntities;

    private EntityRemovalQuarantine() {
    }

    // 仅在清除已获准后登记，普通受保护实体不会进入隔离。
    public static void begin(ServerLevel level, Entity entity) {
        if (level == null || entity == null) return;
        synchronized (ACTIVE_LOCK) {
            ACTIVE.put(entity, level);
            hasActiveRemovals = true;
        }
    }

    // 热点读取先检查快门，无活动清除时不进入同步块。
    public static boolean isQueryHidden(Entity entity) {
        if (!hasActiveRemovals || entity == null) return false;
        synchronized (ACTIVE_LOCK) {
            return ACTIVE.containsKey(entity);
        }
    }

    public static boolean hasActiveRemovals() {
        return hasActiveRemovals;
    }

    public static boolean hasBlockedAdditions() {
        return hasActiveRemovals || hasRetiredEntities;
    }

    // 活动隔离与已完成墓碑都禁止同一实例重新加入容器。
    public static boolean shouldBlockAdd(Entity entity) {
        if (entity == null) return false;
        if (isQueryHidden(entity)) return true;
        if (!hasRetiredEntities) return false;
        expungeRetiredReferences();
        if (!hasRetiredEntities) return false;
        return RETIRED.containsKey(new IdentityWeakReference(entity));
    }

    // 物理删除完成后关闭 getter 过滤，仅保留低频写入口墓碑。
    public static void reconcile(ServerLevel level, Entity entity) {
        if (level == null || entity == null || !isQueryHidden(entity)) return;
        if (EcaEntitySelector.containsPhysicalInstance(level, entity)) return;
        retire(entity);
    }

    // 延迟容器操作完成后按 tick 复查，正常状态由快门直接返回。
    public static void onServerTick(MinecraftServer server) {
        if (!hasActiveRemovals || server == null) return;
        List<Map.Entry<Entity, ServerLevel>> snapshot;
        synchronized (ACTIVE_LOCK) {
            snapshot = new ArrayList<>(ACTIVE.entrySet());
        }
        for (Map.Entry<Entity, ServerLevel> entry : snapshot) {
            ServerLevel level = entry.getValue();
            if (level != null && level.getServer() == server) {
                reconcile(level, entry.getKey());
            }
        }
    }

    public static void clear() {
        synchronized (ACTIVE_LOCK) {
            ACTIVE.clear();
            hasActiveRemovals = false;
        }
        RETIRED.clear();
        hasRetiredEntities = false;
        while (RETIRED_QUEUE.poll() != null) {
            // 清空引用队列，避免集成服务器跨存档残留。
        }
    }

    private static void retire(Entity entity) {
        expungeRetiredReferences();
        RETIRED.put(new IdentityWeakReference(entity, RETIRED_QUEUE), Boolean.TRUE);
        hasRetiredEntities = true;
        synchronized (ACTIVE_LOCK) {
            ACTIVE.remove(entity);
            hasActiveRemovals = !ACTIVE.isEmpty();
        }
    }

    private static void expungeRetiredReferences() {
        IdentityWeakReference reference;
        while ((reference = (IdentityWeakReference) RETIRED_QUEUE.poll()) != null) {
            RETIRED.remove(reference);
        }
        if (RETIRED.isEmpty()) {
            hasRetiredEntities = false;
        }
    }

    private static final class IdentityWeakReference extends WeakReference<Entity> {
        private final int identityHash;

        private IdentityWeakReference(Entity entity) {
            super(entity);
            this.identityHash = System.identityHashCode(entity);
        }

        private IdentityWeakReference(Entity entity, ReferenceQueue<Entity> queue) {
            super(entity, queue);
            this.identityHash = System.identityHashCode(entity);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof IdentityWeakReference other)) return false;
            Entity entity = get();
            return entity != null && entity == other.get();
        }
    }
}
