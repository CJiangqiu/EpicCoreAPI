package net.eca.pro.ingot;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.eca.agent.AgentLogWriter;
import net.eca.coremod.EcaContainers;
import net.eca.util.reflect.ObfuscationMapping;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class ProContainerHooks {
    private static final Map<String, FieldSlot> FIELD_SLOTS = new ConcurrentHashMap<>();
    private static final Map<Object, Integer> NORMALIZED_LEVELS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Object UNSAFE;
    private static final Method OBJECT_FIELD_OFFSET;
    private static final Method GET_OBJECT;
    private static final Method PUT_OBJECT;

    static {
        Object unsafe = null;
        Method objectFieldOffset = null;
        Method getObject = null;
        Method putObject = null;
        try {
            Class<?> memoryUtil = Class.forName("org.lwjgl.system.MemoryUtil");
            Field field = memoryUtil.getDeclaredField("UNSAFE");
            field.setAccessible(true);
            unsafe = field.get(null);
            Class<?> unsafeType = unsafe.getClass();
            objectFieldOffset = unsafeType.getMethod("objectFieldOffset", Field.class);
            getObject = unsafeType.getMethod("getObject", Object.class, long.class);
            putObject = unsafeType.getMethod("putObject", Object.class, long.class, Object.class);
        } catch (Throwable failure) {
            AgentLogWriter.info("[ProIngot] Container migration channel unavailable: "
                    + rootMessage(failure));
        }
        UNSAFE = unsafe;
        OBJECT_FIELD_OFFSET = objectFieldOffset;
        GET_OBJECT = getObject;
        PUT_OBJECT = putObject;
    }

    private ProContainerHooks() {
    }

    public static Object normalizeFieldValue(Object value, String kind) {
        if (value == null) return null;
        return switch (kind) {
            case "int_linked" -> normalizeIntLinked((Int2ObjectMap<?>) value);
            case "int_open" -> normalizeIntOpen((Int2ObjectMap<?>) value);
            case "map" -> normalizeMap((Map<?, ?>) value);
            case "list" -> normalizeList((List<?>) value);
            case "set" -> normalizeSet((Set<?>) value);
            case "queue" -> normalizeQueue((Queue<?>) value);
            case "long_map" -> normalizeLongMap((Long2ObjectMap<?>) value);
            case "long_function" -> value instanceof Long2ObjectMap<?> map
                    ? normalizeLongMap(map) : value;
            case "long_set" -> normalizeLongSet((LongSet) value);
            default -> value;
        };
    }

    public static void normalizeServerGraph(Object level) {
        if (level == null || UNSAFE == null) return;
        int invocation;
        synchronized (NORMALIZED_LEVELS) {
            invocation = NORMALIZED_LEVELS.getOrDefault(level, 0) + 1;
            NORMALIZED_LEVELS.put(level, invocation);
        }
        if (invocation % 20 != 1) return;

        replace(level, "ServerLevel.players", value -> normalizeList((List<?>) value));
        replace(level, "ServerLevel.navigatingMobs", value -> normalizeSet((Set<?>) value));

        Object tickList = read(level, "ServerLevel.entityTickList");
        replace(tickList, "EntityTickList.active", value -> normalizeIntLinked((Int2ObjectMap<?>) value));
        replace(tickList, "EntityTickList.passive", value -> normalizeIntLinked((Int2ObjectMap<?>) value));

        Object manager = read(level, "ServerLevel.entityManager");
        replace(manager, "PersistentEntitySectionManager.knownUuids",
                value -> normalizeSet((Set<?>) value));
        replace(manager, "PersistentEntitySectionManager.loadingInbox",
                value -> normalizeQueue((Queue<?>) value));
        replace(manager, "PersistentEntitySectionManager.chunkVisibility",
                value -> normalizeLongMap((Long2ObjectMap<?>) value));
        replace(manager, "PersistentEntitySectionManager.chunksToUnload",
                value -> normalizeLongSet((LongSet) value));
        replace(manager, "PersistentEntitySectionManager.chunkLoadStatuses",
                value -> normalizeLongMap((Long2ObjectMap<?>) value));

        Object lookup = read(manager, "PersistentEntitySectionManager.visibleEntityStorage");
        replace(lookup, "EntityLookup.byId", value -> normalizeIntLinked((Int2ObjectMap<?>) value));
        replace(lookup, "EntityLookup.byUuid", value -> normalizeMap((Map<?, ?>) value));

        Object storage = read(manager, "PersistentEntitySectionManager.sectionStorage");
        replace(storage, "EntitySectionStorage.sections", value -> normalizeLongMap((Long2ObjectMap<?>) value));
        normalizeSections(storage);

        Object chunkSource = read(level, "ServerLevel.chunkSource");
        Object chunkMap = read(chunkSource, "ServerChunkCache.chunkMap");
        replace(chunkMap, "ChunkMap.entityMap", value -> normalizeIntOpen((Int2ObjectMap<?>) value));
        replace(chunkMap, "ChunkMap.entitiesInLevel", value -> normalizeLongSet((LongSet) value));
    }

    private static Object normalizeIntLinked(Int2ObjectMap<?> value) {
        if (value instanceof EcaContainers.EcaInt2ObjectLinkedOpenHashMap<?>) return value;
        EcaContainers.EcaInt2ObjectLinkedOpenHashMap<Object> result =
                new EcaContainers.EcaInt2ObjectLinkedOpenHashMap<>(value.size());
        result.putAll((Map<? extends Integer, ?>) value);
        return result;
    }

    private static Object normalizeIntOpen(Int2ObjectMap<?> value) {
        if (value instanceof EcaContainers.EcaInt2ObjectOpenHashMap<?>) return value;
        EcaContainers.EcaInt2ObjectOpenHashMap<Object> result =
                new EcaContainers.EcaInt2ObjectOpenHashMap<>(value.size());
        result.putAll((Map<? extends Integer, ?>) value);
        return result;
    }

    private static Object normalizeMap(Map<?, ?> value) {
        if (value instanceof EcaContainers.EcaHashMap<?, ?>) return value;
        return new EcaContainers.EcaHashMap<>(value);
    }

    private static Object normalizeList(List<?> value) {
        if (value instanceof EcaContainers.EcaArrayList<?>) return value;
        return new EcaContainers.EcaArrayList<>(value);
    }

    private static Object normalizeSet(Set<?> value) {
        if (value instanceof EcaContainers.EcaHashSet<?>) return value;
        return new EcaContainers.EcaHashSet<>(value);
    }

    private static Object normalizeQueue(Queue<?> value) {
        if (value instanceof EcaContainers.EcaConcurrentLinkedQueue<?>) return value;
        return new EcaContainers.EcaConcurrentLinkedQueue<>(value);
    }

    private static Object normalizeLongMap(Long2ObjectMap<?> value) {
        if (value instanceof EcaContainers.EcaLong2ObjectOpenHashMap<?>) return value;
        EcaContainers.EcaLong2ObjectOpenHashMap<Object> result =
                new EcaContainers.EcaLong2ObjectOpenHashMap<>(value.size());
        result.putAll((Map<? extends Long, ?>) value);
        return result;
    }

    private static Object normalizeLongSet(LongSet value) {
        if (value.getClass() == LongOpenHashSet.class) return value;
        return new LongOpenHashSet(value);
    }

    private static void normalizeSections(Object storage) {
        Object rawSections = read(storage, "EntitySectionStorage.sections");
        if (!(rawSections instanceof Long2ObjectMap<?> sections)) return;
        for (Object section : new ArrayList<>(sections.values())) {
            Object multiMap = read(section, "EntitySection.storage");
            replace(multiMap, "ClassInstanceMultiMap.allInstances",
                    value -> normalizeList((List<?>) value));
            replace(multiMap, "ClassInstanceMultiMap.byClass", value -> {
                Map<?, ?> source = (Map<?, ?>) value;
                Map<Object, Object> normalized = new HashMap<>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    Object entryValue = entry.getValue();
                    normalized.put(entry.getKey(), entryValue instanceof List<?> list
                            ? normalizeList(list) : entryValue);
                }
                return new EcaContainers.EcaHashMap<>(normalized);
            });
        }
    }

    private static void replace(Object owner, String mappingKey, Function<Object, Object> normalizer) {
        if (owner == null) return;
        try {
            FieldSlot slot = fieldSlot(owner.getClass(), mappingKey);
            if (slot == null) return;
            Object current = GET_OBJECT.invoke(UNSAFE, owner, slot.offset());
            Object normalized = normalizer.apply(current);
            if (normalized != current) PUT_OBJECT.invoke(UNSAFE, owner, slot.offset(), normalized);
        } catch (Throwable failure) {
            logFailure(mappingKey, failure);
        }
    }

    private static Object read(Object owner, String mappingKey) {
        if (owner == null) return null;
        try {
            FieldSlot slot = fieldSlot(owner.getClass(), mappingKey);
            return slot == null ? null : GET_OBJECT.invoke(UNSAFE, owner, slot.offset());
        } catch (Throwable failure) {
            logFailure(mappingKey, failure);
            return null;
        }
    }

    private static FieldSlot fieldSlot(Class<?> ownerType, String mappingKey) throws Exception {
        String cacheKey = ownerType.getName() + '#' + mappingKey;
        FieldSlot cached = FIELD_SLOTS.get(cacheKey);
        if (cached != null) return cached;
        String fieldName = ObfuscationMapping.getFieldMapping(mappingKey);
        Field field = findField(ownerType, fieldName, logicalName(mappingKey));
        if (field == null) return null;
        field.setAccessible(true);
        long offset = (long) OBJECT_FIELD_OFFSET.invoke(UNSAFE, field);
        FieldSlot slot = new FieldSlot(offset);
        FIELD_SLOTS.put(cacheKey, slot);
        return slot;
    }

    private static Field findField(Class<?> ownerType, String mappedName, String logicalName) {
        try {
            return ObfuscationReflectionHelper.findField(ownerType, mappedName);
        } catch (Throwable ignored) {
            for (Class<?> current = ownerType; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.getName().equals(mappedName) || field.getName().equals(logicalName)) return field;
                }
            }
            return null;
        }
    }

    private static String logicalName(String mappingKey) {
        int separator = mappingKey.lastIndexOf('.');
        return separator < 0 ? mappingKey : mappingKey.substring(separator + 1);
    }

    private static void logFailure(String mappingKey, Throwable failure) {
        AgentLogWriter.info("[ProIngot] Container migration failed for " + mappingKey + ": "
                + rootMessage(failure));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private record FieldSlot(long offset) {
    }
}
