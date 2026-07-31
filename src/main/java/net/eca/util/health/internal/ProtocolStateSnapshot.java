package net.eca.util.health.internal;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ProtocolStateSnapshot {
    private static final long TIME_BUDGET_NANOS = 50_000_000L;
    private static final int MAX_SLOTS = 100_000;
    private static final Set<String> DIAG_DUMPED = ConcurrentHashMap.newKeySet();

    private final List<Slot> slots = new ArrayList<>();
    private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final long deadline;
    private boolean complete = true;

    private ProtocolStateSnapshot(long deadline) {
        this.deadline = deadline;
    }

    static ProtocolStateSnapshot capture(LivingEntity entity, List<Object> roots) {
        ProtocolStateSnapshot snapshot = new ProtocolStateSnapshot(System.nanoTime() + TIME_BUDGET_NANOS);
        snapshot.captureEntityFields(entity);
        snapshot.captureStaticAnchors(entity == null ? null : entity.getClass());
        if (roots != null) {
            for (Object root : roots) snapshot.walk(root);
        }
        if (!snapshot.complete) snapshot.diag("snapshot incomplete");
        return snapshot;
    }

    /* 行为探针只需要回滚候选 writer 的直接可达存储；不递归展开世界级根，避免探测本身成为全内存扫描。 */
    static ProtocolStateSnapshot captureProbe(LivingEntity entity, List<Object> roots) {
        ProtocolStateSnapshot snapshot = new ProtocolStateSnapshot(System.nanoTime() + TIME_BUDGET_NANOS);
        snapshot.captureEntityFieldsShallow(entity);
        snapshot.captureSynchedData(entity);
        snapshot.captureStaticFieldsShallow(entity == null ? null : entity.getClass());
        if (roots != null) for (Object root : roots) snapshot.captureRootShallow(root);
        if (!snapshot.complete) snapshot.diag("probe snapshot incomplete");
        return snapshot;
    }

    void restore() {
        for (int i = slots.size() - 1; i >= 0; i--) {
            try {
                slots.get(i).restore();
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                diag("restore failed: " + t.getClass().getSimpleName());
            }
        }
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
        for (Class<?> c = entity.getClass(); c != null && c != LivingEntity.class && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    addSlot(new FieldSlot(entity, field, field.get(entity)));
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                    diag("probe field capture failed: " + c.getName() + "." + field.getName());
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
            diag("probe SynchedEntityData capture failed: " + t.getClass().getSimpleName());
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
                    diag("static field capture failed: " + c.getName() + "." + field.getName());
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
                    diag("probe static field capture failed: " + c.getName() + "." + field.getName());
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
                diag("probe root field capture failed: " + cls.getName() + "." + field.getName());
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
            diag("probe root array capture failed");
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
            diag("probe root map capture failed: " + map.getClass().getName());
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
            diag("probe root collection capture failed: " + collection.getClass().getName());
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
                diag("field capture failed: " + cls.getName() + "." + field.getName());
            }
        }
    }

    private void captureArray(Object array) {
        int length;
        try {
            length = Array.getLength(array);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            diag("array capture failed");
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
                diag("array slot capture failed");
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
            diag("map capture failed: " + map.getClass().getName());
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
            diag("collection capture failed: " + collection.getClass().getName());
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

    private void diag(String reason) {
        if (DIAG_DUMPED.add(reason)) EcaLogger.info("[ProtocolStateSnapshot] {}", reason);
    }

    private interface Slot {
        void restore();
    }

    private record FieldSlot(Object owner, Field field, Object value) implements Slot {
        @Override public void restore() {
            try {
                field.set(owner, value);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                if (owner == null || !UnsafeUtil.unsafePutField(owner, field, value)) throw new IllegalStateException(t);
            }
        }
    }

    private record ArraySlot(Object array, int index, Object value) implements Slot {
        @Override public void restore() {
            Array.set(array, index, value);
        }
    }

    @SuppressWarnings("rawtypes")
    private record SynchedDataItemSlot(SynchedEntityData.DataItem item, Object value, boolean dirty) implements Slot {
        @Override public void restore() {
            item.value = value;
            item.dirty = dirty;
        }
    }

    private record SynchedDataDirtySlot(SynchedEntityData entityData, boolean dirty) implements Slot {
        @Override public void restore() {
            entityData.isDirty = dirty;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private record MapSlot(Map map, List<MapEntry> copy) implements Slot {
        @Override public void restore() {
            map.clear();
            for (MapEntry entry : copy) map.put(entry.key(), entry.value());
        }
    }

    private record MapEntry(Object key, Object value) {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    private record CollectionSlot(Collection collection, List<Object> copy) implements Slot {
        @Override public void restore() {
            collection.clear();
            collection.addAll(copy);
        }
    }
}


