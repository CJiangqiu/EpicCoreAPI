package net.eca.util.health;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.eca.coremod.JvmTiChannel;
import net.eca.util.EcaLogger;
import net.eca.util.health.HealthDataflowAnalyzer.AnalysisResult;
import net.eca.util.health.HealthDataflowAnalyzer.ArrayElementSource;
import net.eca.util.health.HealthDataflowAnalyzer.CapabilityDataSource;
import net.eca.util.health.HealthDataflowAnalyzer.ChainedFieldSource;
import net.eca.util.health.HealthDataflowAnalyzer.Choice;
import net.eca.util.health.HealthDataflowAnalyzer.ConstOverrideSource;
import net.eca.util.health.HealthDataflowAnalyzer.EvalContext;
import net.eca.util.health.HealthDataflowAnalyzer.Expr;
import net.eca.util.health.HealthDataflowAnalyzer.FieldChainSource;
import net.eca.util.health.HealthDataflowAnalyzer.FieldStep;
import net.eca.util.health.HealthDataflowAnalyzer.MapEntrySource;
import net.eca.util.health.HealthDataflowAnalyzer.MethodCallSource;
import net.eca.util.health.HealthDataflowAnalyzer.Primitive;
import net.eca.util.health.HealthDataflowAnalyzer.Source;
import net.eca.util.health.HealthDataflowAnalyzer.StaticFieldSource;
import net.eca.util.health.HealthDataflowAnalyzer.SynchedDataSource;
import net.eca.util.reflect.UnsafeUtil;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Type;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 数据流改血落地层：吃 HealthDataflowAnalyzer 产出的可写树(AnalysisResult)，
 * 把求解结果写进目标真实存储并校验，失败回滚。
 * 拥有写目标的全部副作用——Source 子类的真正写入实现按 instanceof 分发到本类，
 * Unsafe/反射/retransform 都集中在这里，使分析器保持零 ECA 依赖、零副作用。
 */
public final class HealthDataFlow {

    private HealthDataFlow() {}

    /* ==================== 启动注入：把分析器接入 ECA 运行期 ==================== */

    private static volatile boolean initialized = false;

    /* ECA 启动期由调用者调用一次：把 ECA 运行期字节码源和 ECA hook 类名告诉分析器，
       使其能读到 mixin/coremod 转换后字节码，并在分析树中自动剥离 ECA 注入。 */
    public static void init() {
        if (initialized) return;
        synchronized (HealthDataFlow.class) {
            if (initialized) return;
            HealthDataflowAnalyzer.setClassBytesProvider(HealthDataFlow::classBytesViaRuntime);
            HealthDataflowAnalyzer.setOverrideLookup(ConstOverride::getOverride);
            MethodProbe.setClassBytesProvider(HealthDataFlow::classBytesViaRuntime);
            HealthDataflowAnalyzer.setStripConfig(
                    Set.of("net/eca/coremod/LivingEntityHook", "net/eca/util/health/HealthLockManager"),
                    Set.of("SF:net.eca.util.EntityUtil.HEALTH_LOCK_VALUE",
                           "SF:net.eca.util.EntityUtil.HEAL_BAN_VALUE"));
            initialized = true;
        }
    }

    /* RuntimeBytecodeProvider 优先(含 mixin/coremod 转换后)，缺失回退分析器内置默认实现 */
    private static byte[] classBytesViaRuntime(Class<?> clazz) {
        try {
            byte[] runtime = net.eca.coremod.RuntimeBytecodeProvider.get(clazz);
            if (runtime != null) return runtime;
        } catch (Throwable ignored) {
            if (ignored instanceof VirtualMachineError e) throw e;
        }
        return HealthDataflowAnalyzer.defaultClassBytes(clazz);
    }

    /* ==================== 写入入口 ==================== */

    //每个实体类首次走数据流改血时打印一次分析结构诊断
    private static final Set<String> FIRST_WRITE_DUMPED = ConcurrentHashMap.newKeySet();

    /* 数据流改血主入口：拿已分析的可写树把目标血量写进目标真实存储，verify 通过返回 true。
       DATAFLOW 与 CONST_OVERRIDE(带可写源)由本入口处理；无源 CONST_OVERRIDE/UNRESOLVED 在表层就被拦掉。 */
    public static boolean write(AnalysisResult tree, LivingEntity entity, float target) {
        if (tree == null || entity == null) return false;
        Class<?> cls = entity.getClass();
        if (FIRST_WRITE_DUMPED.add(cls.getName())) dumpAnalysisStructure(cls, tree, target);
        return writeViaSources(cls, tree, entity, target,
                (verifiedEntity, verifiedTarget, sink) -> EcaSetHealthManager.verify(verifiedEntity, verifiedTarget));
    }

    /* 外部扫描写入：与 write 同骨架，但用外部专用校验器(带死亡语义：目标≤0 需实体确实死亡)。
       供 isAlive/isDeadOrDying/hurt/actuallyHurt 逆推出的可写结构落地。 */
    public static boolean writeExternal(AnalysisResult tree, LivingEntity entity, float target) {
        if (tree == null || entity == null) return false;
        Class<?> cls = entity.getClass();
        if (FIRST_WRITE_DUMPED.add(cls.getName())) dumpAnalysisStructure(cls, tree, target);
        return writeViaSources(cls, tree, entity, target,
                (verifiedEntity, verifiedTarget, sink) ->
                        HealthDataflowAnalyzer.verifyExternalDataflow(tree.returnExpr, verifiedEntity, verifiedTarget, sink));
    }

    /* 首次诊断：打印目标实体类的可写树结构(kind/definingClass/sources 列表)，便于排查不同实体的改血行为 */
    private static void dumpAnalysisStructure(Class<?> cls, AnalysisResult tree, float target) {
        EcaLogger.info("[HealthDataflow] ===== first dataflow write: {} =====", cls.getName());
        EcaLogger.info("[HealthDataflow]   target={} kind={} definingClass={} sources={}",
                target, tree.classify(),
                tree.definingClass != null ? tree.definingClass.getName() : "null",
                tree.sources.size());
        EcaLogger.info("[HealthDataflow]   returnExpr={}", tree.returnExpr);
        int i = 0;
        for (Source s : tree.sources) {
            EcaLogger.info("[HealthDataflow]   sink#{} {} type={} class={}",
                    i++, s.label, s.valueType.getName(), s.getClass().getSimpleName());
        }
    }

    /* ==================== 写入编排 ==================== */

    @FunctionalInterface
    public interface HealthVerifier {
        boolean verify(LivingEntity entity, float expected, Source sink);
    }

    private record PreparedSourceWrite(Source sink, Object snapshot, Object value) {}

    private static final Set<String> FAIL_DUMPED = ConcurrentHashMap.newKeySet();

    /* 逐个验证候选 Source，单点未命中再联合写入(应对双源防御)，失败回滚原值。
       仅在缓存失败树时打印一次诊断，避免每-tick 改血刷屏。 */
    public static boolean writeViaSources(Class<?> cls, AnalysisResult ar, LivingEntity entity, float expected,
                                          HealthVerifier verifier) {
        EvalContext ctx = HealthDataflowAnalyzer.newContext(entity);
        List<String> diag = new ArrayList<>();
        List<PreparedSourceWrite> solvedWrites = new ArrayList<>();
        for (Source sink : ar.sources) {
            HealthSolveResult solved = HealthDataflowAnalyzer.buildWritePath(ar.returnExpr, sink, Float.valueOf(expected), ctx);
            if (!solved.solved() || solved.value() == null) {
                diag.add("    [" + sink.label + "] solve=FAIL " + solved.failure() + " (" + solved.detail() + ")");
                continue;
            }

            Object snapshot = sink.read(entity);
            solvedWrites.add(new PreparedSourceWrite(sink, snapshot, solved.value()));
            if (!dispatchWrite(sink, entity, solved.value())) {
                boolean restored = dispatchWrite(sink, entity, snapshot);
                diag.add("    [" + sink.label + "] solved=" + solved.value()
                        + " write=FAIL restore=" + (restored ? "OK" : "FAIL"));
                continue;
            }
            if (verifier.verify(entity, expected, sink)) {
                EcaLogger.info("[HealthDataflow] setHealth success entity={} sink={} solved={} expected={}",
                        cls.getName(), sink.label, solved.value(), expected);
                return true;
            }

            boolean restored = dispatchWrite(sink, entity, snapshot);
            diag.add("    [" + sink.label + "] solved=" + solved.value()
                    + " verify=FAIL restore=" + (restored ? "OK" : "FAIL"));
        }

        if (writeAllSources(solvedWrites, entity, expected, diag, verifier)) return true;

        if (FAIL_DUMPED.add(cls.getName())) {
            EcaLogger.warn("[HealthDataflow] setHealth failed entity={} expected={} sink results:", cls.getName(), expected);
            for (String line : diag) EcaLogger.warn("[HealthDataflow] {}", line);
        }
        return false;
    }

    /* ≥2 个可解 Source 时联合写入(应对需同时写多处的双源防御)，失败逆序回滚。
       writes 由单源循环收集，其 snapshot 均为原值(循环对每次尝试都已回滚)，故回滚即复原。 */
    private static boolean writeAllSources(List<PreparedSourceWrite> writes, LivingEntity entity, float expected,
                                           List<String> diag, HealthVerifier verifier) {
        if (writes.size() < 2) return false;

        boolean wroteAll = true;
        for (PreparedSourceWrite write : writes) {
            if (!dispatchWrite(write.sink(), entity, write.value())) {
                wroteAll = false;
                break;
            }
        }
        if (wroteAll && verifier.verify(entity, expected, null)) {
            EcaLogger.info("[HealthDataflow] setHealth success entity={} sink=all-sources expected={}",
                    entity.getClass().getName(), expected);
            return true;
        }

        boolean restoredAll = true;
        for (int i = writes.size() - 1; i >= 0; i--) {
            PreparedSourceWrite write = writes.get(i);
            if (!dispatchWrite(write.sink(), entity, write.snapshot())) restoredAll = false;
        }
        diag.add("    [all sources] write=" + (wroteAll ? "OK" : "FAIL")
                + " verify=FAIL restore=" + (restoredAll ? "OK" : "FAIL"));
        return false;
    }

    /* ==================== Source 写入分发(按子类形态) ==================== */

    /* 按 Source 子类形态选择写入实现。新增 Source 子类时必须在此扩充分发，否则写入将默默失败。 */
    public static boolean dispatchWrite(Source sink, LivingEntity entity, Object value) {
        if (sink instanceof FieldChainSource s) return writeFieldChain(s, entity, value);
        if (sink instanceof StaticFieldSource s) return writeStaticField(s, value);
        if (sink instanceof ChainedFieldSource s) return writeChainedField(s, entity, value);
        if (sink instanceof CapabilityDataSource s) return writeCapability(s, entity, value);
        if (sink instanceof SynchedDataSource s) return writeSynchedData(s, entity, value);
        if (sink instanceof MapEntrySource s) return writeMapEntry(s, entity, value);
        if (sink instanceof ArrayElementSource s) return writeArrayElement(s, entity, value);
        if (sink instanceof MethodCallSource s) return writeMethodCall(s, entity, value);
        if (sink instanceof ConstOverrideSource s) return writeConstOverride(s, entity, value);
        return false;
    }

    /* 常数覆写写入：求出该常数点的 holder(① 实体本体 ② 实体的 health manager)，
       把目标血量登记进 ConstOverride；patched 字节码的 resolveHealth(this,...) 据此返回覆写值。 */
    private static boolean writeConstOverride(ConstOverrideSource s, LivingEntity entity, Object value) {
        Object holder = s.holder(entity);
        if (holder == null || !(value instanceof Number n)) return false;
        ConstOverride.setOverride(holder, n.floatValue());
        return true;
    }

    private static boolean writeFieldChain(FieldChainSource s, LivingEntity entity, Object value) {
        VarHandle[] handles = s.handles;
        List<FieldStep> chain = s.chain;
        int n = handles.length;
        FieldStep last = chain.get(n - 1);
        Object coerced;
        try {
            coerced = HealthDataflowAnalyzer.coerceForType(value, s.valueType);
            if (coerced == null && s.valueType.isPrimitive()) return false;
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }

        // record component 不可经 VarHandle/Unsafe 写：改为重建 record 写回上一级字段
        Class<?> leafOwner = HealthDataflowAnalyzer.loadClass(last.ownerInternal());
        if (leafOwner != null && leafOwner.isRecord() && n >= 2) {
            try {
                Object holder = entity;
                for (int i = 0; i < n - 2; i++) {
                    holder = handles[i].get(holder);
                    if (holder == null) return false;
                }
                Object recordObj = handles[n - 2].get(holder);
                Object rebuilt = rebuildRecord(leafOwner, recordObj, last.name(), coerced);
                if (rebuilt == null) return false;
                handles[n - 2].set(holder, rebuilt);
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
        }

        Object container;
        try {
            Object cur = entity;
            for (int i = 0; i < n - 1; i++) {
                cur = handles[i].get(cur);
                if (cur == null) return false;
            }
            container = cur;
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }

        // 普通字段先走 VarHandle，final 字段失败再走 Unsafe 兜底
        try {
            handles[n - 1].set(container, coerced);
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            Class<?> owner = HealthDataflowAnalyzer.loadClass(last.ownerInternal());
            if (owner == null) return false;
            Field f = HealthDataflowAnalyzer.findFieldInHierarchy(owner, last.name());
            if (f == null) return false;
            f.setAccessible(true);
            return UnsafeUtil.unsafePutField(container, f, coerced);
        }
    }

    private static boolean writeStaticField(StaticFieldSource s, Object value) {
        try {
            s.field.set(null, HealthDataflowAnalyzer.coerceForType(value, s.valueType));
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    private static boolean writeChainedField(ChainedFieldSource s, LivingEntity entity, Object value) {
        try {
            Object cur = HealthDataflowAnalyzer.evaluate(s.root, HealthDataflowAnalyzer.newContext(entity));
            if (cur == null) return false;
            for (int i = 0; i < s.chain.size() - 1; i++) {
                cur = readField(cur, s.chain.get(i));
                if (cur == null) return false;
            }
            return writeFieldStep(cur, s.chain.get(s.chain.size() - 1), value);
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
    }

    private static boolean writeCapability(CapabilityDataSource s, LivingEntity entity, Object value) {
        try {
            EvalContext ctx = HealthDataflowAnalyzer.newContext(entity);
            Object container = HealthDataflowAnalyzer.evaluate(s.containerExpr, ctx);
            Object key = HealthDataflowAnalyzer.evaluate(s.keyExpr, ctx);
            if (container == null || key == null) return false;
            if (s.chain.isEmpty()) return writeCapabilitySlot(container, key, value);

            Object slot = readCapabilitySlot(container, key);
            if (slot == null) return false;
            Object cur = slot;
            for (int i = 0; i < s.chain.size() - 1; i++) {
                cur = readField(cur, s.chain.get(i));
                if (cur == null) return false;
            }
            FieldStep leaf = s.chain.get(s.chain.size() - 1);
            if (!writeFieldViaSetter(cur, leaf, value) && !writeFieldStep(cur, leaf, value)) return false;
            writeCapabilitySlot(container, key, slot);
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    private static Object readCapabilitySlot(Object container, Object key) {
        Object value = HealthDataflowAnalyzer.invokeCompatibleSafely(container, "getValue", key);
        return value == HealthDataflowAnalyzer.INVOKE_FAILED ? null : value;
    }

    private static boolean writeCapabilitySlot(Object container, Object key, Object value) {
        return HealthDataflowAnalyzer.invokeCompatibleSafely(container, "setValue", key, value) != HealthDataflowAnalyzer.INVOKE_FAILED;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean writeSynchedData(SynchedDataSource s, LivingEntity entity, Object value) {
        try {
            SynchedEntityData ed = entity.getEntityData();
            Int2ObjectMap<?> map = (Int2ObjectMap<?>) ed.itemsById;
            SynchedEntityData.DataItem item = (SynchedEntityData.DataItem) map.get(s.accessor.getId());
            if (item == null) return false;
            Object coerced = HealthDataflowAnalyzer.coerceSameType(item.value, value);
            if (coerced == null) return false;
            item.value = coerced;
            item.dirty = true;
            ed.isDirty = true;
            entity.onSyncedDataUpdated(s.accessor);
            return true;
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
    }

    private static boolean writeMapEntry(MapEntrySource s, LivingEntity entity, Object value) {
        boolean any = false;
        Set<Object> writtenMaps = Collections.newSetFromMap(new IdentityHashMap<>());

        try {
            Object obj = HealthDataflowAnalyzer.evaluate(s.containerExpr, HealthDataflowAnalyzer.newContext(entity));
            if (obj instanceof Map<?, ?> map && writtenMaps.add(map)) {
                Object key = matchKey(map, entity, s.keyKind);
                if (key != null && unsafeModifyMapEntry(map, key, value)) any = true;
            }
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }

        // 兄弟表/记录表：owner 类及其嵌套类的所有静态 Map 字段一起写，对抗"主表与影子表不一致→回滚"防御
        if (s.ownerClassInternal != null) {
            Class<?> ownerClass = HealthDataflowAnalyzer.loadClass(s.ownerClassInternal);
            if (ownerClass != null && writeSiblingMaps(ownerClass, entity, s, value, writtenMaps)) any = true;
        }
        return any;
    }

    /* 写入 cls 及其嵌套类中所有"已含本实体键"的静态 Map 字段(影子/记录表)，令多表一致、规避不一致回滚。
       仅改动 matchKey 命中(以本实体为键)的 Map，故对无关静态表安全；writtenMaps 身份集防重复写。 */
    private static boolean writeSiblingMaps(Class<?> cls, LivingEntity entity, MapEntrySource s, Object value, Set<Object> writtenMaps) {
        boolean any = false;
        for (Field f : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!Map.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object obj = f.get(null);
                if (!(obj instanceof Map<?, ?> map)) continue;
                if (!writtenMaps.add(map)) continue;
                Object key = matchKey(map, entity, s.keyKind);
                if (key == null) continue;
                if (unsafeModifyMapEntry(map, key, value)) any = true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
        }
        for (Class<?> nested : cls.getDeclaredClasses()) {
            if (writeSiblingMaps(nested, entity, s, value, writtenMaps)) any = true;
        }
        return any;
    }

    private static boolean writeArrayElement(ArrayElementSource s, LivingEntity entity, Object value) {
        try {
            EvalContext ctx = HealthDataflowAnalyzer.newContext(entity);
            Object arr = HealthDataflowAnalyzer.evaluate(s.arrayExpr, ctx);
            Object idx = HealthDataflowAnalyzer.evaluate(s.indexExpr, ctx);
            if (arr == null || !(idx instanceof Number n) || !(value instanceof Number v)) return false;
            int i = n.intValue();
            Class<?> ct = arr.getClass().getComponentType();
            if (ct == int.class) Array.setInt(arr, i, v.intValue());
            else if (ct == long.class) Array.setLong(arr, i, v.longValue());
            else if (ct == float.class) Array.setFloat(arr, i, v.floatValue());
            else if (ct == double.class) Array.setDouble(arr, i, v.doubleValue());
            else if (ct == short.class) Array.setShort(arr, i, v.shortValue());
            else if (ct == byte.class) Array.setByte(arr, i, v.byteValue());
            else Array.set(arr, i, value);
            return true;
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
    }

    private static boolean writeMethodCall(MethodCallSource s, LivingEntity entity, Object value) {
        try {
            Class<?> owner = HealthDataflowAnalyzer.loadClass(s.ownerInternal);
            if (owner == null) return false;
            Type[] argTypes = Type.getArgumentTypes(s.desc);
            boolean isStatic = s.args.size() == argTypes.length;
            int start = isStatic ? 0 : 1;
            Object receiver = null;
            EvalContext ctx = HealthDataflowAnalyzer.newContext(entity);
            if (!isStatic) {
                receiver = HealthDataflowAnalyzer.evaluate(s.args.get(0), ctx);
                if (receiver == null) return false;
            }
            Object[] values = new Object[argTypes.length];
            Class<?>[] paramTypes = new Class<?>[argTypes.length];
            for (int i = 0; i < argTypes.length; i++) {
                paramTypes[i] = HealthDataflowAnalyzer.asmTypeToClass(argTypes[i]);
                if (paramTypes[i] == null) return false;
                Object argValue = i == s.valueArgIndex ? value : HealthDataflowAnalyzer.evaluate(s.args.get(start + i), ctx);
                values[i] = HealthDataflowAnalyzer.coerceArgPublic(argValue, paramTypes[i]);
            }
            Method method = HealthDataflowAnalyzer.findMethod(isStatic ? owner : receiver.getClass(), s.name, paramTypes, values);
            if (method == null && !isStatic) method = HealthDataflowAnalyzer.findMethod(owner, s.name, paramTypes, values);
            if (method == null) return false;
            method.setAccessible(true);
            method.invoke(receiver, values);
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    /* ==================== Map 写入：兄弟表 + entrySet 遍历 + Unsafe ==================== */

    private static final Map<Class<?>, Long> ENTRY_VALUE_OFFSET_CACHE = new ConcurrentHashMap<>();

    private static Object matchKey(Map<?, ?> map, LivingEntity entity, MapEntrySource.KeyKind kind) {
        Object primary = switch (kind) {
            case ENTITY -> entity;
            case ENTITY_UUID -> entity.getUUID();
            case ENTITY_ID -> entity.getId();
            case UNKNOWN -> entity;
        };
        if (primary != null && map.containsKey(primary)) return primary;
        Object[] fb = {entity, entity.getUUID(), entity.getId()};
        for (Object k : fb) if (k != null && map.containsKey(k)) return k;
        return null;
    }

    /* 遍历 entrySet 写所有 key 匹配的 entry(WeakHashMap 多 entry 同 key 的坑),
     * 用 Entry.setValue 绕过 Map.put(常见 mixin 拦截点),失败走 Unsafe 写字段偏移
     */
    private static boolean unsafeModifyMapEntry(Map<?, ?> map, Object targetKey, Object newValue) {
        int written = 0;
        try {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object ek = entry.getKey();
                if (ek != targetKey && (targetKey == null || !targetKey.equals(ek))) continue;
                Object cur = entry.getValue();
                Object boxed = cur == null ? newValue : HealthDataflowAnalyzer.coerceSameType(cur, newValue);
                if (boxed == null) boxed = newValue;

                boolean wrote = false;
                try {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Map.Entry rawEntry = entry;
                    rawEntry.setValue(boxed);
                    if (boxed.equals(entry.getValue())) wrote = true;
                } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }

                if (!wrote) {
                    long offset = getEntryValueOffset(entry);
                    if (offset != -1) {
                        UnsafeUtil.lwjglPutObject(entry, offset, boxed);
                        if (boxed.equals(entry.getValue())) wrote = true;
                    }
                }
                if (wrote) written++;
            }
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
        return written > 0;
    }

    private static long getEntryValueOffset(Object entry) {
        Class<?> ec = entry.getClass();
        Long cached = ENTRY_VALUE_OFFSET_CACHE.get(ec);
        if (cached != null) return cached;
        for (Class<?> cls = ec; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
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
        }
        return -1;
    }

    /* ==================== 字段写入辅助(供 ChainedField/Capability 用) ==================== */

    private static Object readField(Object target, FieldStep step) {
        if (target == null) return null;
        try {
            Field f = HealthDataflowAnalyzer.findFieldInHierarchy(target.getClass(), step.name());
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return null; }
    }

    private static boolean writeFieldViaSetter(Object target, FieldStep step, Object value) {
        if (target == null) return false;
        Class<?> fieldType = HealthDataflowAnalyzer.descriptorToClass(step.desc());
        if (fieldType == null) return false;
        String suffix = step.name().isEmpty()
                ? ""
                : Character.toUpperCase(step.name().charAt(0)) + step.name().substring(1);
        String[] names = suffix.isEmpty() ? new String[] {"set"} : new String[] {"set" + suffix, "setValue"};
        for (String name : names) {
            Method method = findSetter(target.getClass(), name, fieldType, value);
            if (method == null) continue;
            try {
                method.setAccessible(true);
                method.invoke(target, HealthDataflowAnalyzer.coerceArgPublic(value, method.getParameterTypes()[0]));
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
            }
        }
        return false;
    }

    private static Method findSetter(Class<?> owner, String name, Class<?> fieldType, Object value) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1) continue;
                if (HealthDataflowAnalyzer.methodArgMatches(params[0], fieldType, value)) return method;
            }
        }
        return null;
    }

    private static boolean writeFieldStep(Object target, FieldStep step, Object value) {
        Field f;
        Class<?> ft;
        try {
            Class<?> owner = HealthDataflowAnalyzer.loadClass(step.ownerInternal());
            if (owner == null) return false;
            f = HealthDataflowAnalyzer.findFieldInHierarchy(owner, step.name());
            if (f == null) return false;
            f.setAccessible(true);
            ft = f.getType();
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }

        try {
            if (ft == float.class) f.setFloat(target, ((Number) value).floatValue());
            else if (ft == double.class) f.setDouble(target, ((Number) value).doubleValue());
            else if (ft == int.class) f.setInt(target, ((Number) value).intValue());
            else if (ft == long.class) f.setLong(target, ((Number) value).longValue());
            else if (ft == short.class) f.setShort(target, ((Number) value).shortValue());
            else if (ft == byte.class) f.setByte(target, ((Number) value).byteValue());
            else f.set(target, HealthDataflowAnalyzer.coerceForType(value, ft));
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            // final 字段 / 模块系统访问限制 → 走 Unsafe 兜底
            return UnsafeUtil.unsafePutField(target, f, value);
        }
    }

    /* ==================== record 重建(供 FieldChain 末段是 record component 时用) ==================== */

    private static Object rebuildRecord(Class<?> recordClass, Object current, String targetComponent, Object newValue) {
        try {
            RecordComponent[] comps = recordClass.getRecordComponents();
            Class<?>[] types = new Class<?>[comps.length];
            Object[] args = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                types[i] = comps[i].getType();
                if (comps[i].getName().equals(targetComponent)) {
                    args[i] = HealthDataflowAnalyzer.coerceForType(newValue, types[i]);
                } else if (current != null) {
                    Method acc = comps[i].getAccessor();
                    acc.setAccessible(true);
                    args[i] = acc.invoke(current);
                } else {
                    args[i] = types[i].isPrimitive() ? HealthDataflowAnalyzer.coerceForType(0, types[i]) : null;
                }
            }
            Constructor<?> ctor = recordClass.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return null; }
    }

}
