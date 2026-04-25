package net.eca.util.health;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.eca.util.EcaLogger;
import net.eca.util.health.HealthAnalyzer.AnalysisResult;
import net.eca.util.health.HealthAnalyzer.ArrayIndexLeaf;
import net.eca.util.health.HealthAnalyzer.ChainedFieldLeaf;
import net.eca.util.health.HealthAnalyzer.Const;
import net.eca.util.health.HealthAnalyzer.EntityDataLeaf;
import net.eca.util.health.HealthAnalyzer.Expr;
import net.eca.util.health.HealthAnalyzer.FieldLeaf;
import net.eca.util.health.HealthAnalyzer.BinaryOp;
import net.eca.util.health.HealthAnalyzer.Choice;
import net.eca.util.health.HealthAnalyzer.InvertibleCall;
import net.eca.util.health.HealthAnalyzer.MapByStaticFieldLeaf;
import net.eca.util.health.HealthAnalyzer.MapEntryLeaf;
import net.eca.util.health.HealthAnalyzer.UnaryOp;
import net.eca.util.health.HealthAnalyzer.UnresolvedCall;
import net.eca.util.health.HealthAnalyzer.WriteTarget;
import org.objectweb.asm.Type;
import net.eca.util.reflect.UnsafeUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Phase 3 写入管理器：把 HealthAnalyzer 输出的表达式叶子解析为运行时 Sink，执行反向求解 + 写入。
 * 三层写入：1) 对每个叶子 solve 反推应写值直接写 Sink；2) MapEntry 类 Sink 同时扫 owner class
 * 所有静态 Map 做兄弟联写，应对影子表回滚设计；3) 前两层全失败时扫 owner class 找签名匹配的 setter 兜底。
 * Map 写入绕过 Map.put（常见 mixin 拦截点），走 Entry.setValue / Unsafe 写字段偏移。
 * WeakHashMap 有多 entry 同 key 的坑（Entity.hashCode 来自 mutable 的 id），必须遍历
 * entrySet 写所有匹配 entry，否则 get 走另一个 bucket 读到 stale 值。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class HealthAnalyzerManager {

    private HealthAnalyzerManager() {}

    private static final Map<Class<?>, Resolved> CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Long> ENTRY_VALUE_OFFSET_CACHE = new ConcurrentHashMap<>();

    //解析后的写入方案（每个 WriteTarget 对应一个 Sink + sister-setter 兜底列表）
    private record Resolved(AnalysisResult analysis, Map<Expr, Sink> sinks, List<Sink> sisterSinks) {}

    // ==================== 对外入口 ====================

    //hook 调用：首次触发分析
    public static void onGetHealthCalled(LivingEntity entity, String className) {
        if (entity == null) return;
        Class<?> c = entity.getClass();
        if (!CACHE.containsKey(c)) analyzeAndCache(c);
    }

    //Phase 3 主入口：尝试写入全部可写 sink，任意一个写成功且 verify 通过即 true
    public static boolean writeAll(LivingEntity entity, float expected) {
        if (entity == null) return false;
        Resolved initial = CACHE.get(entity.getClass());
        if (initial == null) {
            analyzeAndCache(entity.getClass());
            initial = CACHE.get(entity.getClass());
            if (initial == null) return false;
        }
        final Resolved r = initial;
        if (r.analysis.isEmpty()) return false;

        boolean anyWrote = false;
        for (WriteTarget wt : r.analysis.writeTargets) {
            Sink sink = r.sinks.get(wt.sink());
            if (sink == null) continue;
            Number toWrite = HealthAnalyzer.solve(wt.fullExpr(), wt.sink(), Float.valueOf(expected),
                (expr, unused) -> readExprValue(entity, expr, r.sinks));
            if (toWrite == null) continue;
            if (sink.write(entity, toWrite)) anyWrote = true;
        }

        // sister-setter 兜底：常规 sink 全失败时，调用 mod 自带的对偶 setter
        if (!anyWrote && !r.sisterSinks.isEmpty()) {
            for (Sink ss : r.sisterSinks) {
                if (ss.write(entity, Float.valueOf(expected))) { anyWrote = true; break; }
            }
        }
        return anyWrote;
    }

    private static Number readExprValue(LivingEntity entity, Expr expr, Map<Expr, Sink> sinks) {
        Sink s = sinks.get(expr);
        if (s != null) return s.read(entity);
        // 未注册为 sink 的中间表达式（如 ArrayIndexLeaf 内部的 indexExpr 是 ChainedFieldLeaf），按导航算
        return resolveExprNumber(entity, expr, sinks);
    }

    // ==================== 分析 + 解析 sink ====================

    private static void analyzeAndCache(Class<?> entityClass) {
        try {
            AnalysisResult ar = HealthAnalyzer.analyze(entityClass);
            Map<Expr, Sink> sinks = new HashMap<>();
            for (WriteTarget wt : ar.writeTargets) {
                Sink s = resolveSink(wt.sink());
                if (s != null) sinks.put(wt.sink(), s);
            }
            // sister-setter 兜底：扫表达式里所有数值返回的 UnresolvedCall，找匹配 setter
            List<Sink> sisterSinks = new ArrayList<>();
            collectSisterSinks(ar.returnExpr, sisterSinks, new HashSet<>());
            CACHE.put(entityClass, new Resolved(ar, sinks, sisterSinks));
        } catch (Throwable t) {
            EcaLogger.info("[HealthAnalyzerManager] analyze {} failed: {}", entityClass.getName(), t.toString());
            CACHE.put(entityClass, new Resolved(AnalysisResult.EMPTY, Map.of(), List.of()));
        }
    }

    private static Sink resolveSink(Expr leaf) {
        try {
            if (leaf instanceof FieldLeaf fl) return resolveFieldSink(fl);
            if (leaf instanceof EntityDataLeaf el) return resolveEntityDataSink(el);
            if (leaf instanceof MapEntryLeaf ml) return resolveMapEntrySink(ml);
            if (leaf instanceof MapByStaticFieldLeaf mbf) return resolveMapByStaticFieldSink(mbf);
            if (leaf instanceof ChainedFieldLeaf cf) return resolveChainedFieldSink(cf);
            if (leaf instanceof ArrayIndexLeaf ai) return resolveArrayIndexSink(ai);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== Sink 接口 ====================

    private interface Sink {
        Number read(LivingEntity entity);
        boolean write(LivingEntity entity, Number value);
    }

    // ---- FieldSink ----

    private static Sink resolveFieldSink(FieldLeaf fl) throws Exception {
        if (fl.path().isEmpty()) return null;
        VarHandle[] handles = new VarHandle[fl.path().size()];
        Class<?> lastFieldType = null;
        for (int i = 0; i < fl.path().size(); i++) {
            FieldLeaf.FieldStep step = fl.path().get(i);
            Class<?> owner = Class.forName(step.ownerInternal().replace('/', '.'), false,
                Thread.currentThread().getContextClassLoader());
            Class<?> fieldType = descriptorToClass(step.desc());
            if (fieldType == null) return null;
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            handles[i] = lookup.findVarHandle(owner, step.name(), fieldType);
            lastFieldType = fieldType;
        }
        final VarHandle[] navHandles = handles;
        final Class<?> finalType = lastFieldType;

        return new Sink() {
            @Override public Number read(LivingEntity entity) {
                try {
                    Object current = entity;
                    for (int i = 0; i < navHandles.length - 1; i++) {
                        current = navHandles[i].get(current);
                        if (current == null) return null;
                    }
                    Object v = navHandles[navHandles.length - 1].get(current);
                    return v instanceof Number n ? n : null;
                } catch (Throwable t) { return null; }
            }

            @Override public boolean write(LivingEntity entity, Number value) {
                try {
                    Object current = entity;
                    for (int i = 0; i < navHandles.length - 1; i++) {
                        current = navHandles[i].get(current);
                        if (current == null) return false;
                    }
                    VarHandle last = navHandles[navHandles.length - 1];
                    if (finalType == float.class) last.set(current, value.floatValue());
                    else if (finalType == double.class) last.set(current, value.doubleValue());
                    else if (finalType == int.class) last.set(current, value.intValue());
                    else if (finalType == long.class) last.set(current, value.longValue());
                    else if (finalType == short.class) last.set(current, value.shortValue());
                    else if (finalType == byte.class) last.set(current, value.byteValue());
                    else return false;
                    return true;
                } catch (Throwable t) { return false; }
            }
        };
    }

    // ---- EntityDataSink ----

    private static Sink resolveEntityDataSink(EntityDataLeaf el) throws Exception {
        Class<?> ownerClass = Class.forName(el.accessorOwnerInternal().replace('/', '.'), false,
            Thread.currentThread().getContextClassLoader());
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ownerClass, MethodHandles.lookup());
        VarHandle accessorHandle = lookup.findStaticVarHandle(ownerClass, el.accessorName(), EntityDataAccessor.class);

        return new Sink() {
            // 直读 itemsById 与 write 对称，避免被 mixin 拦截 SynchedEntityData.get 后拿到加工值导致 evaluate 失真
            @Override public Number read(LivingEntity entity) {
                try {
                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) accessorHandle.get();
                    if (accessor == null) return null;
                    SynchedEntityData ed = entity.getEntityData();
                    SynchedEntityData.DataItem item = getDataItem(ed, accessor.getId());
                    if (item == null) return null;
                    Object v = item.value;
                    return v instanceof Number n ? n : null;
                } catch (Throwable t) { return null; }
            }

            @Override public boolean write(LivingEntity entity, Number value) {
                try {
                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) accessorHandle.get();
                    if (accessor == null) return false;
                    SynchedEntityData ed = entity.getEntityData();
                    SynchedEntityData.DataItem item = getDataItem(ed, accessor.getId());
                    if (item == null) return false;
                    item.value = coerce(item.value, value.floatValue());
                    item.dirty = true;
                    ed.isDirty = true;
                    entity.onSyncedDataUpdated(accessor);
                    return true;
                } catch (Throwable t) { return false; }
            }
        };
    }

    @SuppressWarnings("rawtypes")
    private static SynchedEntityData.DataItem getDataItem(SynchedEntityData ed, int id) {
        try {
            Int2ObjectMap<?> map = (Int2ObjectMap<?>) ed.itemsById;
            return (SynchedEntityData.DataItem) map.get(id);
        } catch (Throwable t) { return null; }
    }

    private static Object coerce(Object currentValue, float target) {
        if (currentValue instanceof Double) return (double) target;
        if (currentValue instanceof Integer) return (int) target;
        if (currentValue instanceof Long) return (long) target;
        return target;
    }

    // ---- MapEntrySink ----

    private static Sink resolveMapEntrySink(MapEntryLeaf ml) throws Exception {
        Class<?> ownerClass = Class.forName(ml.containerOwnerInternal().replace('/', '.'), false,
            Thread.currentThread().getContextClassLoader());
        Method getter = null;
        for (Method m : ownerClass.getDeclaredMethods()) {
            if (m.getName().equals(ml.containerGetterName()) && m.getParameterCount() == 0) {
                getter = m;
                break;
            }
        }
        if (getter == null) return null;
        getter.setAccessible(true);
        final Method finalGetter = getter;
        final MapEntryLeaf.KeyKind keyKind = ml.keyKind();

        // 同 owner class 的所有静态 Map 字段做兄弟候选 —— 通用应对 N-map shadow 反劫持设计
        // 写入期靠 findMatchingKey 命中过滤，不含 entity 为 key 的兄弟自动跳过，零误伤
        final List<Field> siblingMapFields = new ArrayList<>();
        for (Field f : ownerClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!Map.class.isAssignableFrom(f.getType())) continue;
            try { f.setAccessible(true); } catch (Throwable ignored) { continue; }
            siblingMapFields.add(f);
        }

        return new Sink() {
            @Override public Number read(LivingEntity entity) {
                try {
                    Object container = finalGetter.invoke(null);
                    if (!(container instanceof Map<?, ?> map)) return null;
                    Object matched = findMatchingKey(map, entity, keyKind);
                    if (matched == null) return null;
                    Object v = map.get(matched);
                    return v instanceof Number n ? n : null;
                } catch (Throwable t) { return null; }
            }

            @Override public boolean write(LivingEntity entity, Number value) {
                boolean any = false;
                Set<Object> writtenInstances = Collections.newSetFromMap(new IdentityHashMap<>());

                // 主表（getter 返回的）
                try {
                    Object container = finalGetter.invoke(null);
                    if (container instanceof Map<?, ?> map && writtenInstances.add(map)) {
                        Object matched = findMatchingKey(map, entity, keyKind);
                        if (matched != null && unsafeModifyMapEntry(map, matched, value)) any = true;
                    }
                } catch (Throwable ignored) {}

                // 兄弟表（同类静态 Map 字段，按 identity 去重，不含 entity 为 key 自动跳过）
                for (Field f : siblingMapFields) {
                    try {
                        Object obj = f.get(null);
                        if (!(obj instanceof Map<?, ?> map)) continue;
                        if (!writtenInstances.add(map)) continue;
                        Object matched = findMatchingKey(map, entity, keyKind);
                        if (matched == null) continue;
                        if (unsafeModifyMapEntry(map, matched, value)) any = true;
                    } catch (Throwable ignored) {}
                }
                return any;
            }
        };
    }

    private static Object findMatchingKey(Map<?, ?> map, LivingEntity entity, MapEntryLeaf.KeyKind kind) {
        Object primary = switch (kind) {
            case ENTITY -> entity;
            case ENTITY_ID -> entity.getId();
            case ENTITY_UUID -> entity.getUUID();
            case UNKNOWN -> entity;
        };
        if (primary != null && map.containsKey(primary)) return primary;
        Object[] fallback = {entity, entity.getUUID(), entity.getId()};
        for (Object k : fallback) if (k != null && map.containsKey(k)) return k;
        return null;
    }

    private static boolean unsafeModifyMapEntry(Map<?, ?> map, Object targetKey, Number newValue) {
        // 修复 WeakHashMap 多 entry 同 key 问题：Entity.hashCode 来自 entity.id，
        // 如果 mod 在 id 分配前后多次 putIfAbsent，同一 entity 会落到不同 bucket，产生多个 entry。
        // 必须写到所有匹配的 entry，不能只写第一个，否则 map.get 走另一个 bucket 会读到 stale 值。
        int written = 0;
        try {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object ek = entry.getKey();
                if (ek != targetKey && (targetKey == null || !targetKey.equals(ek))) continue;
                Object cur = entry.getValue();
                if (cur == null) continue;
                Object boxed;
                if (cur instanceof Double) boxed = Double.valueOf(newValue.doubleValue());
                else if (cur instanceof Integer) boxed = Integer.valueOf(newValue.intValue());
                else if (cur instanceof Long) boxed = Long.valueOf(newValue.longValue());
                else if (cur instanceof Short) boxed = Short.valueOf(newValue.shortValue());
                else if (cur instanceof Byte) boxed = Byte.valueOf(newValue.byteValue());
                else boxed = Float.valueOf(newValue.floatValue());

                boolean wrote = false;
                // 1. setValue 直接字段赋值（绕过 mutation 拦截）
                try {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Map.Entry rawEntry = entry;
                    rawEntry.setValue(boxed);
                    if (boxed.equals(entry.getValue())) wrote = true;
                } catch (Throwable ignored) {}

                // 2. Unsafe 兜底
                if (!wrote) {
                    long offset = getEntryValueOffset(entry);
                    if (offset != -1) {
                        UnsafeUtil.lwjglPutObject(entry, offset, boxed);
                        if (boxed.equals(entry.getValue())) wrote = true;
                    }
                }
                if (wrote) written++;
            }
        } catch (Throwable ignored) {}
        return written > 0;
    }

    private static long getEntryValueOffset(Object entry) {
        Class<?> ec = entry.getClass();
        Long cached = ENTRY_VALUE_OFFSET_CACHE.get(ec);
        if (cached != null) return cached;
        Class<?> cls = ec;
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                String n = f.getName();
                if (n.equals("value") || n.equals("val")) {
                    long off = UnsafeUtil.lwjglObjectFieldOffset(f);
                    if (off != -1) {
                        ENTRY_VALUE_OFFSET_CACHE.put(ec, off);
                        return off;
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return -1;
    }

    // ==================== 复合叶子 Sink ====================

    // ---- MapByStaticFieldSink ----
    // 容器是静态字段（getstatic 直接拿到），不是静态方法返回值。同 owner class 的所有静态 Map 字段做兄弟候选
    private static Sink resolveMapByStaticFieldSink(MapByStaticFieldLeaf mbf) throws Exception {
        Class<?> ownerClass = Class.forName(mbf.fieldOwnerInternal().replace('/', '.'), false,
            Thread.currentThread().getContextClassLoader());
        Field primaryField = null;
        try {
            primaryField = ownerClass.getDeclaredField(mbf.fieldName());
            primaryField.setAccessible(true);
        } catch (NoSuchFieldException e) { return null; }
        final Field finalPrimary = primaryField;
        final MapEntryLeaf.KeyKind keyKind = mbf.keyKind();

        // 兄弟静态 Map 字段（含主表，identity 去重）
        final List<Field> siblingMapFields = new ArrayList<>();
        for (Field f : ownerClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!Map.class.isAssignableFrom(f.getType())) continue;
            try { f.setAccessible(true); } catch (Throwable ignored) { continue; }
            siblingMapFields.add(f);
        }

        return new Sink() {
            @Override public Number read(LivingEntity entity) {
                try {
                    Object container = finalPrimary.get(null);
                    if (!(container instanceof Map<?, ?> map)) return null;
                    Object matched = findMatchingKey(map, entity, keyKind);
                    if (matched == null) return null;
                    Object v = map.get(matched);
                    return v instanceof Number n ? n : null;
                } catch (Throwable t) { return null; }
            }

            @Override public boolean write(LivingEntity entity, Number value) {
                boolean any = false;
                Set<Object> writtenInstances = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Field f : siblingMapFields) {
                    try {
                        Object obj = f.get(null);
                        if (!(obj instanceof Map<?, ?> map)) continue;
                        if (!writtenInstances.add(map)) continue;
                        Object matched = findMatchingKey(map, entity, keyKind);
                        if (matched == null) continue;
                        if (unsafeModifyMapEntry(map, matched, value)) any = true;
                    } catch (Throwable ignored) {}
                }
                return any;
            }
        };
    }

    // ---- ChainedFieldSink ----
    // 在非 entity 受体上做字段链：root 计算出受体对象，然后链式 GETFIELD
    private static Sink resolveChainedFieldSink(ChainedFieldLeaf cf) {
        if (cf.path().isEmpty()) return null;
        return new Sink() {
            @Override public Number read(LivingEntity entity) {
                try {
                    Object cur = resolveExprObject(entity, cf.root(), Map.of());
                    if (cur == null) return null;
                    for (FieldLeaf.FieldStep step : cf.path()) {
                        cur = readField(cur, step);
                        if (cur == null) return null;
                    }
                    return cur instanceof Number n ? n : null;
                } catch (Throwable t) { return null; }
            }

            @Override public boolean write(LivingEntity entity, Number value) {
                try {
                    Object cur = resolveExprObject(entity, cf.root(), Map.of());
                    if (cur == null) return false;
                    int last = cf.path().size() - 1;
                    for (int i = 0; i < last; i++) {
                        cur = readField(cur, cf.path().get(i));
                        if (cur == null) return false;
                    }
                    return writeField(cur, cf.path().get(last), value);
                } catch (Throwable t) { return false; }
            }
        };
    }

    // ---- ArrayIndexSink ----
    // 数组元素：arrayExpr[indexExpr]。indexExpr 必须能 evaluate 到 Number
    private static Sink resolveArrayIndexSink(ArrayIndexLeaf ai) {
        return new Sink() {
            @Override public Number read(LivingEntity entity) {
                try {
                    Object arr = resolveExprObject(entity, ai.arrayExpr(), Map.of());
                    if (arr == null) return null;
                    Number idx = resolveExprNumber(entity, ai.indexExpr(), Map.of());
                    if (idx == null) return null;
                    Object v = java.lang.reflect.Array.get(arr, idx.intValue());
                    return v instanceof Number n ? n : null;
                } catch (Throwable t) { return null; }
            }

            @Override public boolean write(LivingEntity entity, Number value) {
                try {
                    Object arr = resolveExprObject(entity, ai.arrayExpr(), Map.of());
                    if (arr == null) return false;
                    Number idx = resolveExprNumber(entity, ai.indexExpr(), Map.of());
                    if (idx == null) return false;
                    int i = idx.intValue();
                    Class<?> ct = arr.getClass().getComponentType();
                    if (ct == long.class) java.lang.reflect.Array.setLong(arr, i, value.longValue());
                    else if (ct == int.class) java.lang.reflect.Array.setInt(arr, i, value.intValue());
                    else if (ct == float.class) java.lang.reflect.Array.setFloat(arr, i, value.floatValue());
                    else if (ct == double.class) java.lang.reflect.Array.setDouble(arr, i, value.doubleValue());
                    else if (ct == short.class) java.lang.reflect.Array.setShort(arr, i, value.shortValue());
                    else if (ct == byte.class) java.lang.reflect.Array.setByte(arr, i, value.byteValue());
                    else return false;
                    return true;
                } catch (Throwable t) { return false; }
            }
        };
    }

    // ==================== 表达式求值导航 ====================

    //把任意 Expr 解析为运行时 Object（数组、容器、字段对象等）。用于 ArrayIndexLeaf / ChainedFieldLeaf 的 read/write 链
    private static Object resolveExprObject(LivingEntity entity, Expr e, Map<Expr, Sink> sinks) {
        if (e == null) return null;
        if (e instanceof Const c) return Float.valueOf(c.value());
        // markers：分析期占位符，运行时可解析
        if (e instanceof HealthAnalyzer.EntityParamMarker) return entity;
        if (e instanceof HealthAnalyzer.StaticFieldMarker sfm) {
            try {
                Class<?> owner = Class.forName(sfm.ownerInternal().replace('/', '.'), false,
                    Thread.currentThread().getContextClassLoader());
                Field f = owner.getDeclaredField(sfm.name());
                f.setAccessible(true);
                return f.get(null);
            } catch (Throwable t) { return null; }
        }
        if (e instanceof FieldLeaf fl) {
            Object cur = entity;
            for (FieldLeaf.FieldStep step : fl.path()) {
                cur = readField(cur, step);
                if (cur == null) return null;
            }
            return cur;
        }
        if (e instanceof ChainedFieldLeaf cf) {
            Object cur = resolveExprObject(entity, cf.root(), sinks);
            if (cur == null) return null;
            for (FieldLeaf.FieldStep step : cf.path()) {
                cur = readField(cur, step);
                if (cur == null) return null;
            }
            return cur;
        }
        if (e instanceof MapByStaticFieldLeaf mbf) {
            try {
                Class<?> owner = Class.forName(mbf.fieldOwnerInternal().replace('/', '.'), false,
                    Thread.currentThread().getContextClassLoader());
                Field f = owner.getDeclaredField(mbf.fieldName());
                f.setAccessible(true);
                Object obj = f.get(null);
                if (!(obj instanceof Map<?, ?> map)) return null;
                Object key = findMatchingKey(map, entity, mbf.keyKind());
                return key == null ? null : map.get(key);
            } catch (Throwable t) { return null; }
        }
        if (e instanceof MapEntryLeaf me) {
            try {
                Class<?> owner = Class.forName(me.containerOwnerInternal().replace('/', '.'), false,
                    Thread.currentThread().getContextClassLoader());
                Method getter = null;
                for (Method m : owner.getDeclaredMethods()) {
                    if (m.getName().equals(me.containerGetterName()) && m.getParameterCount() == 0) {
                        getter = m;
                        break;
                    }
                }
                if (getter == null) return null;
                getter.setAccessible(true);
                Object obj = getter.invoke(null);
                if (!(obj instanceof Map<?, ?> map)) return null;
                Object key = findMatchingKey(map, entity, me.keyKind());
                return key == null ? null : map.get(key);
            } catch (Throwable t) { return null; }
        }
        if (e instanceof ArrayIndexLeaf ai) {
            try {
                Object arr = resolveExprObject(entity, ai.arrayExpr(), sinks);
                if (arr == null) return null;
                Number idx = resolveExprNumber(entity, ai.indexExpr(), sinks);
                if (idx == null) return null;
                return java.lang.reflect.Array.get(arr, idx.intValue());
            } catch (Throwable t) { return null; }
        }
        if (e instanceof EntityDataLeaf el) {
            try {
                Class<?> owner = Class.forName(el.accessorOwnerInternal().replace('/', '.'), false,
                    Thread.currentThread().getContextClassLoader());
                Field f = owner.getDeclaredField(el.accessorName());
                f.setAccessible(true);
                EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) f.get(null);
                if (accessor == null) return null;
                SynchedEntityData ed = entity.getEntityData();
                SynchedEntityData.DataItem item = getDataItem(ed, accessor.getId());
                return item == null ? null : item.value;
            } catch (Throwable t) { return null; }
        }
        if (e instanceof UnresolvedCall uc) {
            // 反射调用：用于 args 重放（如 Enum.ordinal() / entity.getId() 等）
            return invokeUnresolvedCall(entity, uc, sinks);
        }
        return null;
    }

    private static Object invokeUnresolvedCall(LivingEntity entity, UnresolvedCall uc, Map<Expr, Sink> sinks) {
        try {
            Class<?> owner = Class.forName(uc.owner().replace('/', '.'), false,
                Thread.currentThread().getContextClassLoader());
            Type[] argTypes = Type.getArgumentTypes(uc.desc());
            // INVOKEVIRTUAL/INVOKEINTERFACE/INVOKESPECIAL：args[0] 是 receiver
            // INVOKESTATIC：args 直接对应 desc 参数
            boolean hasReceiver = uc.args().size() > argTypes.length;
            int argStart = hasReceiver ? 1 : 0;
            Object receiver = null;
            if (hasReceiver) {
                receiver = resolveExprObject(entity, uc.args().get(0), sinks);
                if (receiver == null) return null;
            }
            Class<?>[] paramTypes = new Class<?>[argTypes.length];
            Object[] paramValues = new Object[argTypes.length];
            for (int i = 0; i < argTypes.length; i++) {
                Class<?> pt = asmTypeToClass(argTypes[i]);
                if (pt == null) return null;
                paramTypes[i] = pt;
                Object v = resolveExprObject(entity, uc.args().get(argStart + i), sinks);
                paramValues[i] = coerceArg(v, pt);
            }
            Method method = findMatchingMethod(owner, uc.name(), paramTypes);
            if (method == null) return null;
            method.setAccessible(true);
            return method.invoke(receiver, paramValues);
        } catch (Throwable t) { return null; }
    }

    private static Method findMatchingMethod(Class<?> owner, String name, Class<?>[] paramTypes) {
        for (Class<?> cls = owner; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] mp = m.getParameterTypes();
                if (mp.length != paramTypes.length) continue;
                boolean match = true;
                for (int i = 0; i < mp.length; i++) {
                    if (!mp[i].equals(paramTypes[i]) && !mp[i].isAssignableFrom(paramTypes[i])) { match = false; break; }
                }
                if (match) return m;
            }
        }
        // fallback：按名字匹配（兼容 Enum.ordinal 等继承方法）
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) return m;
        }
        return null;
    }

    private static Class<?> asmTypeToClass(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> boolean.class;
            case Type.CHAR -> char.class;
            case Type.BYTE -> byte.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.FLOAT -> float.class;
            case Type.LONG -> long.class;
            case Type.DOUBLE -> double.class;
            case Type.OBJECT, Type.ARRAY -> {
                try { yield Class.forName(t.getClassName(), false, Thread.currentThread().getContextClassLoader()); }
                catch (Throwable e) { yield null; }
            }
            default -> null;
        };
    }

    private static Object coerceArg(Object v, Class<?> targetType) {
        if (v == null) return targetType.isPrimitive() ? defaultPrimitive(targetType) : null;
        if (targetType.isInstance(v)) return v;
        if (v instanceof Number n) {
            if (targetType == int.class || targetType == Integer.class) return n.intValue();
            if (targetType == long.class || targetType == Long.class) return n.longValue();
            if (targetType == float.class || targetType == Float.class) return n.floatValue();
            if (targetType == double.class || targetType == Double.class) return n.doubleValue();
            if (targetType == short.class || targetType == Short.class) return n.shortValue();
            if (targetType == byte.class || targetType == Byte.class) return n.byteValue();
        }
        return v;
    }

    private static Object defaultPrimitive(Class<?> t) {
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == boolean.class) return false;
        if (t == char.class) return (char) 0;
        return null;
    }

    // ==================== Sister-setter 兜底 ====================

    //递归扫表达式树，对每个数值返回的 UnresolvedCall（去重 owner+name+desc）尝试构造 sister-setter
    private static void collectSisterSinks(Expr e, List<Sink> result, Set<String> seenSig) {
        if (e == null) return;
        if (e instanceof UnresolvedCall uc) {
            Type retType = Type.getReturnType(uc.desc());
            if (isNumericReturn(retType)) {
                String sig = uc.owner() + "#" + uc.name() + uc.desc();
                if (seenSig.add(sig)) {
                    Sink s = trySisterSetter(uc);
                    if (s != null) result.add(s);
                }
            }
            for (Expr arg : uc.args()) collectSisterSinks(arg, result, seenSig);
            return;
        }
        if (e instanceof BinaryOp b) { collectSisterSinks(b.left(), result, seenSig); collectSisterSinks(b.right(), result, seenSig); return; }
        if (e instanceof UnaryOp u) { collectSisterSinks(u.operand(), result, seenSig); return; }
        if (e instanceof Choice c) { for (Expr a : c.alternatives()) collectSisterSinks(a, result, seenSig); return; }
        if (e instanceof InvertibleCall ic) { for (Expr a : ic.args()) collectSisterSinks(a, result, seenSig); return; }
        if (e instanceof ArrayIndexLeaf ai) { collectSisterSinks(ai.arrayExpr(), result, seenSig); collectSisterSinks(ai.indexExpr(), result, seenSig); return; }
        if (e instanceof ChainedFieldLeaf cf) { collectSisterSinks(cf.root(), result, seenSig); }
    }

    private static boolean isNumericReturn(Type retType) {
        return switch (retType.getSort()) {
            case Type.INT, Type.FLOAT, Type.LONG, Type.DOUBLE, Type.SHORT, Type.BYTE -> true;
            default -> false;
        };
    }

    private static final List<String> SETTER_VERBS = List.of("set", "write", "store", "update", "save", "put");

    //在 read 调用 owner class 里找匹配 setter：参数前缀和 read 一致，多一个数值参在末尾
    private static Sink trySisterSetter(UnresolvedCall uc) {
        try {
            Class<?> owner = Class.forName(uc.owner().replace('/', '.'), false,
                Thread.currentThread().getContextClassLoader());
            Type[] readArgTypes = Type.getArgumentTypes(uc.desc());
            boolean readHasReceiver = uc.args().size() > readArgTypes.length;
            Method writer = findWriterMethod(owner, readArgTypes);
            if (writer == null) return null;

            final Method finalWriter = writer;
            final boolean writerStatic = Modifier.isStatic(writer.getModifiers());
            final List<Expr> readArgs = uc.args();
            final int readArgStart = readHasReceiver ? 1 : 0;

            return new Sink() {
                @Override public Number read(LivingEntity entity) { return null; }   // 兜底 sink，不参与 evaluate

                @Override public boolean write(LivingEntity entity, Number value) {
                    try {
                        Class<?>[] writerParams = finalWriter.getParameterTypes();
                        Object[] args = new Object[writerParams.length];
                        int valueIdx = writerParams.length - 1;
                        // 前 valueIdx 个参从 read 调用现场重放
                        for (int i = 0; i < valueIdx; i++) {
                            int srcIdx = readArgStart + i;
                            if (srcIdx >= readArgs.size()) {
                                args[i] = defaultPrimitive(writerParams[i]);
                            } else {
                                Object v = resolveExprObject(entity, readArgs.get(srcIdx), Map.of());
                                args[i] = coerceArg(v, writerParams[i]);
                            }
                        }
                        // 末位是新值
                        args[valueIdx] = coerceArg(value, writerParams[valueIdx]);
                        Object recv = null;
                        if (!writerStatic) {
                            // 实例 setter：用 entity 当 receiver（最常见情况）
                            recv = entity;
                        }
                        finalWriter.setAccessible(true);
                        finalWriter.invoke(recv, args);
                        return true;
                    } catch (Throwable t) {
                        EcaLogger.info("[Phase3] sister-setter invoke failed {}.{}: {}",
                            finalWriter.getDeclaringClass().getSimpleName(), finalWriter.getName(), t.toString());
                        return false;
                    }
                }
            };
        } catch (Throwable t) { return null; }
    }

    private static Method findWriterMethod(Class<?> owner, Type[] readArgTypes) {
        Method best = null;
        for (Method m : owner.getDeclaredMethods()) {
            String lname = m.getName().toLowerCase();
            boolean hasVerb = false;
            for (String v : SETTER_VERBS) if (lname.contains(v)) { hasVerb = true; break; }
            if (!hasVerb) continue;
            Class<?>[] params = m.getParameterTypes();
            // 期望参数数 = read 参数数 或 read 参数数 + 1，且最末位是数值
            if (params.length == 0) continue;
            int expectedFirstN;
            if (params.length == readArgTypes.length) expectedFirstN = readArgTypes.length - 1;
            else if (params.length == readArgTypes.length + 1) expectedFirstN = readArgTypes.length;
            else continue;
            // 末位必须是数值原始类型
            Class<?> last = params[params.length - 1];
            if (!isNumericPrimitive(last)) continue;
            // 前 expectedFirstN 个参类型与 read 一致
            boolean prefixMatch = true;
            for (int i = 0; i < expectedFirstN; i++) {
                Class<?> pt = params[i];
                Class<?> rt = asmTypeToClass(readArgTypes[i]);
                if (rt == null || !pt.equals(rt)) { prefixMatch = false; break; }
            }
            if (!prefixMatch) continue;
            best = m;
            break;
        }
        return best;
    }

    private static boolean isNumericPrimitive(Class<?> c) {
        return c == float.class || c == double.class || c == int.class
            || c == long.class || c == short.class || c == byte.class;
    }

    private static Number resolveExprNumber(LivingEntity entity, Expr e, Map<Expr, Sink> sinks) {
        Object obj = resolveExprObject(entity, e, sinks);
        return obj instanceof Number n ? n : null;
    }

    private static Object readField(Object target, FieldLeaf.FieldStep step) {
        try {
            Class<?> owner = Class.forName(step.ownerInternal().replace('/', '.'), false,
                Thread.currentThread().getContextClassLoader());
            Field f = owner.getDeclaredField(step.name());
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) { return null; }
    }

    private static boolean writeField(Object target, FieldLeaf.FieldStep step, Number value) {
        try {
            Class<?> owner = Class.forName(step.ownerInternal().replace('/', '.'), false,
                Thread.currentThread().getContextClassLoader());
            Field f = owner.getDeclaredField(step.name());
            f.setAccessible(true);
            Class<?> ft = f.getType();
            if (ft == float.class) f.setFloat(target, value.floatValue());
            else if (ft == double.class) f.setDouble(target, value.doubleValue());
            else if (ft == int.class) f.setInt(target, value.intValue());
            else if (ft == long.class) f.setLong(target, value.longValue());
            else if (ft == short.class) f.setShort(target, value.shortValue());
            else if (ft == byte.class) f.setByte(target, value.byteValue());
            else return false;
            return true;
        } catch (Throwable t) { return false; }
    }

    // ==================== 工具 ====================

    private static Class<?> descriptorToClass(String d) {
        if (d == null || d.isEmpty()) return null;
        return switch (d.charAt(0)) {
            case 'F' -> float.class;
            case 'D' -> double.class;
            case 'I' -> int.class;
            case 'J' -> long.class;
            case 'S' -> short.class;
            case 'B' -> byte.class;
            case 'C' -> char.class;
            case 'Z' -> boolean.class;
            case 'L' -> {
                try { yield Class.forName(d.substring(1, d.length() - 1).replace('/', '.')); }
                catch (ClassNotFoundException e) { yield null; }
            }
            default -> null;
        };
    }
}
