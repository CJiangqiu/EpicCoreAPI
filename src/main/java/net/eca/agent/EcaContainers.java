package net.eca.agent;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.eca.api.EcaAPI;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * ECA custom container classes for entity protection.
 * These containers replace vanilla Minecraft containers via bytecode transformation
 * to intercept removal operations on invulnerable entities.
 */
public final class EcaContainers {

    private EcaContainers() {}

    // 是否启用调试日志
    private static final boolean DEBUG = false;

    /**
     * Check if an entity removal should be protected.
     * @param entity the entity being removed
     * @return true if removal should be blocked
     */
    private static boolean shouldProtectEntity(Entity entity) {
        return EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity);
    }

    /**
     * ECA自定义的ArrayList容器
     * 用于替换MC原版的ClassInstanceMultiMap.allInstances
     */
    public static class EcaArrayList<E> extends ArrayList<E> {

        private static final long serialVersionUID = 1L;

        public EcaArrayList() {
            super();
        }

        public EcaArrayList(int initialCapacity) {
            super(initialCapacity);
        }

        public EcaArrayList(Collection<? extends E> c) {
            super(c);
        }

        @Override
        public boolean remove(Object o) {
            if (o instanceof Entity entity && shouldProtectEntity(entity)) {
                if (DEBUG) {
                    EcaLogger.info("[EcaArrayList] Blocked removal of: {}", o);
                }
                return false;
            }
            return super.remove(o);
        }

        @Override
        public E remove(int index) {
            E element = super.get(index);
            if (element instanceof Entity entity && shouldProtectEntity(entity)) {
                if (DEBUG) {
                    EcaLogger.info("[EcaArrayList] Blocked removal at index {}: {}", index, element);
                }
                return null;
            }
            return super.remove(index);
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            Collection<?> filtered = c.stream()
                .filter(item -> !(item instanceof Entity entity && shouldProtectEntity(entity)))
                .toList();
            return super.removeAll(filtered);
        }

        @Override
        public boolean removeIf(java.util.function.Predicate<? super E> filter) {
            java.util.function.Predicate<E> protectedFilter = item ->
                !(item instanceof Entity entity && shouldProtectEntity(entity)) && filter.test(item);
            return super.removeIf(protectedFilter);
        }
    }

    /**
     * ECA自定义的HashMap容器
     * 用于替换MC原版的EntityLookup.byUuid和ClassInstanceMultiMap.byClass
     */
    @SuppressWarnings("unchecked")
    public static class EcaHashMap<K, V> extends HashMap<K, V> {

        private static final long serialVersionUID = 1L;

        public EcaHashMap() {
            super();
        }

        public EcaHashMap(int initialCapacity) {
            super(initialCapacity);
        }

        public EcaHashMap(int initialCapacity, float loadFactor) {
            super(initialCapacity, loadFactor);
        }

        public EcaHashMap(Map<? extends K, ? extends V> m) {
            super(m);
        }

        @Override
        public V put(K key, V value) {
            return super.put(key, wrapListIfNeeded(value));
        }

        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
            V value = super.computeIfAbsent(key, mappingFunction);
            V wrapped = wrapListIfNeeded(value);
            if (wrapped != value) {
                super.put(key, wrapped);
                return wrapped;
            }
            return value;
        }

        @Override
        public V remove(Object key) {
            if (shouldProtectRemoval(key)) {
                if (DEBUG) {
                    EcaLogger.info("[EcaHashMap] Blocked removal of key: {}", key);
                }
                return null;
            }
            return super.remove(key);
        }

        private boolean shouldProtectRemoval(Object key) {
            V value = super.get(key);

            // 情况1：值直接是Entity（用于EntityLookup.byUuid）
            if (value instanceof Entity entity) {
                return shouldProtectEntity(entity);
            }

            // 情况2：值是List（用于ClassInstanceMultiMap.byClass）
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Entity entity && shouldProtectEntity(entity)) {
                        return true;
                    }
                }
            }

            return false;
        }

        private V wrapListIfNeeded(V value) {
            if (value instanceof EcaArrayList) {
                return value;
            }
            if (value instanceof List<?> list) {
                return (V) new EcaArrayList<>(list);
            }
            return value;
        }
    }

    /**
     * ECA自定义的Int2ObjectOpenHashMap容器
     * 用于替换MC原版的ChunkMap.entityMap和EntityLookup.byId
     */
    public static class EcaInt2ObjectOpenHashMap<V> extends Int2ObjectOpenHashMap<V> {

        private static final long serialVersionUID = 1L;

        public EcaInt2ObjectOpenHashMap() {
            super();
        }

        public EcaInt2ObjectOpenHashMap(int expected) {
            super(expected);
        }

        public EcaInt2ObjectOpenHashMap(int expected, float f) {
            super(expected, f);
        }

        @Override
        public V remove(int key) {
            if (shouldProtectRemoval(key)) {
                if (DEBUG) {
                    EcaLogger.info("[EcaInt2ObjectOpenHashMap] Blocked removal of entity id: {}", key);
                }
                return null;
            }
            return super.remove(key);
        }

        private boolean shouldProtectRemoval(int entityId) {
            V value = super.get(entityId);
            if (value instanceof Entity entity) {
                return shouldProtectEntity(entity);
            }
            return false;
        }
    }

    /**
     * ECA自定义的Int2ObjectLinkedOpenHashMap容器
     * 用于替换MC原版的EntityTickList.active和EntityTickList.passive
     */
    public static class EcaInt2ObjectLinkedOpenHashMap<V> extends Int2ObjectLinkedOpenHashMap<V> {

        private static final long serialVersionUID = 1L;

        public EcaInt2ObjectLinkedOpenHashMap() {
            super();
        }

        public EcaInt2ObjectLinkedOpenHashMap(int expected) {
            super(expected);
        }

        public EcaInt2ObjectLinkedOpenHashMap(int expected, float f) {
            super(expected, f);
        }

        @Override
        public V remove(int key) {
            if (shouldProtectRemoval(key)) {
                if (DEBUG) {
                    EcaLogger.info("[EcaInt2ObjectLinkedOpenHashMap] Blocked removal of entity id: {}", key);
                }
                return null;
            }
            return super.remove(key);
        }

        private boolean shouldProtectRemoval(int entityId) {
            V value = super.get(entityId);
            if (value instanceof Entity entity) {
                return shouldProtectEntity(entity);
            }
            return false;
        }
    }
}
