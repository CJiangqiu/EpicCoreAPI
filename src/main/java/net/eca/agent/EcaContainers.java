package net.eca.agent;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import net.eca.api.EcaAPI;
import net.eca.util.EntityUtil;
import net.eca.util.InvulnerableEntityManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntitySection;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * ECA custom container classes for entity protection.
 * These containers replace vanilla Minecraft containers via bytecode transformation
 * to intercept removal operations on invulnerable entities.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class EcaContainers {

    private EcaContainers() {}

    // ==================== 回调系统 ====================

    public interface RemovalCallback {
        void onRemove(Entity entity);
    }

    private static final List<WeakReference<RemovalCallback>> REMOVAL_CALLBACKS = new CopyOnWriteArrayList<>();

    private static void registerRemovalCallback(RemovalCallback callback) {
        REMOVAL_CALLBACKS.add(new WeakReference<>(callback));
    }

    public static void callRemove(Entity entity) {
        if (entity == null) return;
        Iterator<WeakReference<RemovalCallback>> it = REMOVAL_CALLBACKS.iterator();
        while (it.hasNext()) {
            RemovalCallback cb = it.next().get();
            if (cb == null) {
                it.remove();
                continue;
            }
            try {
                cb.onRemove(entity);
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 保护逻辑 ====================

    private static final Map<Class<?>, Field> ENTITY_FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> NO_ENTITY_FIELD_CLASSES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<SectionRemovalContext> SECTION_REMOVAL_CONTEXT = new ThreadLocal<>();

    private static final class SectionRemovalContext {
        private final Long2ObjectOpenHashMap<?> sections;
        private final long sectionKey;

        private SectionRemovalContext(Long2ObjectOpenHashMap<?> sections, long sectionKey) {
            this.sections = sections;
            this.sectionKey = sectionKey;
        }
    }

    /**
     * Check if an entity removal should be protected.
     * @param entity the entity being removed
     * @return true if removal should be blocked
     */
    private static boolean shouldProtectEntity(Entity entity) {
        return EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity);
    }

    private static boolean shouldProtectTarget(Object value) {
        Entity entity = resolveEntityFromContainerValue(value);
        return entity != null && shouldProtectEntity(entity);
    }

    private static boolean hasProtectedEntity(Collection<?> values) {
        for (Object value : values) {
            if (shouldProtectTarget(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProtectedEntityInListValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return false;
        }
        return hasProtectedEntity(list);
    }

    private static Entity resolveEntityFromContainerValue(Object value) {
        if (value instanceof Entity entity) {
            return entity;
        }
        if (value == null) {
            return null;
        }

        Class<?> valueClass = value.getClass();
        if (NO_ENTITY_FIELD_CLASSES.contains(valueClass)) {
            return null;
        }

        Field entityField = ENTITY_FIELD_CACHE.get(valueClass);
        if (entityField == null) {
            entityField = findEntityField(valueClass);
            if (entityField == null) {
                NO_ENTITY_FIELD_CLASSES.add(valueClass);
                return null;
            }
            ENTITY_FIELD_CACHE.put(valueClass, entityField);
        }

        try {
            Object rawEntity = entityField.get(value);
            if (rawEntity instanceof Entity entity) {
                return entity;
            }
        } catch (IllegalAccessException ignored) {
        }

        return null;
    }

    private static Field findEntityField(Class<?> valueClass) {
        for (Class<?> current = valueClass; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Entity.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static boolean hasProtectedEntityInSection(Object value) {
        if (!(value instanceof EntitySection<?> section)) {
            return false;
        }

        return section.getEntities()
            .anyMatch(entityAccess -> entityAccess instanceof Entity entity && shouldProtectEntity(entity));
    }

    /**
     * ECA自定义的ArrayList容器
     * 用于替换MC原版的ClassInstanceMultiMap.allInstances
     */
    public static class EcaArrayList<E> extends ArrayList<E> implements RemovalCallback {

        private static final long serialVersionUID = 1L;

        public EcaArrayList() {
            super();
            registerRemovalCallback(this);
        }

        public EcaArrayList(int initialCapacity) {
            super(initialCapacity);
            registerRemovalCallback(this);
        }

        public EcaArrayList(Collection<? extends E> c) {
            super(c);
            registerRemovalCallback(this);
        }

        @Override
        public void onRemove(Entity entity) {
            super.remove(entity);
        }

        private boolean isProtectedElement(Object element) {
            return shouldProtectTarget(element);
        }

        @Override
        public boolean remove(Object o) {
            if (isProtectedElement(o)) {
                return false;
            }
            return super.remove(o);
        }

        @Override
        public E remove(int index) {
            E element = super.get(index);
            if (isProtectedElement(element)) {
                return null;
            }
            return super.remove(index);
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            Collection<?> filtered = c.stream()
                .filter(item -> !isProtectedElement(item))
                .toList();
            return super.removeAll(filtered);
        }

        @Override
        public boolean removeIf(Predicate<? super E> filter) {
            Predicate<E> protectedFilter = item -> !isProtectedElement(item) && filter.test(item);
            return super.removeIf(protectedFilter);
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            return super.removeIf(item -> !isProtectedElement(item) && !c.contains(item));
        }

        @Override
        public void clear() {
            if (hasProtectedEntity(this)) {
                super.removeIf(item -> !isProtectedElement(item));
                return;
            }
            super.clear();
        }

        @Override
        public Iterator<E> iterator() {
            return new ProtectedListIteratorWrapper(super.listIterator(0));
        }

        @Override
        public ListIterator<E> listIterator() {
            return new ProtectedListIteratorWrapper(super.listIterator(0));
        }

        @Override
        public ListIterator<E> listIterator(int index) {
            return new ProtectedListIteratorWrapper(super.listIterator(index));
        }

        private class ProtectedListIteratorWrapper implements ListIterator<E> {
            private final ListIterator<E> delegate;
            private E current;

            private ProtectedListIteratorWrapper(ListIterator<E> delegate) {
                this.delegate = delegate;
            }

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public E next() {
                current = delegate.next();
                return current;
            }

            @Override
            public boolean hasPrevious() {
                return delegate.hasPrevious();
            }

            @Override
            public E previous() {
                current = delegate.previous();
                return current;
            }

            @Override
            public int nextIndex() {
                return delegate.nextIndex();
            }

            @Override
            public int previousIndex() {
                return delegate.previousIndex();
            }

            @Override
            public void remove() {
                if (isProtectedElement(current)) {
                    return;
                }
                delegate.remove();
                current = null;
            }

            @Override
            public void set(E e) {
                delegate.set(e);
            }

            @Override
            public void add(E e) {
                delegate.add(e);
                current = null;
            }
        }
    }

    /**
     * ECA自定义的HashMap容器
     * 用于替换MC原版的EntityLookup.byUuid和ClassInstanceMultiMap.byClass
     */
    @SuppressWarnings("unchecked")
    public static class EcaHashMap<K, V> extends HashMap<K, V> implements RemovalCallback {

        private static final long serialVersionUID = 1L;

        public EcaHashMap() {
            super();
            registerRemovalCallback(this);
        }

        public EcaHashMap(int initialCapacity) {
            super(initialCapacity);
            registerRemovalCallback(this);
        }

        public EcaHashMap(int initialCapacity, float loadFactor) {
            super(initialCapacity, loadFactor);
            registerRemovalCallback(this);
        }

        public EcaHashMap(Map<? extends K, ? extends V> m) {
            super(m);
            registerRemovalCallback(this);
        }

        @Override
        public void onRemove(Entity entity) {
            // EntityLookup.byUuid: UUID → Entity
            super.remove((K) entity.getUUID());
            // ClassInstanceMultiMap.byClass: Class → List，遍历每个 List 移除
            for (V value : super.values()) {
                if (value instanceof List<?> list) {
                    list.remove(entity);
                }
            }
        }

        private transient Set<K> keySetView;
        private transient Collection<V> valuesView;
        private transient Set<Map.Entry<K, V>> entrySetView;

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
                return null;
            }
            return super.remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
            V existing = super.get(key);
            if (existing != null && Objects.equals(existing, value) && shouldProtectRemoval(key)) {
                return false;
            }
            return super.remove(key, value);
        }

        @Override
        public void clear() {
            if (containsProtectedValue()) {
                super.entrySet().removeIf(entry -> !isProtectedValue(entry.getValue()));
                return;
            }
            super.clear();
        }

        @Override
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            V oldValue = super.get(key);
            if (oldValue == null) {
                return null;
            }
            V newValue = remappingFunction.apply(key, oldValue);
            if (newValue == null && isProtectedValue(oldValue)) {
                return oldValue;
            }
            return super.computeIfPresent(key, remappingFunction);
        }

        @Override
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            V oldValue = super.get(key);
            V newValue = remappingFunction.apply(key, oldValue);
            if (newValue == null && oldValue != null && isProtectedValue(oldValue)) {
                return oldValue;
            }
            return super.compute(key, remappingFunction);
        }

        @Override
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            V oldValue = super.get(key);
            if (oldValue != null) {
                V newValue = remappingFunction.apply(oldValue, value);
                if (newValue == null && isProtectedValue(oldValue)) {
                    return oldValue;
                }
            }
            return super.merge(key, value, remappingFunction);
        }

        @Override
        public Set<K> keySet() {
            if (keySetView == null) {
                keySetView = new AbstractSet<>() {
                    @Override
                    public Iterator<K> iterator() {
                        Iterator<Map.Entry<K, V>> entryIterator = EcaHashMap.super.entrySet().iterator();
                        return new Iterator<>() {
                            private Map.Entry<K, V> current;

                            @Override
                            public boolean hasNext() {
                                return entryIterator.hasNext();
                            }

                            @Override
                            public K next() {
                                current = entryIterator.next();
                                return current.getKey();
                            }

                            @Override
                            public void remove() {
                                if (current != null && isProtectedValue(current.getValue())) {
                                    return;
                                }
                                entryIterator.remove();
                                current = null;
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return EcaHashMap.this.size();
                    }

                    @Override
                    public boolean remove(Object key) {
                        return EcaHashMap.this.remove(key) != null;
                    }

                    @Override
                    public void clear() {
                        EcaHashMap.this.clear();
                    }
                };
            }
            return keySetView;
        }

        @Override
        public Collection<V> values() {
            if (valuesView == null) {
                valuesView = new AbstractCollection<>() {
                    @Override
                    public Iterator<V> iterator() {
                        Iterator<Map.Entry<K, V>> entryIterator = EcaHashMap.super.entrySet().iterator();
                        return new Iterator<>() {
                            private Map.Entry<K, V> current;

                            @Override
                            public boolean hasNext() {
                                return entryIterator.hasNext();
                            }

                            @Override
                            public V next() {
                                current = entryIterator.next();
                                return current.getValue();
                            }

                            @Override
                            public void remove() {
                                if (current != null && isProtectedValue(current.getValue())) {
                                    return;
                                }
                                entryIterator.remove();
                                current = null;
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return EcaHashMap.this.size();
                    }

                    @Override
                    public boolean remove(Object value) {
                        for (Map.Entry<K, V> entry : EcaHashMap.super.entrySet()) {
                            if (Objects.equals(entry.getValue(), value)) {
                                if (isProtectedValue(entry.getValue())) {
                                    return false;
                                }
                                return EcaHashMap.this.remove(entry.getKey(), entry.getValue());
                            }
                        }
                        return false;
                    }

                    @Override
                    public void clear() {
                        EcaHashMap.this.clear();
                    }
                };
            }
            return valuesView;
        }

        @Override
        public Set<Map.Entry<K, V>> entrySet() {
            if (entrySetView == null) {
                entrySetView = new AbstractSet<>() {
                    @Override
                    public Iterator<Map.Entry<K, V>> iterator() {
                        Iterator<Map.Entry<K, V>> delegate = EcaHashMap.super.entrySet().iterator();
                        return new Iterator<>() {
                            private Map.Entry<K, V> current;

                            @Override
                            public boolean hasNext() {
                                return delegate.hasNext();
                            }

                            @Override
                            public Map.Entry<K, V> next() {
                                current = delegate.next();
                                return current;
                            }

                            @Override
                            public void remove() {
                                if (current != null && isProtectedValue(current.getValue())) {
                                    return;
                                }
                                delegate.remove();
                                current = null;
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return EcaHashMap.this.size();
                    }

                    @Override
                    public boolean remove(Object o) {
                        if (!(o instanceof Map.Entry<?, ?> entry)) {
                            return false;
                        }
                        return EcaHashMap.this.remove(entry.getKey(), entry.getValue());
                    }

                    @Override
                    public void clear() {
                        EcaHashMap.this.clear();
                    }
                };
            }
            return entrySetView;
        }

        private boolean shouldProtectRemoval(Object key) {
            V value = super.get(key);
            return isProtectedValue(value);
        }

        private boolean isProtectedValue(Object value) {
            return shouldProtectTarget(value) || hasProtectedEntityInListValue(value);
        }

        private boolean containsProtectedValue() {
            for (V value : super.values()) {
                if (isProtectedValue(value)) {
                    return true;
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

    public static class EcaHashSet<E> extends HashSet<E> implements RemovalCallback {

        private static final long serialVersionUID = 1L;

        public EcaHashSet() {
            super();
            registerRemovalCallback(this);
        }

        public EcaHashSet(int initialCapacity) {
            super(initialCapacity);
            registerRemovalCallback(this);
        }

        public EcaHashSet(int initialCapacity, float loadFactor) {
            super(initialCapacity, loadFactor);
            registerRemovalCallback(this);
        }

        public EcaHashSet(Collection<? extends E> c) {
            super(c);
            registerRemovalCallback(this);
        }

        @Override
        public void onRemove(Entity entity) {
            // PersistentEntitySectionManager.knownUuids: Set<UUID>
            super.remove(entity.getUUID());
            // ServerLevel.navigatingMobs: Set<Mob>
            super.remove(entity);
        }

        private boolean shouldProtectUUID(Object o) {
            return o instanceof java.util.UUID uuid
                && InvulnerableEntityManager.isInvulnerable(uuid)
                && !EntityUtil.isChangingDimension(uuid);
        }

        @Override
        public boolean remove(Object o) {
            if (shouldProtectUUID(o)) {
                return false;
            }
            return super.remove(o);
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            Collection<?> filtered = c.stream()
                .filter(item -> !shouldProtectUUID(item))
                .toList();
            return super.removeAll(filtered);
        }

        @Override
        public boolean removeIf(java.util.function.Predicate<? super E> filter) {
            java.util.function.Predicate<E> protectedFilter = item ->
                !shouldProtectUUID(item) && filter.test(item);
            return super.removeIf(protectedFilter);
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            return super.removeIf(item ->
                !shouldProtectUUID(item) && !c.contains(item));
        }

        @Override
        public void clear() {
            super.removeIf(item -> !shouldProtectUUID(item));
        }

        @Override
        public Iterator<E> iterator() {
            Iterator<E> delegate = super.iterator();
            return new Iterator<>() {
                private E current;

                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public E next() {
                    current = delegate.next();
                    return current;
                }

                @Override
                public void remove() {
                    if (shouldProtectUUID(current)) {
                        return;
                    }
                    delegate.remove();
                    current = null;
                }
            };
        }
    }

    public static class EcaLong2ObjectOpenHashMap<V> extends Long2ObjectOpenHashMap<V> implements RemovalCallback {

        private static final long serialVersionUID = 1L;

        public EcaLong2ObjectOpenHashMap() {
            super();
            registerRemovalCallback(this);
        }

        public EcaLong2ObjectOpenHashMap(int expected) {
            super(expected);
            registerRemovalCallback(this);
        }

        public EcaLong2ObjectOpenHashMap(int expected, float f) {
            super(expected, f);
            registerRemovalCallback(this);
        }

        @Override
        public void onRemove(Entity entity) {
            // EntitySectionStorage.sections: Long → EntitySection，遍历所有 section 移除实体
            for (V value : super.values()) {
                if (value instanceof EntitySection section) {
                    section.remove(entity);
                }
            }
        }

        @Override
        public V remove(long key) {
            V value = super.get(key);
            if (hasProtectedEntityInSection(value)) {
                SECTION_REMOVAL_CONTEXT.set(new SectionRemovalContext(this, key));
                return null;
            }
            return super.remove(key);
        }

        @Override
        public boolean remove(long key, Object value) {
            V existing = super.get(key);
            if (existing != null && Objects.equals(existing, value) && hasProtectedEntityInSection(existing)) {
                return false;
            }
            return super.remove(key, value);
        }

        @Override
        public void clear() {
            if (super.values().stream().anyMatch(EcaContainers::hasProtectedEntityInSection)) {
                super.long2ObjectEntrySet().removeIf(entry -> !hasProtectedEntityInSection(entry.getValue()));
                return;
            }
            super.clear();
        }
    }

    public static class EcaLongAVLTreeSet extends LongAVLTreeSet {

        private static final long serialVersionUID = 1L;

        public EcaLongAVLTreeSet() {
            super();
        }

        @Override
        public boolean remove(long k) {
            SectionRemovalContext context = SECTION_REMOVAL_CONTEXT.get();
            if (context != null && context.sectionKey == k) {
                try {
                    Object section = context.sections.get(k);
                    if (hasProtectedEntityInSection(section)) {
                        return false;
                    }
                } finally {
                    SECTION_REMOVAL_CONTEXT.remove();
                }
            }

            return super.remove(k);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static class EcaConcurrentLinkedQueue<E> extends ConcurrentLinkedQueue<E> implements RemovalCallback {

        private static final long serialVersionUID = 1L;

        public EcaConcurrentLinkedQueue() {
            super();
            registerRemovalCallback(this);
        }

        public EcaConcurrentLinkedQueue(Collection<? extends E> c) {
            super();
            addAll(c);
            registerRemovalCallback(this);
        }

        @Override
        public void onRemove(Entity entity) {
            // loadingInbox 内部的 ChunkEntities.entities 已被包装为 EcaArrayList
            // EcaArrayList 自身的 onRemove 回调会处理实体移除
        }

        @Override
        public boolean add(E e) {
            return super.add((E) wrapChunkEntitiesIfNeeded(e));
        }

        @Override
        public boolean offer(E e) {
            return super.offer((E) wrapChunkEntitiesIfNeeded(e));
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            boolean modified = false;
            for (E element : c) {
                modified |= this.add(element);
            }
            return modified;
        }

        @Override
        public boolean remove(Object o) {
            if (containsProtectedEntityFromChunkEntities(o)) {
                return false;
            }
            return super.remove(o);
        }

        @Override
        public boolean removeIf(Predicate<? super E> filter) {
            return super.removeIf(item -> !containsProtectedEntityFromChunkEntities(item) && filter.test(item));
        }

        @Override
        public void clear() {
            super.removeIf(item -> !containsProtectedEntityFromChunkEntities(item));
        }

        private Object wrapChunkEntitiesIfNeeded(Object value) {
            if (!(value instanceof ChunkEntities<?> chunkEntities)) {
                return value;
            }

            List<?> entities = chunkEntities.getEntities().toList();
            if (entities instanceof EcaArrayList<?>) {
                return value;
            }

            List wrapped = new EcaArrayList<>(entities);
            return new ChunkEntities<>(chunkEntities.getPos(), wrapped);
        }

        private boolean containsProtectedEntityFromChunkEntities(Object value) {
            if (!(value instanceof ChunkEntities<?> chunkEntities)) {
                return false;
            }
            return chunkEntities.getEntities().anyMatch(EcaContainers::shouldProtectTarget);
        }
    }

    /**
     * ECA自定义的Int2ObjectOpenHashMap容器
     * 用于替换MC原版的ChunkMap.entityMap和EntityLookup.byId
     */
    public static class EcaInt2ObjectOpenHashMap<V> extends Int2ObjectOpenHashMap<V> implements RemovalCallback {

        private static final long serialVersionUID = 1L;

        public EcaInt2ObjectOpenHashMap() {
            super();
            registerRemovalCallback(this);
        }

        public EcaInt2ObjectOpenHashMap(int expected) {
            super(expected);
            registerRemovalCallback(this);
        }

        public EcaInt2ObjectOpenHashMap(int expected, float f) {
            super(expected, f);
            registerRemovalCallback(this);
        }

        @Override
        public void onRemove(Entity entity) {
            // ChunkMap.entityMap / EntityLookup.byId
            super.remove(entity.getId());
        }

        @Override
        public V remove(int key) {
            if (shouldProtectRemoval(key)) {
                return null;
            }
            return super.remove(key);
        }

        @Override
        public V remove(Object key) {
            if (key instanceof Integer intKey) {
                return remove((int) intKey);
            }
            return super.remove(key);
        }

        @Override
        public boolean remove(int key, Object value) {
            V existing = super.get(key);
            if (existing != null && Objects.equals(existing, value) && shouldProtectRemoval(key)) {
                return false;
            }
            return super.remove(key, value);
        }

        @Override
        public void clear() {
            if (hasProtectedEntity(super.values())) {
                super.int2ObjectEntrySet().removeIf(entry -> !shouldProtectTarget(entry.getValue()));
                return;
            }
            super.clear();
        }

        private boolean shouldProtectRemoval(int entityId) {
            V value = super.get(entityId);
            Entity entity = resolveEntityFromContainerValue(value);
            if (entity != null) {
                return shouldProtectEntity(entity);
            }
            return false;
        }
    }

    /**
     * ECA自定义的Int2ObjectLinkedOpenHashMap容器
     * 用于替换MC原版的EntityTickList.active和EntityTickList.passive
     */
    public static class EcaInt2ObjectLinkedOpenHashMap<V> extends Int2ObjectLinkedOpenHashMap<V> implements RemovalCallback {

        private static final long serialVersionUID = 1L;

        public EcaInt2ObjectLinkedOpenHashMap() {
            super();
            registerRemovalCallback(this);
        }

        public EcaInt2ObjectLinkedOpenHashMap(int expected) {
            super(expected);
            registerRemovalCallback(this);
        }

        public EcaInt2ObjectLinkedOpenHashMap(int expected, float f) {
            super(expected, f);
            registerRemovalCallback(this);
        }

        @Override
        public void onRemove(Entity entity) {
            // EntityTickList.active/passive, EntityLookup.byId
            super.remove(entity.getId());
        }

        @Override
        public V remove(int key) {
            if (shouldProtectRemoval(key)) {
                return null;
            }
            return super.remove(key);
        }

        @Override
        public V remove(Object key) {
            if (key instanceof Integer intKey) {
                return remove((int) intKey);
            }
            return super.remove(key);
        }

        @Override
        public boolean remove(int key, Object value) {
            V existing = super.get(key);
            if (existing != null && Objects.equals(existing, value) && shouldProtectRemoval(key)) {
                return false;
            }
            return super.remove(key, value);
        }

        @Override
        public void clear() {
            if (hasProtectedEntity(super.values())) {
                super.int2ObjectEntrySet().removeIf(entry -> !shouldProtectTarget(entry.getValue()));
                return;
            }
            super.clear();
        }

        private boolean shouldProtectRemoval(int entityId) {
            V value = super.get(entityId);
            Entity entity = resolveEntityFromContainerValue(value);
            if (entity != null) {
                return shouldProtectEntity(entity);
            }
            return false;
        }
    }
}
