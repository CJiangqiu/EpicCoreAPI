package net.eca.util.health;

import net.eca.util.health.report.HealthReportText;

import static net.eca.util.health.report.HealthReportText.tr;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.eca.util.EcaLogger;
import net.eca.util.reflect.UnsafeUtil;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 试探性写入的对象图快照：保存实体字段、静态锚点及相关对象的可恢复状态，供探针和反演失败后回滚。
 * 常规捕获沿相关根展开，行为探针使用较浅的捕获范围，避免把一次尝试变成世界级对象扫描。
 * 捕获受时间和槽位数量限制，不完整时拒绝作为写入保障；恢复失败通知 HealthMutationContext 停止后续尝试。
 * 快照只覆盖已捕获的存储，不保证撤销任意外部副作用。
 */
final class ObjectGraphSnapshot {
    private static final long TIME_BUDGET_NANOS = 50_000_000L;
    private static final int MAX_SLOTS = 100_000;
    private static final Set<String> DIAG_DUMPED = ConcurrentHashMap.newKeySet();

    private final List<Slot> slots = new ArrayList<>();
    private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final long deadline;
    private boolean complete = true;

    private ObjectGraphSnapshot(long deadline) {
        this.deadline = deadline;
    }

    static ObjectGraphSnapshot capture(LivingEntity entity, List<Object> roots) {
        ObjectGraphSnapshot snapshot = new ObjectGraphSnapshot(System.nanoTime() + TIME_BUDGET_NANOS);
        snapshot.captureEntityFields(entity);
        snapshot.captureStaticAnchors(entity == null ? null : entity.getClass());
        if (roots != null) {
            for (Object root : roots) snapshot.walk(root);
        }
        if (!snapshot.complete) snapshot.diag(tr("snapshot.incomplete"));
        return snapshot;
    }

    /* 行为探针只需要回滚候选 writer 的直接可达存储；不递归展开世界级根，避免探测本身成为全内存扫描。 */
    static ObjectGraphSnapshot captureProbe(LivingEntity entity, List<Object> roots) {
        ObjectGraphSnapshot snapshot = new ObjectGraphSnapshot(System.nanoTime() + TIME_BUDGET_NANOS);
        snapshot.captureEntityFieldsShallow(entity);
        snapshot.captureSynchedData(entity);
        snapshot.captureStaticFieldsShallow(entity == null ? null : entity.getClass());
        if (roots != null) for (Object root : roots) snapshot.captureRootShallow(root);
        if (!snapshot.complete) snapshot.diag(tr("snapshot.probe_incomplete"));
        return snapshot;
    }

    void restore() {
        if (!complete) HealthMutationContext.rollbackFailed(tr("snapshot.incomplete_restore"));
        for (int i = slots.size() - 1; i >= 0; i--) {
            try {
                if (!slots.get(i).restore()) {
                    diag(tr("snapshot.restore_rejected"));
                    HealthMutationContext.rollbackFailed(tr("snapshot.rejected", i, slots.get(i).getClass().getSimpleName()));
                }
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                diag(tr("snapshot.restore_error", t.getClass().getSimpleName()));
                HealthMutationContext.rollbackFailed(tr("snapshot.error", i, slots.get(i).getClass().getSimpleName(), t.getClass().getSimpleName()));
            }
        }
    }

    boolean readyForWrite() {
        if (!complete) HealthMutationContext.recordEvidence(tr("snapshot.incomplete_write"));
        return complete;
    }

    private void captureEntityFields(LivingEntity entity) {
        if (entity == null) return;
        if (!visited.add(entity)) return;
        for (Class<?> c = entity.getClass(); c != null && c != LivingEntity.class && c != Object.class; c = c.getSuperclass()) {
            captureFields(entity, c, false);
        }
    }

    private void captureEntityFieldsShallow(LivingEntity entity) {
        if (entity == null || !visited.add(entity)) return;
        // 未证实的 writer 可能转调基类 setter；身份、位置等基类状态也必须纳入同一事务回滚。
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    addSlot(new FieldSlot(entity, field, field.get(entity)));
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                    captureFailed(tr("snapshot.probe_field", c.getName() + "." + field.getName()));
                }
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private void captureSynchedData(LivingEntity entity) {
        if (entity == null) return;
        try {
            SynchedEntityData entityData = entity.getEntityData();
            Int2ObjectMap<?> items = (Int2ObjectMap<?>) entityData.itemsById;
            addSlot(new SynchedDataDirtySlot(entityData, entityData.isDirty));
            for (Int2ObjectMap.Entry<?> entry : items.int2ObjectEntrySet()) {
                SynchedEntityData.DataItem item = (SynchedEntityData.DataItem) entry.getValue();
                if (item != null) addSlot(new SynchedDataItemSlot(item, item.value, item.dirty));
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            captureFailed(tr("snapshot.probe_synced", t.getClass().getSimpleName()));
        }
    }

    private void captureStaticAnchors(Class<?> entityClass) {
        for (Class<?> c = entityClass; c != null && c != LivingEntity.class && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (!Modifier.isFinal(field.getModifiers())) addSlot(new FieldSlot(null, field, value));
                    walk(value);
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                    captureFailed(tr("snapshot.static_field", c.getName() + "." + field.getName()));
                }
            }
        }
    }

    private void captureStaticFieldsShallow(Class<?> entityClass) {
        for (Class<?> c = entityClass; c != null && c != LivingEntity.class && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    addSlot(new FieldSlot(null, field, field.get(null)));
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                    captureFailed(tr("snapshot.probe_static_field", c.getName() + "." + field.getName()));
                }
            }
        }
    }

    private void captureRootShallow(Object root) {
        if (root == null || isLeaf(root) || !visited.add(root)) return;
        if (root.getClass().isArray()) {
            captureArrayShallow(root);
        } else if (root instanceof Map<?, ?> map) {
            captureMapShallow(map);
        } else if (root instanceof Collection<?> collection) {
            captureCollectionShallow(collection);
        } else {
            captureFieldsShallow(root, root.getClass());
        }
    }

    private void captureFieldsShallow(Object owner, Class<?> cls) {
        for (Field field : cls.getDeclaredFields()) {
            if (!withinBudget()) return;
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                addSlot(new FieldSlot(owner, field, field.get(owner)));
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                captureFailed(tr("snapshot.root_field", cls.getName() + "." + field.getName()));
            }
        }
    }

    private void captureArrayShallow(Object array) {
        try {
            int length = Array.getLength(array);
            if (length > MAX_SLOTS - slots.size()) {
                complete = false;
                return;
            }
            for (int i = 0; i < length && withinBudget(); i++) {
                addSlot(new ArraySlot(array, i, Array.get(array, i)));
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            captureFailed(tr("snapshot.root_array"));
        }
    }

    private void captureMapShallow(Map<?, ?> map) {
        List<MapEntry> copy = new ArrayList<>();
        try {
            if (map.size() > MAX_SLOTS - slots.size()) {
                complete = false;
                return;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) copy.add(new MapEntry(entry.getKey(), entry.getValue()));
            addSlot(new MapSlot(map, copy));
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            captureFailed(tr("snapshot.root_map", map.getClass().getName()));
        }
    }

    private void captureCollectionShallow(Collection<?> collection) {
        try {
            if (collection.size() > MAX_SLOTS - slots.size()) {
                complete = false;
                return;
            }
            addSlot(new CollectionSlot(collection, new ArrayList<>(collection)));
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            captureFailed(tr("snapshot.root_collection", collection.getClass().getName()));
        }
    }

    private void walk(Object obj) {
        if (obj == null || isLeaf(obj) || !withinBudget()) return;
        if (!visited.add(obj)) return;

        Class<?> cls = obj.getClass();
        if (cls.isArray()) {
            captureArray(obj);
            return;
        }
        if (obj instanceof Map<?, ?> map) {
            captureMap(map);
            return;
        }
        if (obj instanceof Collection<?> collection) {
            captureCollection(collection);
            return;
        }
        if (isSkippable(cls)) return;

        for (Class<?> c = cls; c != null && c != Object.class && !isSkippable(c); c = c.getSuperclass()) {
            captureFields(obj, c, false);
        }
    }

    private void captureFields(Object owner, Class<?> cls, boolean includeStatic) {
        for (Field field : cls.getDeclaredFields()) {
            if (!withinBudget()) return;
            if (Modifier.isStatic(field.getModifiers()) != includeStatic) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(owner);
                addSlot(new FieldSlot(owner, field, value));
                walk(value);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                captureFailed(tr("snapshot.field", cls.getName() + "." + field.getName()));
            }
        }
    }

    private void captureArray(Object array) {
        int length;
        try {
            length = Array.getLength(array);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            captureFailed(tr("snapshot.array"));
            return;
        }
        for (int i = 0; i < length; i++) {
            if (!withinBudget()) return;
            try {
                Object value = Array.get(array, i);
                addSlot(new ArraySlot(array, i, value));
                walk(value);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                captureFailed(tr("snapshot.array_slot"));
            }
        }
    }

    private void captureMap(Map<?, ?> map) {
        List<MapEntry> copy = new ArrayList<>();
        try {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.add(new MapEntry(entry.getKey(), entry.getValue()));
            }
            addSlot(new MapSlot(map, copy));
            for (MapEntry entry : copy) {
                walk(entry.key());
                walk(entry.value());
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            captureFailed(tr("snapshot.map", map.getClass().getName()));
        }
    }

    private void captureCollection(Collection<?> collection) {
        List<Object> copy = new ArrayList<>();
        try {
            copy.addAll(collection);
            addSlot(new CollectionSlot(collection, copy));
            for (Object value : copy) walk(value);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            captureFailed(tr("snapshot.collection", collection.getClass().getName()));
        }
    }

    private boolean withinBudget() {
        if (System.nanoTime() > deadline || slots.size() >= MAX_SLOTS) {
            complete = false;
            return false;
        }
        return true;
    }

    private void addSlot(Slot slot) {
        if (withinBudget()) slots.add(slot);
    }

    private boolean isLeaf(Object obj) {
        return obj instanceof Number || obj instanceof CharSequence || obj instanceof Boolean
                || obj instanceof Character || obj instanceof Enum<?> || obj instanceof Class<?>
                || obj instanceof MethodHandle || obj instanceof MethodType || obj instanceof VarHandle
                || obj.getClass().getName().startsWith("java.lang.invoke.");
    }

    private boolean isSkippable(Class<?> cls) {
        String name = cls.getName();
        return name.startsWith("java.lang.reflect.")
                || name.startsWith("java.lang.invoke.")
                || name.startsWith("java.security.")
                || name.startsWith("java.io.")
                || name.startsWith("java.nio.")
                || name.startsWith("sun.")
                || name.startsWith("jdk.")
                || name.startsWith("net.minecraft.")
                || name.startsWith("net.minecraftforge.")
                || name.startsWith("com.mojang.")
                || name.startsWith("org.objectweb.")
                || name.startsWith("org.slf4j.")
                || name.startsWith("org.apache.logging.");
    }

    private void captureFailed(HealthReportText reason) {
        complete = false;
        diag(reason);
    }

    private void diag(HealthReportText reason) {
        HealthMutationContext.recordEvidence(tr("snapshot.diagnostic", reason));
        String raw = HealthReportText.render(reason, "en_us");
        if (DIAG_DUMPED.add(raw)) EcaLogger.info("[ObjectGraphSnapshot] {}", raw);
    }

    private interface Slot {
        boolean restore();
    }

    private record FieldSlot(Object owner, Field field, Object value) implements Slot {
        @Override public boolean restore() {
            try {
                Object current = field.get(owner);
                if (field.getType().isPrimitive() ? Objects.equals(current, value) : current == value) return true;
                field.set(owner, value);
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                boolean restored = owner != null && UnsafeUtil.unsafePutField(owner, field, value);
                if (!restored) EcaLogger.info("[HealthMutation] field restore failed: {}", t.getClass().getSimpleName());
                return restored;
            }
        }
    }

    private record ArraySlot(Object array, int index, Object value) implements Slot {
        @Override public boolean restore() {
            Array.set(array, index, value);
            return true;
        }
    }

    @SuppressWarnings("rawtypes")
    private record SynchedDataItemSlot(SynchedEntityData.DataItem item, Object value, boolean dirty) implements Slot {
        @Override public boolean restore() {
            item.value = value;
            item.dirty = dirty;
            return true;
        }
    }

    private record SynchedDataDirtySlot(SynchedEntityData entityData, boolean dirty) implements Slot {
        @Override public boolean restore() {
            entityData.isDirty = dirty;
            return true;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private record MapSlot(Map map, List<MapEntry> copy) implements Slot {
        @Override public boolean restore() {
            boolean unchanged = map.size() == copy.size();
            if (unchanged) for (MapEntry entry : copy) {
                if (!map.containsKey(entry.key()) || map.get(entry.key()) != entry.value()) { unchanged = false; break; }
            }
            if (unchanged) return true;
            Set<Object> savedKeys = map instanceof IdentityHashMap
                    ? Collections.newSetFromMap(new IdentityHashMap<>()) : new HashSet<>();
            for (MapEntry entry : copy) savedKeys.add(entry.key());
            List<Object> removed = new ArrayList<>();
            for (Object key : map.keySet()) if (!savedKeys.contains(key)) removed.add(key);
            for (Object key : removed) map.remove(key);
            for (MapEntry entry : copy) if (!map.containsKey(entry.key()) || map.get(entry.key()) != entry.value()) map.put(entry.key(), entry.value());
            if (map.size() != copy.size()) return false;
            for (MapEntry entry : copy) if (!map.containsKey(entry.key()) || map.get(entry.key()) != entry.value()) return false;
            return true;
        }
    }

    private record MapEntry(Object key, Object value) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private record CollectionSlot(Collection collection, List<Object> copy) implements Slot {
        @Override public boolean restore() {
            if (collection.size() == copy.size()) {
                int index = 0;
                boolean unchanged = true;
                for (Object value : collection) if (value != copy.get(index++)) { unchanged = false; break; }
                if (unchanged) return true;
            }
            collection.clear();
            collection.addAll(copy);
            if (collection.size() != copy.size()) return false;
            if (collection instanceof Set) {
                Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
                identities.addAll(copy);
                for (Object value : collection) if (!identities.contains(value)) return false;
                return true;
            }
            int index = 0;
            for (Object value : collection) if (value != copy.get(index++)) return false;
            return true;
        }
    }
}
