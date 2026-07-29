package net.eca.util.health;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/*
 * ECA数据流逆向分析器
 * 通过从指定方法的字节码进行反向遍历指令序列构造 Expr 树,然后按 DualityTable 反推 sink 应写值
 */
public final class HealthDataflowAnalyzer {

    private static final int DEFAULT_MAX_DEPTH = 6;
    private static final int DEFAULT_INLINE_BUDGET = 500;
    //表达式节点总预算：构造组合表达式时递减，耗尽即坍缩为 Unknown，防止复杂/互递归 getHealth 把表达式树撑爆导致分析卡死
    private static final int DEFAULT_NODE_BUDGET = 500_000;
    //控制流汇合处 Choice 分支上限，超出即加宽为 Unknown，保证格有限高、数据流分析收敛
    private static final int MAX_CHOICE_ALTS = 16;

    /* ==================== MC 实体方法表 ==================== */

    /* MC 实体方法元数据：携带同名方法的 SRG/MCP 双名 + JVM 描述符。Mojang 反混淆切换映射时只需改本表，
       调用者一律按 GET_HEALTH/IS_ALIVE/... 查表，避免常量散落各处与拼接漂移。 */
    public record McMethod(String srg, String mcp, String desc) {
        //在指定类的字节码中按 SRG 优先 MCP 后备查找本方法，未定义返回 null
        public String matchIn(Class<?> cls) {
            if (classDefinesMethod(cls, srg, desc)) return srg;
            if (classDefinesMethod(cls, mcp, desc)) return mcp;
            return null;
        }
    }

    private static final String DAMAGE_SOURCE_DESC = "Lnet/minecraft/world/damagesource/DamageSource;";
    public static final McMethod GET_HEALTH       = new McMethod("m_21223_", "getHealth", "()F");
    public static final McMethod GET_MAX_HEALTH   = new McMethod("m_21233_", "getMaxHealth", "()F");
    public static final McMethod IS_ALIVE         = new McMethod("m_6084_", "isAlive", "()Z");
    public static final McMethod IS_DEAD_OR_DYING = new McMethod("m_21224_", "isDeadOrDying", "()Z");
    public static final McMethod HURT             = new McMethod("m_6469_", "hurt", "(" + DAMAGE_SOURCE_DESC + "F)Z");
    public static final McMethod ACTUALLY_HURT    = new McMethod("m_6475_", "actuallyHurt", "(" + DAMAGE_SOURCE_DESC + "F)V");
    public static final McMethod SET_HEALTH       = new McMethod("m_21153_", "setHealth", "(F)V");

    /* ==================== 外部扫描：isAlive/isDeadOrDying 数据流逆向 ==================== */

    private static final Map<Class<?>, AnalysisResult> EXTERNAL_SCAN_CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> EXTERNAL_SCAN_DIAG_DUMPED = ConcurrentHashMap.newKeySet();
    /* 单个扫描入口抛出的诊断去重(每类每入口一次) */
    private static final Set<String> EXTERNAL_ENTRY_FAILURE_DUMPED = ConcurrentHashMap.newKeySet();

    private HealthDataflowAnalyzer() {}

    public interface ClassBytesProvider {
        byte[] get(Class<?> clazz);
    }
    private static volatile ClassBytesProvider bytesProvider = HealthDataflowAnalyzer::defaultClassBytes;
    public static void setClassBytesProvider(ClassBytesProvider provider) {
        if (provider != null) bytesProvider = provider;
    }

    /* 覆写血量查表钩子：覆写表由外部持有，ConstOverrideSource.read() 据此按 holder 取覆写值。
       未注入时恒无覆写，read 回退原常数。 */
    public interface OverrideLookup {
        Float get(Object holder);
    }
    private static volatile OverrideLookup overrideLookup = holder -> null;
    public static void setOverrideLookup(OverrideLookup lookup) {
        if (lookup != null) overrideLookup = lookup;
    }

    /* 注入式包装剥离：调用者登记注入 getHealth 的 hook owner 与透明静态源 label 前缀，
       分析时从结果树中剥离，避免把调用者自己的逻辑误当作真实血量。独立运行不调用即默认不剥离。 */
    private static final Set<String> WRAPPER_CALL_OWNERS = ConcurrentHashMap.newKeySet();
    private static final Set<String> WRAPPER_SOURCE_LABEL_PREFIXES = ConcurrentHashMap.newKeySet();
    private static final Set<String> WRAPPER_REFERENCE_VALUES = ConcurrentHashMap.newKeySet();
    public static void setStripConfig(Set<String> wrapperCallOwners, Set<String> wrapperSourceLabelPrefixes) {
        setStripConfig(wrapperCallOwners, wrapperSourceLabelPrefixes, Set.of());
    }

    public static void setStripConfig(Set<String> wrapperCallOwners, Set<String> wrapperSourceLabelPrefixes,
                                      Set<String> wrapperReferenceValues) {
        WRAPPER_CALL_OWNERS.clear();
        WRAPPER_SOURCE_LABEL_PREFIXES.clear();
        WRAPPER_REFERENCE_VALUES.clear();
        if (wrapperCallOwners != null) WRAPPER_CALL_OWNERS.addAll(wrapperCallOwners);
        if (wrapperSourceLabelPrefixes != null) WRAPPER_SOURCE_LABEL_PREFIXES.addAll(wrapperSourceLabelPrefixes);
        if (wrapperReferenceValues != null) WRAPPER_REFERENCE_VALUES.addAll(wrapperReferenceValues);
    }

    /* 默认字节码源：ClassLoader 资源 + CodeSource JAR 回退，调用者可注入运行期转换后字节码 */
    public static byte[] defaultClassBytes(Class<?> clazz) {
        if (clazz == null) return null;
        ClassLoader cl = clazz.getClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        String path = internalName(clazz) + ".class";
        try (java.io.InputStream is = cl.getResourceAsStream(path)) {
            if (is != null) return is.readAllBytes();
        } catch (Throwable ignored) { if (ignored instanceof VirtualMachineError e) throw e; }
        try {
            java.security.CodeSource cs = clazz.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(cs.getLocation().getPath())) {
                    java.util.jar.JarEntry entry = jar.getJarEntry(path);
                    if (entry != null) {
                        try (java.io.InputStream jis = jar.getInputStream(entry)) {
                            return jis.readAllBytes();
                        }
                    }
                }
            }
        } catch (Throwable ignored) { if (ignored instanceof VirtualMachineError e) throw e; }
        return null;
    }

    /* ==================== 对外：字节码分析入口 ==================== */

    /* 外部扫描入口：对 isAlive/isDeadOrDying 跑完整数据流管线，返回可写结构；
       无结果或无可写 Source 返回 null。写入由调用者落地。 */
    public static AnalysisResult resolveExternalScanResult(Class<?> entityClass) {
        if (entityClass == null) return null;
        AnalysisResult ar = EXTERNAL_SCAN_CACHE.computeIfAbsent(entityClass,
                c -> analyzeUnifiedExternalScan(entityClass));
        if (ar == null || ar.isEmpty() || ar.sources.isEmpty()) return null;
        return ar;
    }

    /* 仅查外部扫描缓存、绝不触发分析(供运行期非阻塞查询)。重量级分析由调用者在后台线程经
       resolveExternalScanResult 预填，从而不阻塞服务器线程。 */
    public static AnalysisResult peekExternalScanResult(Class<?> entityClass) {
        if (entityClass == null) return null;
        AnalysisResult ar = EXTERNAL_SCAN_CACHE.get(entityClass);
        if (ar == null || ar.isEmpty() || ar.sources.isEmpty()) return null;
        return ar;
    }

    public static boolean verifyExternalDataflow(Expr root, LivingEntity entity, float expected, Source sink) {
        if (entity == null) return false;
        boolean expressionMatches = externalExpressionMatches(root, sink, expected, newContext(entity));
        if (expected <= 0.0f) {
            return expressionMatches && (!entity.isAlive() || entity.isDeadOrDying());
        }
        if (!expressionMatches || entity.isRemoved()) return false;
        // ConstOverride 在外部扫描中是高危假阳性(覆写辅助方法常数不一定改变真实血量)，
        // 须额外通过 getHealth 校验；普通字段/SynchedData 写入信任 expressionMatches
        if (sink instanceof ConstOverrideSource) {
            return EcaSetHealthManager.verify(entity, expected);
        }
        return true;
    }

    private static final Set<String> EXTERNAL_EVAL_DIAG = ConcurrentHashMap.newKeySet();

    private static boolean externalExpressionMatches(Expr expression, Source sink, float expected, EvalContext context) {
        if (expression instanceof Choice choice) {
            for (Expr alternative : choice.alternatives()) {
                if ((sink == null || containsSink(alternative, sink))
                        && externalExpressionMatches(alternative, sink, expected, context)) return true;
            }
            return false;
        }
        // StoreWrite 是纯副作用写入(如无敌帧/击退计数)，不代表该字段是血量读取源
        if (expression instanceof StoreWrite write) {
            if (sink != null && sameSource(write.sink(), sink) && !containsSinkInReadPosition(write.valueExpr(), sink))
                return false;
        }
        if (sink != null && !containsSink(expression, sink)) return false;
        Object value = safeEvaluate(expression, context);
        if (!(value instanceof Number number)) {
            if (sink != null && EXTERNAL_EVAL_DIAG.add(sink.label + "|eval=" + value))
                EcaLogger.info("[ExternalScan] eval non-numeric sink={} value={} expr={}", sink.label, value, expression);
            return false;
        }
        float actual = number.floatValue();
        float tolerance = Math.max(0.5f, Math.abs(expected) * 0.02f);
        boolean matches = Float.isFinite(actual) && Math.abs(actual - expected) <= tolerance;
        if (!matches && sink != null && EXTERNAL_EVAL_DIAG.add(sink.label + "|actual=" + actual))
            EcaLogger.info("[ExternalScan] eval mismatch sink={} actual={} expected={}", sink.label, actual, expected);
        return matches;
    }

    /* sink 是否出现在读取位置(作为调用参数/运算操作数等)，而非仅作为 StoreWrite 的写入目标 */
    private static boolean containsSinkInReadPosition(Expr e, Source sink) {
        if (sameSource(e, sink)) return true;
        if (e instanceof Op op) {
            for (Expr a : op.args()) if (containsSinkInReadPosition(a, sink)) return true;
        }
        if (e instanceof Call call) {
            for (Expr a : call.args()) if (containsSinkInReadPosition(a, sink)) return true;
        }
        if (e instanceof Choice c) {
            for (Expr a : c.alternatives()) if (containsSinkInReadPosition(a, sink)) return true;
        }
        return false;
    }

    /* ==================== Expr 类型系统 ==================== */

    public interface Expr {}

    /* 字面常量,jvmType 标记 IJFD/CSB/Z 等 JVM 类型字符。
       origin 记录常数加载指令在字节码中的来源(类/方法/指令下标 + 持有方法 receiver)，供常数覆写精准 patch 定位;
       origin 只是旁路信息,不参与 equals/hashCode——常数仍按值去重,不破坏 Op 折叠语义。 */
    public static final class Primitive implements Expr {
        private final Number value;
        private final char jvmType;
        private final ConstProvenance origin;
        public Primitive(Number value, char jvmType) { this(value, jvmType, null); }
        public Primitive(Number value, char jvmType, ConstProvenance origin) {
            this.value = value; this.jvmType = jvmType; this.origin = origin;
        }
        public Number value() { return value; }
        public char jvmType() { return jvmType; }
        public ConstProvenance origin() { return origin; }
        @Override public boolean equals(Object o) {
            return o instanceof Primitive p && jvmType == p.jvmType && Objects.equals(value, p.value);
        }
        @Override public int hashCode() { return Objects.hash(value, jvmType); }
        @Override public String toString() { return "Primitive[" + value + ":" + jvmType + "]"; }
    }

    /* 常数来源坐标:持有常数加载指令的 类内部名/方法名/方法描述符/指令下标,
       外加该持有方法的 receiver 表达式(local 0,运行期据此定位覆写表的 holder 对象)。 */
    public record ConstProvenance(String ownerInternal, String methodName, String methodDesc,
                                  int insnIndex, boolean holderIsStatic, Expr receiver) {}

    //分析期已确定的对象引用(如 GETSTATIC EntityDataAccessor / 静态 Map)
    public record Reference(Object value, String className) implements Expr {}

    //JVM 算术/位/类型转换指令
    public record Op(int opcode, List<Expr> args) implements Expr {
        @Override public boolean equals(Object o) {
            return this == o || (o instanceof Op other && opcode == other.opcode && args.equals(other.args));
        }
        @Override public int hashCode() { return opcode * 31 + args.hashCode(); }
    }

    //任意方法调用,args 含 receiver(若非 static)
    public record Call(String owner, String name, String desc, List<Expr> args) implements Expr {}

    public record Closure(Handle implementation, String samName, String samDesc, List<Expr> captured) implements Expr {}

    public record StoreWrite(Source sink, Expr valueExpr) implements Expr {}

    public record WriteInput(int index, char jvmType) implements Expr {}

    public record ArrayAllocExpr(int id) implements Expr {}

    public record OptionalContentExpr(Expr optionalExpr) implements Expr {}

    //控制流汇合 / 多 return 路径的并集
    public record Choice(List<Expr> alternatives) implements Expr {}

    //分析期无法符号化的节点
    public record UnknownExpr(String provenance) implements Expr {
        public static final UnknownExpr UNKNOWN = new UnknownExpr("");
    }

    /* 数据源(sink 候选)：仅描述位置 + 求解期 read 代入，equals 按 canonicalKey。
       写入实体的副作用由调用者按 instanceof 分发到对应实现，本类不承担写入。 */
    public static abstract class Source implements Expr {
        public final Class<?> valueType;
        public final String label;
        protected Source(Class<?> valueType, String label) {
            this.valueType = valueType;
            this.label = label;
        }
        public abstract Object read(LivingEntity entity);
        protected abstract String canonicalKey();

        /* 数值反演默认使用 read() 的结果作为运行期对象锚点。
           容器类型的子类应同时提供容器或根对象，避免依赖具体容器结构。 */
        public void collectDescentAnchors(EvalContext ctx, Consumer<Object> sink) {
            sink.accept(read(ctx.entity()));
        }
        @Override public boolean equals(Object o) {
            return o instanceof Source s && canonicalKey().equals(s.canonicalKey());
        }
        @Override public int hashCode() { return canonicalKey().hashCode(); }
        @Override public String toString() { return label; }
    }

    /* ==================== Source 子类 ==================== */

    public record FieldStep(String ownerInternal, String name, String desc) {}

    //this.a.b.c 字段链,可指向任意类型字段(数值/String/Object)
    public static final class FieldChainSource extends Source {
        public final List<FieldStep> chain;
        public final VarHandle[] handles;

        public FieldChainSource(List<FieldStep> chain, VarHandle[] handles, Class<?> valueType) {
            super(valueType, "F:" + describe(chain));
            this.chain = chain;
            this.handles = handles;
        }

        private static String describe(List<FieldStep> chain) {
            StringBuilder sb = new StringBuilder();
            for (FieldStep s : chain) { if (sb.length() > 0) sb.append('.'); sb.append(s.name()); }
            return sb.toString();
        }

        @Override public Object read(LivingEntity entity) {
            try {
                Object cur = entity;
                for (VarHandle vh : handles) {
                    if (cur == null) return null;
                    cur = vh.get(cur);
                }
                return cur;
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
        }


        @Override protected String canonicalKey() {
            StringBuilder sb = new StringBuilder("FC:");
            for (FieldStep s : chain) sb.append(s.ownerInternal()).append('.').append(s.name()).append(';');
            return sb.toString();
        }
    }

    /* 常数覆写源：把 getHealth 逆向中某个被精准 patch 的常数点建模为可写源。
       read/write 先 evaluate(receiver) 求出 holder 对象(① 实体本体 ② 实体的 health manager)，
       按其 identity 读写覆写表，与被 patch 字节码里的 resolveHealth(this,...) 落在同一 holder 上。 */
    public static final class ConstOverrideSource extends Source {
        public final Expr receiver;
        public final float original;
        public final ConstProvenance provenance;

        public ConstOverrideSource(Expr receiver, float original, ConstProvenance provenance) {
            super(float.class, "CO:" + provenance.ownerInternal() + "#" + provenance.methodName()
                    + "@" + provenance.insnIndex());
            this.receiver = receiver;
            this.original = original;
            this.provenance = provenance;
        }

        // 求 holder 对象：receiver 为 EntityParamMarker 时即实体，否则按字段链等表达式求值
        public Object holder(LivingEntity entity) {
            try {
                return evaluate(receiver, newContext(entity));
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return null; }
        }

        @Override public Object read(LivingEntity entity) {
            Object h = holder(entity);
            if (h == null) return original;
            Float ov = overrideLookup.get(h);
            return ov != null ? ov : original;
        }

        @Override protected String canonicalKey() {
            return "CO:" + provenance.ownerInternal() + "#" + provenance.methodName()
                    + "#" + provenance.methodDesc() + "@" + provenance.insnIndex();
        }
    }

    public static final class StaticFieldSource extends Source {
        public final Field field;

        public StaticFieldSource(Field field) {
            super(field.getType(), "SF:" + field.getDeclaringClass().getName() + "." + field.getName());
            this.field = field;
            this.field.setAccessible(true);
        }

        @Override
        public Object read(LivingEntity entity) {
            try {
                return field.get(null);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return null;
            }
        }

        @Override
        protected String canonicalKey() {
            return "SF:" + field.getDeclaringClass().getName() + "." + field.getName();
        }
    }

    //在非 this 受体上做字段链：root 是任意 Expr,运行时 eval 得到受体对象,再走 chain
    public static final class ChainedFieldSource extends Source {
        public final Expr root;
        public final List<FieldStep> chain;

        public ChainedFieldSource(Expr root, List<FieldStep> chain, Class<?> valueType) {
            super(valueType, "CF:" + describe(chain));
            this.root = root;
            this.chain = chain;
        }

        private static String describe(List<FieldStep> chain) {
            StringBuilder sb = new StringBuilder();
            for (FieldStep s : chain) { if (sb.length() > 0) sb.append('.'); sb.append(s.name()); }
            return sb.toString();
        }

        @Override public Object read(LivingEntity entity) {
            try {
                Object cur = evaluate(root, new SimpleEvalContext(entity));
                for (FieldStep s : chain) {
                    if (cur == null) return null;
                    cur = readField(cur, s);
                }
                return cur;
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
        }

        @Override public void collectDescentAnchors(EvalContext ctx, Consumer<Object> sink) {
            sink.accept(read(ctx.entity()));
            sink.accept(safeEvaluate(root, ctx));
        }

        @Override protected String canonicalKey() {
            StringBuilder sb = new StringBuilder("CFS:").append(System.identityHashCode(root)).append(':');
            for (FieldStep s : chain) sb.append(s.ownerInternal()).append('.').append(s.name()).append(';');
            return sb.toString();
        }
    }

    //SynchedEntityData.get(accessor) - 读写 DataItem.value
    public static final class CapabilityDataSource extends Source {
        public final Expr containerExpr;
        public final Expr keyExpr;
        public final List<FieldStep> chain;

        public CapabilityDataSource(Expr containerExpr, Expr keyExpr, List<FieldStep> chain, Class<?> valueType) {
            super(valueType, "CAP:" + (chain.isEmpty() ? "value" : describeCapabilityChain(chain)));
            this.containerExpr = containerExpr;
            this.keyExpr = keyExpr;
            this.chain = chain;
        }

        private static String describeCapabilityChain(List<FieldStep> chain) {
            StringBuilder sb = new StringBuilder();
            for (FieldStep step : chain) {
                if (sb.length() > 0) sb.append('.');
                sb.append(step.name());
            }
            return sb.toString();
        }

        @Override public Object read(LivingEntity entity) {
            try {
                EvalContext ctx = new SimpleEvalContext(entity);
                Object slot = readCapabilitySlot(ctx);
                for (FieldStep step : chain) {
                    if (slot == null) return null;
                    slot = readField(slot, step);
                }
                return slot;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
                return null;
            }
        }

        private Object readCapabilitySlot(EvalContext ctx) {
            Object container = evaluate(containerExpr, ctx);
            Object key = evaluate(keyExpr, ctx);
            return container == null || key == null ? null : readCapabilitySlot(container, key);
        }

        private static Object readCapabilitySlot(Object container, Object key) {
            Object value = invokeCompatible(container, "getValue", key);
            return value == InvokeFailed.INSTANCE ? null : value;
        }

        @Override public void collectDescentAnchors(EvalContext ctx, Consumer<Object> sink) {
            sink.accept(read(ctx.entity()));
            sink.accept(safeEvaluate(containerExpr, ctx));
        }

        @Override protected String canonicalKey() {
            StringBuilder sb = new StringBuilder("CAP:")
                    .append(System.identityHashCode(containerExpr)).append(':')
                    .append(System.identityHashCode(keyExpr)).append(':');
            for (FieldStep step : chain) sb.append(step.ownerInternal()).append('.').append(step.name()).append(';');
            return sb.toString();
        }
    }

    public static final class SynchedDataSource extends Source {
        public final EntityDataAccessor<?> accessor;

        public SynchedDataSource(EntityDataAccessor<?> accessor, Class<?> valueType) {
            super(valueType, "SD:" + accessor.getId());
            this.accessor = accessor;
        }

        @SuppressWarnings("rawtypes")
        @Override public Object read(LivingEntity entity) {
            try {
                Int2ObjectMap<?> map = (Int2ObjectMap<?>) entity.getEntityData().itemsById;
                SynchedEntityData.DataItem item = (SynchedEntityData.DataItem) map.get(accessor.getId());
                return item == null ? null : item.value;
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
        }

        @Override protected String canonicalKey() { return "SD:" + accessor.getId(); }
    }

    //在某个容器对象上做 .get(key)。ownerClassInternal 非空时启用兄弟表联写,对抗影子表回滚
    public static final class MapEntrySource extends Source {
        public final Expr containerExpr;
        public final Expr keyExpr;
        public final KeyKind keyKind;
        public final String ownerClassInternal;

        public enum KeyKind { ENTITY, ENTITY_UUID, ENTITY_ID, UNKNOWN }

        public MapEntrySource(Expr containerExpr, Expr keyExpr, KeyKind keyKind,
                              String ownerClassInternal, Class<?> valueType, String label) {
            super(valueType, "M:" + label);
            this.containerExpr = containerExpr;
            this.keyExpr = keyExpr;
            this.keyKind = keyKind;
            this.ownerClassInternal = ownerClassInternal;
        }

        /* read 提供当前匹配 entry 的值供求解使用；相关 Map 的同步写入由调用者完成。
           容器既支持 java.util.Map，也鸭子类型支持不实现 Map 的自定义容器(仅依赖只读 containsKey/get 访问器)。 */
        @Override public Object read(LivingEntity entity) {
            try {
                Object obj = evaluate(containerExpr, new SimpleEvalContext(entity));
                if (obj == null) return null;
                Object[] fb = {entity, entity.getUUID(), entity.getId()};
                if (obj instanceof Map<?, ?> map) {
                    for (Object k : fb) if (k != null && map.containsKey(k)) return map.get(k);
                    return null;
                }
                return duckMapGet(obj, fb);
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
        }

        @Override public void collectDescentAnchors(EvalContext ctx, Consumer<Object> sink) {
            sink.accept(read(ctx.entity()));
            sink.accept(safeEvaluate(containerExpr, ctx));
        }

        @Override protected String canonicalKey() {
            return "ME:" + System.identityHashCode(containerExpr) + ":" + keyKind;
        }

        /* 鸭子类型读取自定义 map 容器：按容器类缓存 containsKey/get 只读访问器，逐一尝试候选键。
           仅调用只读访问器(无写入/写锁副作用)，使不实现 java.util.Map 的容器也能交出 entry 值供反演下探。 */
        private static final Map<Class<?>, Method[]> DUCK_ACCESSORS = new ConcurrentHashMap<>();
        private static final Method[] NO_DUCK_ACCESSORS = new Method[0];

        private static Object duckMapGet(Object container, Object[] keys) {
            Method[] acc = DUCK_ACCESSORS.computeIfAbsent(container.getClass(), cls -> {
                Method containsKey = findDuckAccessor(cls, "containsKey");
                Method get = findDuckAccessor(cls, "get");
                if (containsKey == null || get == null) return NO_DUCK_ACCESSORS;
                containsKey.setAccessible(true);
                get.setAccessible(true);
                return new Method[]{containsKey, get};
            });
            if (acc == NO_DUCK_ACCESSORS) return null;
            try {
                for (Object k : keys) {
                    if (k == null) continue;
                    if (Boolean.TRUE.equals(acc[0].invoke(container, k))) return acc[1].invoke(container, k);
                }
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
            return null;
        }

        // 找擦除后的泛型 map 只读访问器：单参、非原始参数(排除 get(int) 索引器)，优先 Object 参数
        private static Method findDuckAccessor(Class<?> cls, String name) {
            Method fallback = null;
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                    Class<?> pt = m.getParameterTypes()[0];
                    if (pt.isPrimitive()) continue;
                    if (pt == Object.class) return m;
                    if (fallback == null) fallback = m;
                }
            }
            return fallback;
        }
    }

    //arr[i] - arrayExpr 和 indexExpr 都是 Expr
    public static final class ArrayElementSource extends Source {
        public final Expr arrayExpr;
        public final Expr indexExpr;

        public ArrayElementSource(Expr arrayExpr, Expr indexExpr, Class<?> valueType, String label) {
            super(valueType, "A:" + label);
            this.arrayExpr = arrayExpr;
            this.indexExpr = indexExpr;
        }

        @Override public Object read(LivingEntity entity) {
            try {
                EvalContext ctx = new SimpleEvalContext(entity);
                Object arr = evaluate(arrayExpr, ctx);
                Object idx = evaluate(indexExpr, ctx);
                if (arr == null || !(idx instanceof Number n)) return null;
                return Array.get(arr, n.intValue());
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
        }

        @Override public void collectDescentAnchors(EvalContext ctx, Consumer<Object> sink) {
            sink.accept(read(ctx.entity()));
            sink.accept(safeEvaluate(arrayExpr, ctx));
        }

        @Override protected String canonicalKey() {
            return "AE:" + System.identityHashCode(arrayExpr) + ":" + System.identityHashCode(indexExpr);
        }
    }

    public static final class MethodCallSource extends Source {
        public final String ownerInternal;
        public final String name;
        public final String desc;
        public final List<Expr> args;
        public final int valueArgIndex;

        public MethodCallSource(String ownerInternal, String name, String desc, List<Expr> args, int valueArgIndex) {
            super(float.class, "MC:" + ownerInternal + "#" + name + desc + "[" + valueArgIndex + "]");
            this.ownerInternal = ownerInternal;
            this.name = name;
            this.desc = desc;
            this.args = args;
            this.valueArgIndex = valueArgIndex;
        }

        @Override public Object read(LivingEntity entity) {
            return null;
        }

        @Override protected String canonicalKey() {
            return "MC:" + ownerInternal + "#" + name + "#" + desc + "#" + valueArgIndex;
        }
    }

    /* ==================== 求逆接口与对偶表 ==================== */

    public interface Inverter {
        Object invert(Object target, List<Expr> args, int sinkArgIdx, EvalContext ctx);
    }

    public interface EvalContext {
        Object eval(Expr e);
        LivingEntity entity();
    }

    public static final class DualityTable {
        private final Map<Integer, Inverter> opRules = new HashMap<>();
        private final Map<String, Inverter> callRules = new HashMap<>();
        public void registerOp(int opcode, Inverter inv) { opRules.put(opcode, inv); }
        public void registerCall(String owner, String name, String desc, Inverter inv) {
            callRules.put(owner + "#" + name + "#" + desc, inv);
        }
        public Inverter lookupOp(int opcode) { return opRules.get(opcode); }
        public Inverter lookupCall(String owner, String name, String desc) {
            return callRules.get(owner + "#" + name + "#" + desc);
        }
    }

    public static final DualityTable TABLE = new DualityTable();
    static { initDefaultRules(); }

    /* 运行期发现的编解码对偶：仅在激进逻辑开启时，允许把同一工具类中已存在的 encode(P)->E
       作为 decode(E)->P 的逆，明文 P 可为数字或文本。它不尝试破译密钥，只复用目标自身的合法编码器。 */
    private static final Map<String, Inverter> DISCOVERED_CODEC_INVERTERS = new ConcurrentHashMap<>();
    private static final Set<String> CODEC_DISCOVERY_FAILED = ConcurrentHashMap.newKeySet();

    private static Inverter lookupCallInverter(Call call) {
        Inverter known = TABLE.lookupCall(call.owner(), call.name(), call.desc());
        if (known != null) return known;
        // Call IR 为实例调用额外携带 receiver；动态编码器只接受无 receiver 的静态工具方法。
        if (call.args().size() != Type.getArgumentTypes(call.desc()).length) return null;
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()) return null;
        String key = call.owner() + "#" + call.name() + "#" + call.desc();
        Inverter cached = DISCOVERED_CODEC_INVERTERS.get(key);
        if (cached != null || CODEC_DISCOVERY_FAILED.contains(key)) return cached;
        Inverter discovered = discoverCodecInverter(call.owner(), call.name(), call.desc());
        if (discovered == null) {
            CODEC_DISCOVERY_FAILED.add(key);
            return null;
        }
        Inverter existing = DISCOVERED_CODEC_INVERTERS.putIfAbsent(key, discovered);
        return existing != null ? existing : discovered;
    }

    private static Inverter discoverCodecInverter(String ownerInternal, String decoderName, String decoderDesc) {
        try {
            Type[] decoderArgs = Type.getArgumentTypes(decoderDesc);
            Type decoderReturn = Type.getReturnType(decoderDesc);
            if (decoderArgs.length != 1 || decoderArgs[0].getSort() != Type.OBJECT) return null;
            /* 明文可以是数字，也可以是文本：密文串先解成明文串，再由上层 parseXxx 取数，
               此时解码器形如 (String)->String，不能只认数值返回。 */
            boolean numericPlain = isNumericAsmType(decoderReturn);
            if (!numericPlain && decoderReturn.getSort() != Type.OBJECT) return null;
            Class<?> owner = loadClass(ownerInternal);
            Class<?> encodedType = asmTypeToClass(decoderArgs[0]);
            Class<?> plainType = asmTypeToClass(decoderReturn);
            if (owner == null || encodedType == null || plainType == null) return null;
            Method decoder = findDeclaredStatic(owner, decoderName, encodedType, plainType);
            if (decoder == null) return null;
            for (Method encoder : owner.getDeclaredMethods()) {
                // 明密文同类型时解码器自身也符合编码器形状，必须排除
                if (encoder.equals(decoder) || !Modifier.isStatic(encoder.getModifiers())
                        || encoder.getParameterCount() != 1 || encoder.getReturnType() != encodedType) continue;
                Class<?> plainInput = encoder.getParameterTypes()[0];
                if (numericPlain ? !isNumericClass(plainInput) : plainInput != plainType) continue;
                encoder.setAccessible(true);
                decoder.setAccessible(true);
                if (validCodecPair(decoder, encoder, numericPlain)) {
                    return (target, args, sinkArgIdx, ctx) -> encodeCodecTarget(encoder, target);
                }
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
        return null;
    }

    private static Method findDeclaredStatic(Class<?> owner, String name, Class<?> parameter, Class<?> returnType) {
        for (Method method : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getName().equals(name)
                    && method.getReturnType() == returnType && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == parameter) return method;
        }
        return null;
    }

    private static boolean isNumericClass(Class<?> type) {
        return type == byte.class || type == short.class || type == int.class || type == long.class
                || type == float.class || type == double.class || type == Byte.class || type == Short.class
                || type == Integer.class || type == Long.class || type == Float.class || type == Double.class
                || type == Number.class;
    }

    /* 以探针值验证 decode(encode(p)) == p。数值明文按容差比较，文本明文按内容比较。
       文本探针取浮点字面形态：这类编解码在血量场景中总是垫在 parseFloat 之下。 */
    private static boolean validCodecPair(Method decoder, Method encoder, boolean numericPlain) {
        try {
            if (numericPlain) {
                for (float probe : new float[]{1.25f, 17.5f, 73.75f}) {
                    Object input = coerceForType(Float.valueOf(probe), encoder.getParameterTypes()[0]);
                    if (input == null) return false;
                    Object decoded = decoder.invoke(null, encoder.invoke(null, input));
                    if (!(decoded instanceof Number number) || !Float.isFinite(number.floatValue())) return false;
                    if (Math.abs(number.floatValue() - probe) > Math.max(0.01f, Math.abs(probe) * 0.001f)) return false;
                }
                return true;
            }
            for (String probe : new String[]{"1.25", "-73.75", "20.0"}) {
                Object input = coerceForType(probe, encoder.getParameterTypes()[0]);
                if (input == null) return false;
                Object encoded = encoder.invoke(null, input);
                if (encoded == null) return false;
                Object decoded = decoder.invoke(null, encoded);
                if (decoded == null || !probe.equals(decoded.toString())) return false;
            }
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    private static Object encodeCodecTarget(Method encoder, Object target) {
        try {
            Object input = coerceForType(target, encoder.getParameterTypes()[0]);
            return input == null ? null : encoder.invoke(null, input);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    /* ==================== Solve / Evaluate ==================== */

    public static Object solveFor(Expr root, Source sink, Object target, EvalContext ctx) {
        return solveDetailed(root, sink, target, ctx).value();
    }

    public static HealthSolveResult solveDetailed(Expr root, Source sink, Object target, EvalContext ctx) {
        if (root == null || sink == null) {
            return HealthSolveResult.failure(HealthSolveFailure.LOCATION_NOT_FOUND, "root or sink is null");
        }
        if (root instanceof StoreWrite write) {
            if (!sameSource(write.sink(), sink)) {
                return HealthSolveResult.failure(HealthSolveFailure.LOCATION_NOT_FOUND, "sink is absent from store write");
            }
            Object value = solveStoreWriteValue(write.valueExpr(), target, ctx);
            return value == null
                ? HealthSolveResult.failure(HealthSolveFailure.VALUE_NOT_REPRESENTABLE, "store value expression could not be evaluated")
                : HealthSolveResult.success(value);
        }
        if (sameSource(root, sink)) {
            Object value = coerceForType(target, sink.valueType);
            return value == null
                ? HealthSolveResult.failure(HealthSolveFailure.VALUE_NOT_REPRESENTABLE, sink.valueType.getName())
                : HealthSolveResult.success(value);
        }
        if (root instanceof Choice c) {
            HealthSolveResult last = HealthSolveResult.failure(HealthSolveFailure.LOCATION_NOT_FOUND, "sink is absent from all branches");
            for (Expr alt : c.alternatives()) {
                if (!containsSink(alt, sink)) continue;
                HealthSolveResult result = solveDetailed(alt, sink, target, ctx);
                if (result.solved()) return result;
                last = result;
            }
            return last;
        }
        if (root instanceof Op op) {
            int idx = findArgWithSinkDetailed(op.args(), sink);
            if (idx == -2) {
                Inverter inv = TABLE.lookupOp(op.opcode());
                if (inv == null) return HealthSolveResult.failure(HealthSolveFailure.INVERTER_MISSING, "opcode=" + op.opcode());
                for (int i = 0; i < op.args().size(); i++) {
                    if (!containsSink(op.args().get(i), sink)) continue;
                    Object newT = inv.invert(target, op.args(), i, ctx);
                    if (newT == null) continue;
                    HealthSolveResult result = solveDetailed(op.args().get(i), sink, newT, ctx);
                    if (result.solved()) return result;
                }
                return HealthSolveResult.failure(HealthSolveFailure.MULTI_LOCATION_UNSUPPORTED, "sink occurs in multiple operands");
            }
            if (idx < 0) return HealthSolveResult.failure(HealthSolveFailure.LOCATION_NOT_FOUND, "sink is absent from operation");
            Inverter inv = TABLE.lookupOp(op.opcode());
            if (inv == null) return HealthSolveResult.failure(HealthSolveFailure.INVERTER_MISSING, "opcode=" + op.opcode());
            Object newT = inv.invert(target, op.args(), idx, ctx);
            if (newT == null) return HealthSolveResult.failure(HealthSolveFailure.VALUE_NOT_REPRESENTABLE, "operation inverse rejected target");
            return solveDetailed(op.args().get(idx), sink, newT, ctx);
        }
        if (root instanceof Call call) {
            int idx = findArgWithSinkDetailed(call.args(), sink);
            if (idx == -2) {
                Inverter inv = lookupCallInverter(call);
                if (inv == null) {
                    HealthSolveFailure failure = call.owner().startsWith("java/util/function/")
                        ? HealthSolveFailure.CALL_NOT_RESOLVED : HealthSolveFailure.INVERTER_MISSING;
                    return HealthSolveResult.failure(failure, call.owner() + "#" + call.name() + call.desc());
                }
                for (int i = 0; i < call.args().size(); i++) {
                    if (!containsSink(call.args().get(i), sink)) continue;
                    Object newT = inv.invert(target, call.args(), i, ctx);
                    if (newT == null) continue;
                    HealthSolveResult result = solveDetailed(call.args().get(i), sink, newT, ctx);
                    if (result.solved()) return result;
                }
                return HealthSolveResult.failure(HealthSolveFailure.MULTI_LOCATION_UNSUPPORTED, "sink occurs in multiple call operands");
            }
            if (idx < 0) return HealthSolveResult.failure(HealthSolveFailure.LOCATION_NOT_FOUND, "sink is absent from call");
            Inverter inv = lookupCallInverter(call);
            if (inv == null) {
                HealthSolveFailure failure = call.owner().startsWith("java/util/function/")
                    ? HealthSolveFailure.CALL_NOT_RESOLVED : HealthSolveFailure.INVERTER_MISSING;
                return HealthSolveResult.failure(failure, call.owner() + "#" + call.name() + call.desc());
            }
            Object newT = inv.invert(target, call.args(), idx, ctx);
            if (newT == null) return HealthSolveResult.failure(HealthSolveFailure.VALUE_NOT_REPRESENTABLE, "call inverse rejected target");
            return solveDetailed(call.args().get(idx), sink, newT, ctx);
        }
        return HealthSolveResult.failure(HealthSolveFailure.CALL_NOT_RESOLVED, root.getClass().getSimpleName());
    }

    private static Object solveStoreWriteValue(Expr valueExpr, Object target, EvalContext ctx) {
        List<Object> candidates = writeInputCandidates(valueExpr, target);
        for (Object candidate : candidates) {
            Object value = evaluateWithWriteInput(valueExpr, ctx, candidate);
            if (value instanceof String s && s.isEmpty()) dumpStoreWriteSolve(valueExpr, target, candidate, "empty-string");
            if (value != null) return value;
            dumpStoreWriteSolve(valueExpr, target, candidate, "null");
        }
        Object fallback = evaluate(valueExpr, ctx);
        if (fallback instanceof String s && s.isEmpty()) dumpStoreWriteSolve(valueExpr, target, null, "fallback-empty-string");
        return fallback;
    }

    @SuppressWarnings("unused")
    private static void dumpStoreWriteSolve(Expr valueExpr, Object target, Object candidate, String reason) {
        // 诊断已迁移至调用者；本占位保留签名以兼容历史调用点，无副作用
    }

    private static List<Object> writeInputCandidates(Expr valueExpr, Object target) {
        List<Object> candidates = new ArrayList<>();
        if (target instanceof Number number && valueExprPrefersNegatedInput(valueExpr)) {
            candidates.add(negateNumber(number));
        }
        candidates.add(target);
        if (target instanceof Number number) {
            Object negated = negateNumber(number);
            if (!candidates.contains(negated)) candidates.add(negated);
        }
        return candidates;
    }

    private static Object negateNumber(Number number) {
        if (number instanceof Double) return Double.valueOf(-number.doubleValue());
        if (number instanceof Float) return Float.valueOf(-number.floatValue());
        if (number instanceof Long) return Long.valueOf(-number.longValue());
        if (number instanceof Integer || number instanceof Short || number instanceof Byte) {
            return Integer.valueOf(-number.intValue());
        }
        return Float.valueOf(-number.floatValue());
    }

    private static boolean valueExprPrefersNegatedInput(Expr expr) {
        if (expr instanceof Op op) {
            if ((op.opcode() == Opcodes.FNEG || op.opcode() == Opcodes.DNEG
                    || op.opcode() == Opcodes.INEG || op.opcode() == Opcodes.LNEG)
                    && !op.args().isEmpty() && containsWriteInput(op.args().get(0))) {
                return true;
            }
            for (Expr arg : op.args()) if (valueExprPrefersNegatedInput(arg)) return true;
        } else if (expr instanceof Call call) {
            for (Expr arg : call.args()) if (valueExprPrefersNegatedInput(arg)) return true;
        } else if (expr instanceof Choice choice) {
            for (Expr arg : choice.alternatives()) if (valueExprPrefersNegatedInput(arg)) return true;
        } else if (expr instanceof OptionalContentExpr optional) {
            return valueExprPrefersNegatedInput(optional.optionalExpr());
        }
        return false;
    }

    private static boolean containsWriteInput(Expr expr) {
        if (expr instanceof WriteInput) return true;
        if (expr instanceof Op op) {
            for (Expr arg : op.args()) if (containsWriteInput(arg)) return true;
        } else if (expr instanceof Call call) {
            for (Expr arg : call.args()) if (containsWriteInput(arg)) return true;
        } else if (expr instanceof Choice choice) {
            for (Expr arg : choice.alternatives()) if (containsWriteInput(arg)) return true;
        } else if (expr instanceof OptionalContentExpr optional) {
            return containsWriteInput(optional.optionalExpr());
        } else if (expr instanceof StoreWrite write) {
            return containsWriteInput(write.valueExpr());
        }
        return false;
    }

    private static boolean sameSource(Expr a, Source sink) {
        return a == sink || (a instanceof Source s && s.canonicalKey().equals(sink.canonicalKey()));
    }

    public static boolean containsSink(Expr e, Source sink) {
        if (sameSource(e, sink)) return true;
        if (e instanceof Op op) {
            for (Expr a : op.args()) if (containsSink(a, sink)) return true;
        }
        if (e instanceof Call call) {
            for (Expr a : call.args()) if (containsSink(a, sink)) return true;
        }
        if (e instanceof Closure closure) {
            for (Expr a : closure.captured()) if (containsSink(a, sink)) return true;
        }
        if (e instanceof StoreWrite write) {
            return sameSource(write.sink(), sink);
        }
        if (e instanceof Choice c) {
            for (Expr a : c.alternatives()) if (containsSink(a, sink)) return true;
        }
        return false;
    }

    // 递归检查表达式树是否包含 UnknownExpr，用于标记无法符号化的 getHealth 实现
    static boolean containsUnknown(Expr e) {
        if (e instanceof UnknownExpr) return true;
        if (e instanceof Op op) {
            for (Expr a : op.args()) if (containsUnknown(a)) return true;
        }
        if (e instanceof Call call) {
            for (Expr a : call.args()) if (containsUnknown(a)) return true;
        }
        if (e instanceof Closure closure) {
            for (Expr a : closure.captured()) if (containsUnknown(a)) return true;
        }
        if (e instanceof Choice c) {
            for (Expr a : c.alternatives()) if (containsUnknown(a)) return true;
        }
        if (e instanceof OptionalContentExpr o) return containsUnknown(o.optionalExpr());
        return false;
    }

    /* 判断表达式树中是否存在至少一条纯字面常数分支。
       对 Choice：只要任一条 alternative 是常数即返回 true（分支级判定）。
       对 Op：所有 args 必须都是常数才算常数（保持语义正确性）。
       其余节点类型不再放宽——Source/Call/Closure/Unknown 一律不是常数。 */
    static boolean hasConstantBranch(Expr e) {
        if (e instanceof Primitive) return true;
        if (e instanceof Op op) {
            for (Expr a : op.args()) if (!hasConstantBranch(a)) return false;
            return true;
        }
        if (e instanceof Choice c) {
            for (Expr a : c.alternatives()) if (hasConstantBranch(a)) return true;
            return false;
        }
        if (e instanceof Call call) {
            for (Expr a : call.args()) if (hasConstantBranch(a)) return true;
            return false;
        }
        return false;
    }

    private static int findArgWithSink(List<Expr> args, Source sink) {
        int detailed = findArgWithSinkDetailed(args, sink);
        return detailed < 0 ? -1 : detailed;
    }

    private static int findArgWithSinkDetailed(List<Expr> args, Source sink) {
        int found = -1;
        for (int i = 0; i < args.size(); i++) {
            if (containsSink(args.get(i), sink)) {
                if (found >= 0) return -2;
                found = i;
            }
        }
        return found;
    }

    public static Set<Source> collectSources(Expr e) {
        Set<Source> out = new LinkedHashSet<>();
        collect(e, out);
        return out;
    }

    /* 有效血量表达式是否依赖"当次伤害量"。血量是实体状态，伤害是方法入参：
       WriteInput 代表被内联方法的数值形参(hurt/actuallyHurt 的 damage)，伤害事件的 getAmount 同理。
       含此类节点的式子算的是这一击之后的值而非当前血量，用作锚点会解出偏移一个伤害量的存储值。 */
    public static boolean dependsOnDamageInput(Expr e) {
        if (e == null) return false;
        if (e instanceof WriteInput) return true;
        if (e instanceof Call call) {
            if (isDamageAmountCall(call)) return true;
            for (Expr a : call.args()) if (dependsOnDamageInput(a)) return true;
            return false;
        }
        if (e instanceof Op op) {
            for (Expr a : op.args()) if (dependsOnDamageInput(a)) return true;
            return false;
        }
        if (e instanceof Choice c) {
            for (Expr a : c.alternatives()) if (dependsOnDamageInput(a)) return true;
            return false;
        }
        if (e instanceof Closure closure) {
            for (Expr a : closure.captured()) if (dependsOnDamageInput(a)) return true;
            return false;
        }
        if (e instanceof OptionalContentExpr optional) return dependsOnDamageInput(optional.optionalExpr());
        if (e instanceof StoreWrite write) return dependsOnDamageInput(write.valueExpr());
        return false;
    }

    private static boolean isDamageAmountCall(Call call) {
        return call.owner().startsWith("net/minecraftforge/event/entity/living/")
                && call.name().equals("getAmount");
    }

    private static void collect(Expr e, Set<Source> out) {
        if (e instanceof Source s) out.add(s);
        else if (e instanceof Op op) for (Expr a : op.args()) collect(a, out);
        else if (e instanceof Call call) for (Expr a : call.args()) collect(a, out);
        else if (e instanceof Closure closure) for (Expr a : closure.captured()) collect(a, out);
        else if (e instanceof OptionalContentExpr optional) collect(optional.optionalExpr(), out);
        else if (e instanceof StoreWrite write) out.add(write.sink());
        else if (e instanceof Choice c) for (Expr a : c.alternatives()) collect(a, out);
    }

    /* 方案 A 常数覆写重写：仅把返回值顶层、或 Choice 直接分支上的合格 float 常数
       替换为 ConstOverrideSource，使常数点成为可写 sink；算术内部常数暂不处理。 */
    private static Expr rewriteConstOverrides(Expr e) {
        ConstOverrideSource top = asConstOverride(e);
        if (top != null) return top;
        if (e instanceof Choice c) {
            boolean changed = false;
            List<Expr> rebuilt = new ArrayList<>(c.alternatives().size());
            for (Expr alt : c.alternatives()) {
                Expr rewritten = rewriteConstOverrides(alt);
                if (rewritten != alt) changed = true;
                rebuilt.add(rewritten);
            }
            return changed ? new Choice(rebuilt) : e;
        }
        return e;
    }

    /* 合格判定：带 provenance(可定位 patch)、非静态 holder(patch 需 this)、float 常数(与 resolveHealth 签名一致)
       → 建覆写源；否则返回 null 表示不替换。 */
    private static ConstOverrideSource asConstOverride(Expr e) {
        if (!(e instanceof Primitive p)) return null;
        ConstProvenance prov = p.origin();
        if (prov == null || prov.holderIsStatic() || p.jvmType() != 'F') return null;
        return new ConstOverrideSource(prov.receiver(), p.value().floatValue(), prov);
    }

    /* 遍历 returnExpr，遇到没有反演器的 Call 或 Op 时，求值其参数并收集运行期对象。
       收集过程只读，表达式防止重复访问，对象按身份去重。 */
    public static List<Object> collectDeadEndRoots(Expr root, EvalContext ctx) {
        List<Object> out = new ArrayList<>();
        collectDeadEndRoots(root, ctx, out,
                Collections.newSetFromMap(new IdentityHashMap<>()),
                Collections.newSetFromMap(new IdentityHashMap<>()));
        return out;
    }

    private static void collectDeadEndRoots(Expr e, EvalContext ctx, List<Object> out, Set<Object> seenObjs, Set<Expr> seenExpr) {
        if (e == null || !seenExpr.add(e)) return;
        if (e instanceof Call call) {
            if (TABLE.lookupCall(call.owner(), call.name(), call.desc()) == null) {
                for (Expr arg : call.args()) addDeadEndRoot(arg, ctx, out, seenObjs);
            } else {
                for (Expr arg : call.args()) collectDeadEndRoots(arg, ctx, out, seenObjs, seenExpr);
            }
        } else if (e instanceof Op op) {
            if (TABLE.lookupOp(op.opcode()) == null) {
                for (Expr arg : op.args()) addDeadEndRoot(arg, ctx, out, seenObjs);
            } else {
                for (Expr arg : op.args()) collectDeadEndRoots(arg, ctx, out, seenObjs, seenExpr);
            }
        } else if (e instanceof Choice c) {
            for (Expr alt : c.alternatives()) collectDeadEndRoots(alt, ctx, out, seenObjs, seenExpr);
        }
    }

    /* 从不可反演参数的子表达式收集运行期对象，供数值反演遍历。
       Source 提供自身值及相关容器，其他节点以求值结果作为锚点并继续查找嵌套 Source。 */
    private static void addDeadEndRoot(Expr arg, EvalContext ctx, List<Object> out, Set<Object> seenObjs) {
        harvestAnchors(arg, ctx, obj -> {
            if (obj == null || obj instanceof Number || obj instanceof Boolean
                    || obj instanceof Character || obj instanceof String) return;
            if (seenObjs.add(obj)) out.add(obj);
        });
    }

    private static void harvestAnchors(Expr e, EvalContext ctx, Consumer<Object> sink) {
        if (e == null) return;
        if (e instanceof Source s) {
            try { s.collectDescentAnchors(ctx, sink); }
            catch (Throwable t) { if (t instanceof VirtualMachineError err) throw err; }
            return;
        }
        sink.accept(safeEvaluate(e, ctx));
        if (e instanceof Op op) for (Expr a : op.args()) harvestAnchors(a, ctx, sink);
        else if (e instanceof Call c) for (Expr a : c.args()) harvestAnchors(a, ctx, sink);
        else if (e instanceof Choice c) for (Expr a : c.alternatives()) harvestAnchors(a, ctx, sink);
        else if (e instanceof OptionalContentExpr o) harvestAnchors(o.optionalExpr(), ctx, sink);
    }

    // 锚点求值失败时返回 null，避免自定义解码或结构差异中断收集
    private static Object safeEvaluate(Expr e, EvalContext ctx) {
        try { return evaluate(e, ctx); }
        catch (Throwable t) { if (t instanceof VirtualMachineError err) throw err; return null; }
    }

    public static Object evaluate(Expr e, EvalContext ctx) {
        if (e instanceof Primitive p) return p.value();
        if (e instanceof Reference r) return r.value();
        //this 占位符解析为接收者实体，使 getHealth = f(this.method(), this.field) 中的 this 方法调用可被 concrete 求值
        if (e == EntityParamMarker.I) return ctx.entity();
        if (e instanceof Source s) return s.read(ctx.entity());
        if (e instanceof OptionalContentExpr optional) {
            Object container = evaluate(optional.optionalExpr(), ctx);
            return unwrapOptionalContent(container);
        }
        if (e instanceof StoreWrite write) {
            return evaluate(write.valueExpr(), ctx);
        }
        if (e instanceof WriteInput) return null;
        if (e instanceof Choice c) {
            for (Expr alt : c.alternatives()) {
                Object v = evaluate(alt, ctx);
                if (v != null) return v;
            }
            return null;
        }
        if (e instanceof Op op) {
            List<Object> ev = new ArrayList<>(op.args().size());
            for (Expr a : op.args()) {
                Object v = evaluate(a, ctx);
                if (v == null) return null;
                ev.add(v);
            }
            return execOp(op.opcode(), ev);
        }
        if (e instanceof Call call) {
            Object known = evaluateKnownCall(call, ctx, null, false);
            if (known != UnknownEval.INSTANCE) return known;
            return invokeCall(call, ctx);
        }
        if (e instanceof Closure) return null;
        return null;
    }

    private static Object evaluateWithWriteInput(Expr e, EvalContext ctx, Object inputValue) {
        if (e instanceof WriteInput) return inputValue;
        if (e instanceof Primitive p) return p.value();
        if (e instanceof Reference r) return r.value();
        if (e == EntityParamMarker.I) return ctx.entity();
        if (e instanceof Source s) return s.read(ctx.entity());
        if (e instanceof OptionalContentExpr optional) {
            Object container = evaluateWithWriteInput(optional.optionalExpr(), ctx, inputValue);
            return unwrapOptionalContent(container);
        }
        if (e instanceof Choice choice) {
            for (Expr alternative : orderedWriteAlternatives(choice)) {
                Object value = evaluateWithWriteInput(alternative, ctx, inputValue);
                if (value != null) return value;
            }
            return null;
        }
        if (e instanceof Op op) {
            List<Object> values = new ArrayList<>(op.args().size());
            for (Expr arg : op.args()) {
                Object value = evaluateWithWriteInput(arg, ctx, inputValue);
                if (value == null) return null;
                values.add(value);
            }
            return execOp(op.opcode(), values);
        }
        if (e instanceof Call call) {
            Object known = evaluateKnownCall(call, ctx, inputValue, true);
            if (known != UnknownEval.INSTANCE) return known;
            List<Expr> args = new ArrayList<>(call.args().size());
            for (Expr arg : call.args()) {
                Object value = evaluateWithWriteInput(arg, ctx, inputValue);
                if (value == null) return null;
                args.add(new Reference(value, value.getClass().getName().replace('.', '/')));
            }
            return invokeCall(new Call(call.owner(), call.name(), call.desc(), args), ctx);
        }
        if (e instanceof StoreWrite write) return evaluateWithWriteInput(write.valueExpr(), ctx, inputValue);
        return null;
    }

    /* Exception fallbacks often merge before the normal data-bearing branch. Store writes must
       prefer expressions tied to live state and the requested input over literal defaults. */
    private static List<Expr> orderedWriteAlternatives(Choice choice) {
        List<Expr> alternatives = new ArrayList<>(choice.alternatives());
        alternatives.sort(Comparator.comparingInt(HealthDataflowAnalyzer::writeBranchScore).reversed());
        return alternatives;
    }

    private static int writeBranchScore(Expr expression) {
        if (expression instanceof WriteInput) return 1_000;
        if (expression instanceof Source) return 800;
        if (expression == EntityParamMarker.I) return 600;
        if (expression instanceof UnknownExpr) return -1_000;
        if (expression instanceof Primitive) return 0;
        if (expression instanceof Reference) return 20;
        if (expression instanceof OptionalContentExpr optional) {
            return 40 + writeBranchScore(optional.optionalExpr());
        }
        if (expression instanceof StoreWrite write) return writeBranchScore(write.valueExpr());
        if (expression instanceof Op operation) return 60 + childBranchScore(operation.args());
        if (expression instanceof Call call) return 80 + childBranchScore(call.args());
        if (expression instanceof Closure closure) return 40 + childBranchScore(closure.captured());
        if (expression instanceof Choice nested) {
            int best = -1_000;
            for (Expr alternative : nested.alternatives()) {
                best = Math.max(best, writeBranchScore(alternative));
            }
            return best;
        }
        return 0;
    }

    private static int childBranchScore(List<Expr> expressions) {
        int score = 0;
        for (Expr expression : expressions) {
            score += Math.max(-200, Math.min(1_000, writeBranchScore(expression)));
        }
        return score;
    }

    private enum UnknownEval { INSTANCE }

    private static Object evaluateKnownCall(Call call, EvalContext ctx, Object inputValue, boolean hasInput) {
        List<Object> values = new ArrayList<>(call.args().size());
        for (Expr arg : call.args()) {
            Object value = hasInput ? evaluateWithWriteInput(arg, ctx, inputValue) : evaluate(arg, ctx);
            if (value == null) return UnknownEval.INSTANCE;
            values.add(value);
        }
        try {
            String owner = call.owner();
            String name = call.name();
            String desc = call.desc();
            if (owner.equals("java/lang/Float") && name.equals("toString") && desc.equals("(F)Ljava/lang/String;")) {
                return Float.toString(((Number) values.get(0)).floatValue());
            }
            if (owner.equals("java/lang/Double") && name.equals("toString") && desc.equals("(D)Ljava/lang/String;")) {
                return Double.toString(((Number) values.get(0)).doubleValue());
            }
            if (owner.equals("java/lang/Integer") && name.equals("toString") && desc.equals("(I)Ljava/lang/String;")) {
                return Integer.toString(((Number) values.get(0)).intValue());
            }
            if (owner.equals("java/lang/Long") && name.equals("toString") && desc.equals("(J)Ljava/lang/String;")) {
                return Long.toString(((Number) values.get(0)).longValue());
            }
            if (owner.equals("java/lang/String") && name.equals("valueOf") && values.size() == 1) {
                return String.valueOf(values.get(0));
            }
            if ((owner.equals("java/lang/Float") || owner.equals("java/lang/Double")
                    || owner.equals("java/lang/Integer") || owner.equals("java/lang/Long"))
                    && name.equals("valueOf") && values.size() == 1) {
                return values.get(0);
            }
            if (name.equals("floatValue") && desc.equals("()F") && values.get(0) instanceof Number number) {
                return number.floatValue();
            }
            if (name.equals("doubleValue") && desc.equals("()D") && values.get(0) instanceof Number number) {
                return number.doubleValue();
            }
            if (name.equals("intValue") && desc.equals("()I") && values.get(0) instanceof Number number) {
                return number.intValue();
            }
            if (name.equals("longValue") && desc.equals("()J") && values.get(0) instanceof Number number) {
                return number.longValue();
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
        return UnknownEval.INSTANCE;
    }

    /* ==================== 预置对偶规则 ==================== */

    private static void initDefaultRules() {
        registerArith(Opcodes.IADD, 'I');
        registerArith(Opcodes.LADD, 'J');
        registerArith(Opcodes.FADD, 'F');
        registerArith(Opcodes.DADD, 'D');
        registerSub(Opcodes.ISUB, 'I');
        registerSub(Opcodes.LSUB, 'J');
        registerSub(Opcodes.FSUB, 'F');
        registerSub(Opcodes.DSUB, 'D');
        registerMul(Opcodes.IMUL, 'I');
        registerMul(Opcodes.LMUL, 'J');
        registerMul(Opcodes.FMUL, 'F');
        registerMul(Opcodes.DMUL, 'D');
        registerDiv(Opcodes.IDIV, 'I');
        registerDiv(Opcodes.LDIV, 'J');
        registerDiv(Opcodes.FDIV, 'F');
        registerDiv(Opcodes.DDIV, 'D');
        registerUnaryNeg(Opcodes.INEG, 'I');
        registerUnaryNeg(Opcodes.LNEG, 'J');
        registerUnaryNeg(Opcodes.FNEG, 'F');
        registerUnaryNeg(Opcodes.DNEG, 'D');

        // XOR 自逆
        TABLE.registerOp(Opcodes.IXOR, (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            return Integer.valueOf(((Number) t).intValue() ^ o.intValue());
        });
        TABLE.registerOp(Opcodes.LXOR, (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            return Long.valueOf(((Number) t).longValue() ^ o.longValue());
        });

        // 移位
        TABLE.registerOp(Opcodes.ISHL, (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number sh = num(ctx.eval(args.get(1))); if (sh == null) return null;
            return Integer.valueOf(((Number) t).intValue() >>> sh.intValue());
        });
        TABLE.registerOp(Opcodes.LSHL, (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number sh = num(ctx.eval(args.get(1))); if (sh == null) return null;
            return Long.valueOf(((Number) t).longValue() >>> sh.intValue());
        });
        TABLE.registerOp(Opcodes.ISHR, (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number sh = num(ctx.eval(args.get(1))); if (sh == null) return null;
            return Integer.valueOf(((Number) t).intValue() << sh.intValue());
        });
        TABLE.registerOp(Opcodes.LSHR, (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number sh = num(ctx.eval(args.get(1))); if (sh == null) return null;
            return Long.valueOf(((Number) t).longValue() << sh.intValue());
        });
        TABLE.registerOp(Opcodes.IUSHR, (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number sh = num(ctx.eval(args.get(1))); if (sh == null) return null;
            return Integer.valueOf(((Number) t).intValue() << sh.intValue());
        });
        TABLE.registerOp(Opcodes.LUSHR, (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number sh = num(ctx.eval(args.get(1))); if (sh == null) return null;
            return Long.valueOf(((Number) t).longValue() << sh.intValue());
        });

        // 位掩码
        TABLE.registerOp(Opcodes.IAND, (t, args, idx, ctx) -> {
            Number m = num(ctx.eval(args.get(1 - idx))); if (m == null) return null;
            int target = ((Number) t).intValue(), mask = m.intValue();
            if ((target & mask) != target) return null;
            return Integer.valueOf(target);
        });
        TABLE.registerOp(Opcodes.LAND, (t, args, idx, ctx) -> {
            Number m = num(ctx.eval(args.get(1 - idx))); if (m == null) return null;
            long target = ((Number) t).longValue(), mask = m.longValue();
            if ((target & mask) != target) return null;
            return Long.valueOf(target);
        });
        TABLE.registerOp(Opcodes.IOR, (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            return Integer.valueOf(((Number) t).intValue() & ~o.intValue());
        });
        TABLE.registerOp(Opcodes.LOR, (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            return Long.valueOf(((Number) t).longValue() & ~o.longValue());
        });

        // 类型转换
        TABLE.registerOp(Opcodes.I2F, (t, args, idx, ctx) -> Integer.valueOf(((Number) t).intValue()));
        TABLE.registerOp(Opcodes.I2L, (t, args, idx, ctx) -> Integer.valueOf((int) ((Number) t).longValue()));
        TABLE.registerOp(Opcodes.I2D, (t, args, idx, ctx) -> Integer.valueOf(((Number) t).intValue()));
        TABLE.registerOp(Opcodes.I2B, (t, args, idx, ctx) -> Integer.valueOf(((Number) t).intValue() & 0xFF));
        TABLE.registerOp(Opcodes.I2C, (t, args, idx, ctx) -> Integer.valueOf(((Number) t).intValue() & 0xFFFF));
        TABLE.registerOp(Opcodes.I2S, (t, args, idx, ctx) -> Integer.valueOf((short) ((Number) t).intValue()));
        TABLE.registerOp(Opcodes.L2I, (t, args, idx, ctx) -> Long.valueOf(((Number) t).intValue()));
        TABLE.registerOp(Opcodes.L2F, (t, args, idx, ctx) -> Long.valueOf((long) ((Number) t).floatValue()));
        TABLE.registerOp(Opcodes.L2D, (t, args, idx, ctx) -> Long.valueOf((long) ((Number) t).doubleValue()));
        TABLE.registerOp(Opcodes.F2I, (t, args, idx, ctx) -> Float.valueOf(((Number) t).intValue()));
        TABLE.registerOp(Opcodes.F2L, (t, args, idx, ctx) -> Float.valueOf(((Number) t).longValue()));
        TABLE.registerOp(Opcodes.F2D, (t, args, idx, ctx) -> Float.valueOf((float) ((Number) t).doubleValue()));
        TABLE.registerOp(Opcodes.D2I, (t, args, idx, ctx) -> Double.valueOf(((Number) t).intValue()));
        TABLE.registerOp(Opcodes.D2L, (t, args, idx, ctx) -> Double.valueOf(((Number) t).longValue()));
        TABLE.registerOp(Opcodes.D2F, (t, args, idx, ctx) -> Double.valueOf(((Number) t).floatValue()));

        // JDK 位转换
        TABLE.registerCall("java/lang/Float", "intBitsToFloat", "(I)F",
            (t, args, idx, ctx) -> Integer.valueOf(Float.floatToRawIntBits(((Number) t).floatValue())));
        TABLE.registerCall("java/lang/Float", "floatToRawIntBits", "(F)I",
            (t, args, idx, ctx) -> Float.valueOf(Float.intBitsToFloat(((Number) t).intValue())));
        TABLE.registerCall("java/lang/Float", "floatToIntBits", "(F)I",
            (t, args, idx, ctx) -> Float.valueOf(Float.intBitsToFloat(((Number) t).intValue())));
        TABLE.registerCall("java/lang/Double", "longBitsToDouble", "(J)D",
            (t, args, idx, ctx) -> Long.valueOf(Double.doubleToRawLongBits(((Number) t).doubleValue())));
        TABLE.registerCall("java/lang/Double", "doubleToRawLongBits", "(D)J",
            (t, args, idx, ctx) -> Double.valueOf(Double.longBitsToDouble(((Number) t).longValue())));

        // JDK 位操作 (自逆)
        TABLE.registerCall("java/lang/Long", "reverse", "(J)J",
            (t, args, idx, ctx) -> Long.valueOf(Long.reverse(((Number) t).longValue())));
        TABLE.registerCall("java/lang/Integer", "reverse", "(I)I",
            (t, args, idx, ctx) -> Integer.valueOf(Integer.reverse(((Number) t).intValue())));
        TABLE.registerCall("java/lang/Integer", "reverseBytes", "(I)I",
            (t, args, idx, ctx) -> Integer.valueOf(Integer.reverseBytes(((Number) t).intValue())));
        TABLE.registerCall("java/lang/Long", "reverseBytes", "(J)J",
            (t, args, idx, ctx) -> Long.valueOf(Long.reverseBytes(((Number) t).longValue())));

        // 循环移位：rotateLeft(v,n) 的逆是 rotateRight(target,n)，反之亦然。n 必须可求值，且 sink 须在值参(idx==0)
        TABLE.registerCall("java/lang/Long", "rotateLeft", "(JI)J", (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number n = num(ctx.eval(args.get(1))); if (n == null) return null;
            return Long.valueOf(Long.rotateRight(((Number) t).longValue(), n.intValue()));
        });
        TABLE.registerCall("java/lang/Long", "rotateRight", "(JI)J", (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number n = num(ctx.eval(args.get(1))); if (n == null) return null;
            return Long.valueOf(Long.rotateLeft(((Number) t).longValue(), n.intValue()));
        });
        TABLE.registerCall("java/lang/Integer", "rotateLeft", "(II)I", (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number n = num(ctx.eval(args.get(1))); if (n == null) return null;
            return Integer.valueOf(Integer.rotateRight(((Number) t).intValue(), n.intValue()));
        });
        TABLE.registerCall("java/lang/Integer", "rotateRight", "(II)I", (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number n = num(ctx.eval(args.get(1))); if (n == null) return null;
            return Integer.valueOf(Integer.rotateLeft(((Number) t).intValue(), n.intValue()));
        });

        // JDK 文本 <-> 数字
        TABLE.registerCall("java/lang/Float", "parseFloat", "(Ljava/lang/String;)F",
            (t, args, idx, ctx) -> Float.toString(((Number) t).floatValue()));
        TABLE.registerCall("java/lang/Double", "parseDouble", "(Ljava/lang/String;)D",
            (t, args, idx, ctx) -> Double.toString(((Number) t).doubleValue()));
        TABLE.registerCall("java/lang/Integer", "parseInt", "(Ljava/lang/String;)I",
            (t, args, idx, ctx) -> Integer.toString(((Number) t).intValue()));
        TABLE.registerCall("java/lang/Long", "parseLong", "(Ljava/lang/String;)J",
            (t, args, idx, ctx) -> Long.toString(((Number) t).longValue()));
        TABLE.registerCall("java/lang/Integer", "parseInt", "(Ljava/lang/String;I)I",
            (t, args, idx, ctx) -> {
                if (idx != 0) return null;
                Number radix = num(ctx.eval(args.get(1))); if (radix == null) return null;
                return Integer.toString(((Number) t).intValue(), radix.intValue());
            });
        TABLE.registerCall("java/lang/Long", "parseLong", "(Ljava/lang/String;I)J",
            (t, args, idx, ctx) -> {
                if (idx != 0) return null;
                Number radix = num(ctx.eval(args.get(1))); if (radix == null) return null;
                return Long.toString(((Number) t).longValue(), radix.intValue());
            });
        TABLE.registerCall("java/lang/Float", "valueOf", "(Ljava/lang/String;)Ljava/lang/Float;",
            (t, args, idx, ctx) -> Float.toString(((Number) t).floatValue()));
        TABLE.registerCall("java/lang/Integer", "valueOf", "(Ljava/lang/String;)Ljava/lang/Integer;",
            (t, args, idx, ctx) -> Integer.toString(((Number) t).intValue()));

        // JDK 数学函数
        TABLE.registerCall("java/lang/Math", "sqrt", "(D)D",
            (t, args, idx, ctx) -> { double x = ((Number) t).doubleValue(); return Double.valueOf(x * x); });
        TABLE.registerCall("java/lang/Math", "cbrt", "(D)D",
            (t, args, idx, ctx) -> { double x = ((Number) t).doubleValue(); return Double.valueOf(x * x * x); });
        TABLE.registerCall("java/lang/Math", "log", "(D)D",
            (t, args, idx, ctx) -> Double.valueOf(Math.exp(((Number) t).doubleValue())));
        TABLE.registerCall("java/lang/Math", "exp", "(D)D",
            (t, args, idx, ctx) -> Double.valueOf(Math.log(((Number) t).doubleValue())));
        TABLE.registerCall("java/lang/Math", "log10", "(D)D",
            (t, args, idx, ctx) -> Double.valueOf(Math.pow(10.0, ((Number) t).doubleValue())));
        // Math.max/min 的两个输入由不同阶段同步写入，此处为当前输入返回相同目标值
        TABLE.registerCall("java/lang/Math", "max", "(FF)F",
            (t, args, idx, ctx) -> Float.valueOf(((Number) t).floatValue()));
        TABLE.registerCall("java/lang/Math", "min", "(FF)F",
            (t, args, idx, ctx) -> Float.valueOf(((Number) t).floatValue()));
        TABLE.registerCall("java/lang/Math", "max", "(II)I",
            (t, args, idx, ctx) -> Integer.valueOf(((Number) t).intValue()));
        TABLE.registerCall("java/lang/Math", "min", "(II)I",
            (t, args, idx, ctx) -> Integer.valueOf(((Number) t).intValue()));
        TABLE.registerCall("java/lang/Math", "max", "(DD)D",
            (t, args, idx, ctx) -> Double.valueOf(((Number) t).doubleValue()));
        TABLE.registerCall("java/lang/Math", "min", "(DD)D",
            (t, args, idx, ctx) -> Double.valueOf(((Number) t).doubleValue()));
        TABLE.registerCall("java/lang/Math", "pow", "(DD)D", (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number exp = num(ctx.eval(args.get(1))); if (exp == null) return null;
            double e = exp.doubleValue();
            if (Math.abs(e) < 1e-9) return null;
            return Double.valueOf(Math.pow(((Number) t).doubleValue(), 1.0 / e));
        });

        // Math 精确算术：与普通 +/-/×/取负/±1 等价的双射，混淆器常用它伪装成不同形态
        TABLE.registerCall("java/lang/Math", "addExact", "(II)I", (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            return subDomain(t, o, 'I');
        });
        TABLE.registerCall("java/lang/Math", "addExact", "(JJ)J", (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            return subDomain(t, o, 'J');
        });
        TABLE.registerCall("java/lang/Math", "subtractExact", "(II)I", (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            return idx == 0 ? addDomain(t, o, 'I') : subDomain(o, (Number) t, 'I');
        });
        TABLE.registerCall("java/lang/Math", "subtractExact", "(JJ)J", (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            return idx == 0 ? addDomain(t, o, 'J') : subDomain(o, (Number) t, 'J');
        });
        TABLE.registerCall("java/lang/Math", "multiplyExact", "(II)I", (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null || isZero(o, 'I')) return null;
            return divDomain(t, o, 'I');
        });
        TABLE.registerCall("java/lang/Math", "multiplyExact", "(JJ)J", (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null || isZero(o, 'J')) return null;
            return divDomain(t, o, 'J');
        });
        TABLE.registerCall("java/lang/Math", "multiplyExact", "(JI)J", (t, args, idx, ctx) -> {
            if (idx != 0) return null;
            Number o = num(ctx.eval(args.get(1))); if (o == null || o.longValue() == 0L) return null;
            return Long.valueOf(((Number) t).longValue() / o.longValue());
        });
        TABLE.registerCall("java/lang/Math", "negateExact", "(I)I", (t, args, idx, ctx) -> negDomain((Number) t, 'I'));
        TABLE.registerCall("java/lang/Math", "negateExact", "(J)J", (t, args, idx, ctx) -> negDomain((Number) t, 'J'));
        TABLE.registerCall("java/lang/Math", "incrementExact", "(I)I", (t, args, idx, ctx) -> Integer.valueOf(((Number) t).intValue() - 1));
        TABLE.registerCall("java/lang/Math", "incrementExact", "(J)J", (t, args, idx, ctx) -> Long.valueOf(((Number) t).longValue() - 1L));
        TABLE.registerCall("java/lang/Math", "decrementExact", "(I)I", (t, args, idx, ctx) -> Integer.valueOf(((Number) t).intValue() + 1));
        TABLE.registerCall("java/lang/Math", "decrementExact", "(J)J", (t, args, idx, ctx) -> Long.valueOf(((Number) t).longValue() + 1L));
        TABLE.registerCall("java/lang/Math", "toIntExact", "(J)I", (t, args, idx, ctx) -> Long.valueOf(((Number) t).intValue()));

        // 字节翻转 / 无符号转换补全（自逆 / 截断回原宽度）
        TABLE.registerCall("java/lang/Short", "reverseBytes", "(S)S",
            (t, args, idx, ctx) -> Integer.valueOf(Short.reverseBytes(((Number) t).shortValue())));
        TABLE.registerCall("java/lang/Integer", "toUnsignedLong", "(I)J",
            (t, args, idx, ctx) -> Integer.valueOf((int) ((Number) t).longValue()));

        // Base64 解码 → 逆为编码(byte[] → String)。decode 目标是 byte[]，逆推产出可被同款解码器还原的字符串
        TABLE.registerCall("java/util/Base64$Decoder", "decode", "(Ljava/lang/String;)[B",
            (t, args, idx, ctx) -> t instanceof byte[] b ? base64EncodeMatching(args, ctx, b) : null);
        TABLE.registerCall("java/util/Base64$Encoder", "encodeToString", "([B)Ljava/lang/String;",
            (t, args, idx, ctx) -> t instanceof String s ? Base64.getUrlDecoder().decode(s) : null);

    // String.replace(search, "") 仅在替换串为空且 sink 为 receiver 时可逆，此时将 search 加回目标前缀。
    // target 包含 search 时变换不是单射，返回 null。
        TABLE.registerCall("java/lang/String", "replace", "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            (t, args, idx, ctx) -> {
                if (idx != 0 || !(t instanceof String target)) return null;        //sink 须为 receiver
                Object search = ctx.eval(args.get(1));
                Object replacement = ctx.eval(args.get(2));
                if (!(search instanceof CharSequence se) || !(replacement instanceof CharSequence re)) return null;
                if (re.length() != 0) return null;                                  //仅支持替换为空串(纯删除)
                String s = se.toString();
                if (s.isEmpty() || target.contains(s)) return null;                 //保证 (s+target).replace(s,"")==target
                return s + target;
            });

        // Number 拆箱 / Wrapper 装箱 (identity)
        registerIdentityCall("java/lang/Float", "floatValue", "()F");
        registerIdentityCall("java/lang/Double", "doubleValue", "()D");
        registerIdentityCall("java/lang/Integer", "intValue", "()I");
        registerIdentityCall("java/lang/Long", "longValue", "()J");
        registerIdentityCall("java/lang/Short", "shortValue", "()S");
        registerIdentityCall("java/lang/Byte", "byteValue", "()B");
        registerIdentityCall("java/lang/Number", "floatValue", "()F");
        registerIdentityCall("java/lang/Number", "doubleValue", "()D");
        registerIdentityCall("java/lang/Number", "intValue", "()I");
        registerIdentityCall("java/lang/Number", "longValue", "()J");
        registerIdentityCall("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;");
        registerIdentityCall("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
        registerIdentityCall("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;");
        registerIdentityCall("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");

        // Minecraft CompoundTag getXxx (identity,写回由 sink IO 完成)
        registerIdentityCall("net/minecraft/nbt/CompoundTag", "m_128457_", "(Ljava/lang/String;)F");
        registerIdentityCall("net/minecraft/nbt/CompoundTag", "m_128451_", "(Ljava/lang/String;)I");
        registerIdentityCall("net/minecraft/nbt/CompoundTag", "m_128454_", "(Ljava/lang/String;)J");
        registerIdentityCall("net/minecraft/nbt/CompoundTag", "m_128459_", "(Ljava/lang/String;)D");
        registerIdentityCall("net/minecraft/nbt/CompoundTag", "getFloat", "(Ljava/lang/String;)F");
        registerIdentityCall("net/minecraft/nbt/CompoundTag", "getInt", "(Ljava/lang/String;)I");
        registerIdentityCall("net/minecraft/nbt/CompoundTag", "getLong", "(Ljava/lang/String;)J");
        registerIdentityCall("net/minecraft/nbt/CompoundTag", "getDouble", "(Ljava/lang/String;)D");

        // String.substring(str, begin, end)：sink 在 arg[0] 时，从当前值取前缀/后缀还原完整字符串
        TABLE.registerCall("java/lang/String", "substring", "(II)Ljava/lang/String;",
            (t, args, idx, ctx) -> {
                if (idx != 0 || !(t instanceof String target)) return null;
                int begin = ((Number) ctx.eval(args.get(1))).intValue();
                Object endObj = ctx.eval(args.get(2));
                int end = endObj instanceof Number n ? n.intValue() : -1;
                if (!(args.get(0) instanceof Source src)) return null;
                Object cur = src.read(ctx.entity());
                if (!(cur instanceof String s)) return null;
                if (end >= 0 && s.length() >= end)
                    return s.substring(0, begin) + target + s.substring(end);
                if (end < 0 && s.length() >= begin)
                    return s.substring(0, begin) + target;
                return null;
            });
    }

    private static void registerArith(int op, char domain) {
        TABLE.registerOp(op, (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            return subDomain(t, o, domain);
        });
    }
    private static void registerSub(int op, char domain) {
        TABLE.registerOp(op, (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            if (idx == 0) return addDomain(t, o, domain);
            return subDomain(o, (Number) t, domain);
        });
    }
    private static void registerMul(int op, char domain) {
        TABLE.registerOp(op, (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            if (isZero(o, domain)) return null;
            return mulInvert(t, o, domain);
        });
    }

    /* 乘法求逆：浮点用实数除法；整数(I/J)在模 2^n 环上——乘奇数常量可逆(乘其模逆元)，
       乘偶数常量仅当整除时退化为除法，否则不可逆返回 null。修正了整数乘以整除求逆的错误。 */
    private static Object mulInvert(Object t, Number o, char d) {
        switch (d) {
            case 'F' -> { float f = o.floatValue(); return f == 0f ? null : Float.valueOf(((Number) t).floatValue() / f); }
            case 'D' -> { double db = o.doubleValue(); return db == 0d ? null : Double.valueOf(((Number) t).doubleValue() / db); }
            case 'I' -> {
                int c = o.intValue(), ti = ((Number) t).intValue();
                if ((c & 1) != 0) return Integer.valueOf(ti * modInverse32(c));   // 奇数：模逆元
                return c != 0 && ti % c == 0 ? Integer.valueOf(ti / c) : null;     // 偶数：仅整除可逆
            }
            case 'J' -> {
                long c = o.longValue(), tl = ((Number) t).longValue();
                if ((c & 1L) != 0L) return Long.valueOf(tl * modInverse64(c));
                return c != 0 && tl % c == 0 ? Long.valueOf(tl / c) : null;
            }
            default -> { return null; }
        }
    }

    //奇数 a 模 2^32 的逆元(Newton 迭代，每轮翻倍正确低位数)
    private static int modInverse32(int a) {
        int x = a;
        for (int i = 0; i < 5; i++) x *= 2 - a * x;
        return x;
    }

    //奇数 a 模 2^64 的逆元
    private static long modInverse64(long a) {
        long x = a;
        for (int i = 0; i < 6; i++) x *= 2 - a * x;
        return x;
    }

    /* Base64 解码的逆：产出能被「同一个解码器实例」还原成 target 的字符串。
       逐个尝试 url/basic/mime × 有无 padding，用真实解码器验证 round-trip，命中即返回，保证正确。 */
    private static String base64EncodeMatching(List<Expr> args, EvalContext ctx, byte[] target) {
        try {
            Object dec = evaluate(args.get(0), ctx);
            Base64.Encoder[] cands = {
                Base64.getUrlEncoder().withoutPadding(), Base64.getUrlEncoder(),
                Base64.getEncoder().withoutPadding(), Base64.getEncoder(),
                Base64.getMimeEncoder().withoutPadding(), Base64.getMimeEncoder()
            };
            if (dec instanceof Base64.Decoder decoder) {
                for (Base64.Encoder e : cands) {
                    String s = e.encodeToString(target);
                    try { if (Arrays.equals(decoder.decode(s), target)) return s; } catch (Throwable ignored) {}
                }
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(target);   // 兜底
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            return null;
        }
    }
    private static void registerDiv(int op, char domain) {
        TABLE.registerOp(op, (t, args, idx, ctx) -> {
            Number o = num(ctx.eval(args.get(1 - idx))); if (o == null) return null;
            if (idx == 0) return mulDomain(t, o, domain);
            if (isZero((Number) t, domain)) return null;
            return divDomain(o, (Number) t, domain);
        });
    }
    private static void registerUnaryNeg(int op, char domain) {
        TABLE.registerOp(op, (t, args, idx, ctx) -> negDomain((Number) t, domain));
    }
    private static void registerIdentityCall(String owner, String name, String desc) {
        TABLE.registerCall(owner, name, desc, (t, args, idx, ctx) -> t);
    }

    private static Object addDomain(Object t, Number o, char d) {
        return switch (d) {
            case 'I' -> Integer.valueOf(((Number) t).intValue() + o.intValue());
            case 'J' -> Long.valueOf(((Number) t).longValue() + o.longValue());
            case 'F' -> Float.valueOf(((Number) t).floatValue() + o.floatValue());
            case 'D' -> Double.valueOf(((Number) t).doubleValue() + o.doubleValue());
            default -> null;
        };
    }
    private static Object subDomain(Object t, Number o, char d) {
        return switch (d) {
            case 'I' -> Integer.valueOf(((Number) t).intValue() - o.intValue());
            case 'J' -> Long.valueOf(((Number) t).longValue() - o.longValue());
            case 'F' -> Float.valueOf(((Number) t).floatValue() - o.floatValue());
            case 'D' -> Double.valueOf(((Number) t).doubleValue() - o.doubleValue());
            default -> null;
        };
    }
    private static Object mulDomain(Object t, Number o, char d) {
        return switch (d) {
            case 'I' -> Integer.valueOf(((Number) t).intValue() * o.intValue());
            case 'J' -> Long.valueOf(((Number) t).longValue() * o.longValue());
            case 'F' -> Float.valueOf(((Number) t).floatValue() * o.floatValue());
            case 'D' -> Double.valueOf(((Number) t).doubleValue() * o.doubleValue());
            default -> null;
        };
    }
    private static Object divDomain(Object t, Number o, char d) {
        return switch (d) {
            case 'I' -> Integer.valueOf(((Number) t).intValue() / o.intValue());
            case 'J' -> Long.valueOf(((Number) t).longValue() / o.longValue());
            case 'F' -> Float.valueOf(((Number) t).floatValue() / o.floatValue());
            case 'D' -> Double.valueOf(((Number) t).doubleValue() / o.doubleValue());
            default -> null;
        };
    }
    private static Object negDomain(Number t, char d) {
        return switch (d) {
            case 'I' -> Integer.valueOf(-t.intValue());
            case 'J' -> Long.valueOf(-t.longValue());
            case 'F' -> Float.valueOf(-t.floatValue());
            case 'D' -> Double.valueOf(-t.doubleValue());
            default -> null;
        };
    }
    private static boolean isZero(Number n, char d) {
        return switch (d) {
            case 'I' -> n.intValue() == 0;
            case 'J' -> n.longValue() == 0L;
            case 'F' -> n.floatValue() == 0f;
            case 'D' -> n.doubleValue() == 0d;
            default -> false;
        };
    }

    private static Number num(Object v) { return v instanceof Number n ? n : null; }

    /* ==================== execOp / invokeCall ==================== */

    private static Object execOp(int op, List<Object> a) {
        try {
            return switch (op) {
                case Opcodes.IADD -> ((Number) a.get(0)).intValue() + ((Number) a.get(1)).intValue();
                case Opcodes.ISUB -> ((Number) a.get(0)).intValue() - ((Number) a.get(1)).intValue();
                case Opcodes.IMUL -> ((Number) a.get(0)).intValue() * ((Number) a.get(1)).intValue();
                case Opcodes.IDIV -> ((Number) a.get(0)).intValue() / ((Number) a.get(1)).intValue();
                case Opcodes.IREM -> ((Number) a.get(0)).intValue() % ((Number) a.get(1)).intValue();
                case Opcodes.LADD -> ((Number) a.get(0)).longValue() + ((Number) a.get(1)).longValue();
                case Opcodes.LSUB -> ((Number) a.get(0)).longValue() - ((Number) a.get(1)).longValue();
                case Opcodes.LMUL -> ((Number) a.get(0)).longValue() * ((Number) a.get(1)).longValue();
                case Opcodes.LDIV -> ((Number) a.get(0)).longValue() / ((Number) a.get(1)).longValue();
                case Opcodes.LREM -> ((Number) a.get(0)).longValue() % ((Number) a.get(1)).longValue();
                case Opcodes.FADD -> ((Number) a.get(0)).floatValue() + ((Number) a.get(1)).floatValue();
                case Opcodes.FSUB -> ((Number) a.get(0)).floatValue() - ((Number) a.get(1)).floatValue();
                case Opcodes.FMUL -> ((Number) a.get(0)).floatValue() * ((Number) a.get(1)).floatValue();
                case Opcodes.FDIV -> ((Number) a.get(0)).floatValue() / ((Number) a.get(1)).floatValue();
                case Opcodes.DADD -> ((Number) a.get(0)).doubleValue() + ((Number) a.get(1)).doubleValue();
                case Opcodes.DSUB -> ((Number) a.get(0)).doubleValue() - ((Number) a.get(1)).doubleValue();
                case Opcodes.DMUL -> ((Number) a.get(0)).doubleValue() * ((Number) a.get(1)).doubleValue();
                case Opcodes.DDIV -> ((Number) a.get(0)).doubleValue() / ((Number) a.get(1)).doubleValue();
                case Opcodes.INEG -> -((Number) a.get(0)).intValue();
                case Opcodes.LNEG -> -((Number) a.get(0)).longValue();
                case Opcodes.FNEG -> -((Number) a.get(0)).floatValue();
                case Opcodes.DNEG -> -((Number) a.get(0)).doubleValue();
                case Opcodes.IXOR -> ((Number) a.get(0)).intValue() ^ ((Number) a.get(1)).intValue();
                case Opcodes.LXOR -> ((Number) a.get(0)).longValue() ^ ((Number) a.get(1)).longValue();
                case Opcodes.IAND -> ((Number) a.get(0)).intValue() & ((Number) a.get(1)).intValue();
                case Opcodes.LAND -> ((Number) a.get(0)).longValue() & ((Number) a.get(1)).longValue();
                case Opcodes.IOR  -> ((Number) a.get(0)).intValue() | ((Number) a.get(1)).intValue();
                case Opcodes.LOR  -> ((Number) a.get(0)).longValue() | ((Number) a.get(1)).longValue();
                case Opcodes.ISHL -> ((Number) a.get(0)).intValue() << ((Number) a.get(1)).intValue();
                case Opcodes.LSHL -> ((Number) a.get(0)).longValue() << ((Number) a.get(1)).intValue();
                case Opcodes.ISHR -> ((Number) a.get(0)).intValue() >> ((Number) a.get(1)).intValue();
                case Opcodes.LSHR -> ((Number) a.get(0)).longValue() >> ((Number) a.get(1)).intValue();
                case Opcodes.IUSHR -> ((Number) a.get(0)).intValue() >>> ((Number) a.get(1)).intValue();
                case Opcodes.LUSHR -> ((Number) a.get(0)).longValue() >>> ((Number) a.get(1)).intValue();
                case Opcodes.I2F -> (float) ((Number) a.get(0)).intValue();
                case Opcodes.I2L -> (long)  ((Number) a.get(0)).intValue();
                case Opcodes.I2D -> (double)((Number) a.get(0)).intValue();
                case Opcodes.I2B -> (int)(byte) ((Number) a.get(0)).intValue();
                case Opcodes.I2C -> (int)(char) ((Number) a.get(0)).intValue();
                case Opcodes.I2S -> (int)(short) ((Number) a.get(0)).intValue();
                case Opcodes.L2I -> (int)   ((Number) a.get(0)).longValue();
                case Opcodes.L2F -> (float) ((Number) a.get(0)).longValue();
                case Opcodes.L2D -> (double)((Number) a.get(0)).longValue();
                case Opcodes.F2I -> (int)   ((Number) a.get(0)).floatValue();
                case Opcodes.F2L -> (long)  ((Number) a.get(0)).floatValue();
                case Opcodes.F2D -> (double)((Number) a.get(0)).floatValue();
                case Opcodes.D2I -> (int)   ((Number) a.get(0)).doubleValue();
                case Opcodes.D2L -> (long)  ((Number) a.get(0)).doubleValue();
                case Opcodes.D2F -> (float) ((Number) a.get(0)).doubleValue();
                default -> null;
            };
        } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
    }

    private static Object invokeCall(Call call, EvalContext ctx) {
        try {
            Class<?> owner = loadClass(call.owner());
            if (owner == null) return null;
            Type[] argTypes = Type.getArgumentTypes(call.desc());
            boolean hasRecv = call.args().size() > argTypes.length;
            int start = hasRecv ? 1 : 0;
            Object recv = null;
            if (hasRecv) {
                recv = evaluate(call.args().get(0), ctx);
                if (recv == null) return null;
            }
            Class<?>[] pts = new Class<?>[argTypes.length];
            Object[] pvs = new Object[argTypes.length];
            for (int i = 0; i < argTypes.length; i++) {
                pts[i] = asmTypeToClass(argTypes[i]);
                if (pts[i] == null) return null;
                Object v = evaluate(call.args().get(start + i), ctx);
                pvs[i] = v;
            }
            Class<?> dispatchOwner = recv == null ? owner : recv.getClass();
            Method m = findMethod(dispatchOwner, call.name(), pts, pvs);
            if (m == null && dispatchOwner != owner) m = findMethod(owner, call.name(), pts, pvs);
            if (m == null) return null;
            m.setAccessible(true);
            Class<?>[] actualTypes = m.getParameterTypes();
            for (int i = 0; i < pvs.length; i++) pvs[i] = coerceArg(pvs[i], actualTypes[i]);
            return m.invoke(recv, pvs);
        } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
    }

    public enum InvokeFailed { INSTANCE }
    public static final Object INVOKE_FAILED = InvokeFailed.INSTANCE;

    /* 公开版本：失败哨兵返回 INVOKE_FAILED，供写入侧 capability 读写复用 */
    public static Object invokeCompatibleSafely(Object receiver, String name, Object... args) {
        return invokeCompatible(receiver, name, args);
    }

    private static Object invokeCompatible(Object receiver, String name, Object... args) {
        if (receiver == null) return InvokeFailed.INSTANCE;
        try {
            Method method = findMethodByRuntimeArgs(receiver.getClass(), name, args);
            if (method == null) return InvokeFailed.INSTANCE;
            method.setAccessible(true);
            Class<?>[] types = method.getParameterTypes();
            Object[] coerced = new Object[args.length];
            for (int i = 0; i < args.length; i++) coerced[i] = coerceArg(args[i], types[i]);
            return method.invoke(receiver, coerced);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return InvokeFailed.INSTANCE;
        }
    }

    private static Object unwrapOptionalContent(Object container) {
        if (container == null) return null;
        try {
            if (container instanceof Optional<?> optional) return optional.orElse(null);
            try {
                Method resolve = container.getClass().getMethod("resolve");
                Object resolved = resolve.invoke(container);
                if (resolved instanceof Optional<?> optional) return optional.orElse(null);
            } catch (NoSuchMethodException ignored) {}
            try {
                Method orElse = container.getClass().getMethod("orElse", Object.class);
                return orElse.invoke(container, new Object[] { null });
            } catch (NoSuchMethodException ignored) {}
            return null;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    /* ==================== 外部扫描：isAlive/isDeadOrDying 逆推真实血量 ==================== */

    /*
     * 通用数据流逆推骨架：从指定方法的指令帧提取血量表达式并生成 AnalysisResult。
     * getHealth 与外部扫描各自只传入 extractor（从 MethodNode + 跑完的 Frame[] 中挑出 Expr），骨架完成其余公共部分。
     */
    private static AnalysisResult analyzeForHealthExpr(Class<?> owner, String name, String desc,
                                                        java.util.function.BiFunction<MethodNode, Frame<TaintValue>[], Expr> extractor) {
        if (owner == null || owner.getClassLoader() == null) return null;
        try {
            byte[] bytes = classBytes(owner);
            if (bytes == null) return null;
            String ownerInternal = internalName(owner);
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
            MethodNode mn = null;
            for (MethodNode m : cn.methods) {
                if (m.desc.equals(desc) && m.name.equals(name)) { mn = m; break; }
            }
            if (mn == null || mn.instructions.size() == 0) return null;

            AnalysisCtx ctx = new AnalysisCtx(DEFAULT_MAX_DEPTH);
            TaintInterpreter interp = new TaintInterpreter(ctx, 0, ownerInternal, mn, null);
            Analyzer<TaintValue> analyzer = new Analyzer<>(interp);
            Frame<TaintValue>[] frames = analyzer.analyze(ownerInternal, mn);

            Expr raw = extractor.apply(mn, frames);
            if (raw == null || raw instanceof UnknownExpr) return null;
            Expr stripped = stripEcaHealthWrappers(raw);
            if (stripped == null || stripped instanceof UnknownExpr) return null;
            return AnalysisResult.of(stripped, owner);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
        return null;
    }

    //按虚调用实际生效的定义方法分析，避免遗漏基类转换结果或混入被覆写的实现
    private static AnalysisResult analyzeUnifiedExternalScan(Class<?> entityClass) {
        List<AnalysisResult> candidates = new ArrayList<>();
        List<String> scannedMethods = new ArrayList<>();
        for (McMethod method : new McMethod[]{IS_ALIVE, IS_DEAD_OR_DYING}) {
            ClassAndMethod target = findMethodOwner(entityClass, method);
            if (target == null) continue;
            String label = target.owner().getName() + "#" + target.name() + method.desc();
            scannedMethods.add(label);
            AnalysisResult result = safeExternalEntry(entityClass, label,
                    () -> analyzeExternalComparisonMethod(target.owner(), target.name(), method.desc()));
            if (result != null && !result.isEmpty() && !result.sources.isEmpty()) candidates.add(result);
        }
        for (McMethod method : new McMethod[]{HURT, ACTUALLY_HURT}) {
            ClassAndMethod target = findMethodOwner(entityClass, method);
            if (target == null) continue;
            String label = target.owner().getName() + "#" + target.name() + method.desc();
            scannedMethods.add(label);
            AnalysisResult result = safeExternalEntry(entityClass, label,
                    () -> analyzeDamageWriteMethod(target.owner(), target.name(), target.name(), method.desc()));
            if (result != null && !result.isEmpty() && !result.sources.isEmpty()) candidates.add(result);
        }
        AnalysisResult combined = combineExternalScanCandidates(entityClass, candidates);
        if (EXTERNAL_SCAN_DIAG_DUMPED.add(entityClass)) {
            EcaLogger.info("[ExternalScan] entity={} methods={} candidates={} sources={}",
                    entityClass.getName(), scannedMethods, candidates.size(),
                    combined != null ? combined.sources.size() : 0);
        }
        return combined;
    }

    /* 隔离单个扫描入口的异常：不隔离时第一个抛出的入口会连带废掉其余入口，
       整条通道对外只表现为"无结果"，无从判断是分析不出来还是中途炸了。
       VirtualMachineError 按全局约定仍向上抛，由提交侧统一记录栈。 */
    private static AnalysisResult safeExternalEntry(Class<?> entityClass, String label,
                                                    Supplier<AnalysisResult> analysis) {
        try {
            return analysis.get();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            if (EXTERNAL_ENTRY_FAILURE_DUMPED.add(entityClass.getName() + "|" + label)) {
                EcaLogger.info("[ExternalScan] entry threw entity={} entry={} type={} msg={}",
                        entityClass.getName(), label, t.getClass().getName(), t.getMessage());
            }
            return null;
        }
    }

    private static AnalysisResult analyzeExternalComparisonMethod(Class<?> owner, String name, String desc) {
        AnalysisCtx context = new AnalysisCtx(DEFAULT_MAX_DEPTH);
        Expr expression = analyzeComparisonMethod(owner, name, desc, null, context, 0);
        if (expression == null || expression instanceof UnknownExpr) return null;
        Expr stripped = stripEcaHealthWrappers(expression);
        if (stripped == null || stripped instanceof UnknownExpr) return null;
        return AnalysisResult.of(stripped, owner);
    }

    /* ==================== 外部扫描：布尔死亡门控识别 ====================
       部分实体的生死状态由编码布尔字段控制，无法通过浮点比较和写入点分析定位。
       此处识别 isDeadOrDying 或 isAlive 中的字段解码调用及其配对编码器，返回门控字段、编解码器和死亡值。
       分析仅检查各方法自身指令，避免 isAlive 与 isDeadOrDying 相互调用形成递归。 */

    public record DeathGate(Field field, Method encoder, Method decoder, boolean deathValue) {}

    private static final DeathGate NO_DEATH_GATE = new DeathGate(null, null, null, false);
    private static final Map<Class<?>, DeathGate> DEATH_GATE_CACHE = new ConcurrentHashMap<>();

    // 分析实体的死亡门控；无门控返回 null(以 NO_DEATH_GATE 哨兵缓存，避免反复分析)。
    public static DeathGate analyzeDeathGate(Class<?> entityClass) {
        if (entityClass == null) return null;
        DeathGate cached = DEATH_GATE_CACHE.get(entityClass);
        if (cached != null) return cached == NO_DEATH_GATE ? null : cached;
        DeathGate gate = detectDeathGate(entityClass);
        DEATH_GATE_CACHE.put(entityClass, gate != null ? gate : NO_DEATH_GATE);
        return gate;
    }

    private static DeathGate detectDeathGate(Class<?> entityClass) {
        // isDeadOrDying 返回 true 即死 → 死亡值 true；isAlive 返回 true 即活 → 死亡值 false
        DeathGate gate = detectDeathGateMethod(entityClass, IS_DEAD_OR_DYING, true);
        if (gate != null) return gate;
        return detectDeathGateMethod(entityClass, IS_ALIVE, false);
    }

    private static DeathGate detectDeathGateMethod(Class<?> entityClass, McMethod method, boolean deathValue) {
        ClassAndMethod target = findMethodOwner(entityClass, method);
        if (target == null) return null;
        try {
            byte[] bytes = classBytes(target.owner());
            if (bytes == null) return null;
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            MethodNode mn = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals(target.name()) && m.desc.equals(method.desc())) { mn = m; break; }
            }
            if (mn == null || mn.instructions.size() == 0) return null;
            for (AbstractInsnNode in : mn.instructions) {
                if (in.getOpcode() != Opcodes.INVOKESTATIC) continue;
                MethodInsnNode call = (MethodInsnNode) in;
                Type ret = Type.getReturnType(call.desc);
                Type[] args = Type.getArgumentTypes(call.desc);
                // 解码器签名：单个对象入参(编码值) -> boolean
                if (ret.getSort() != Type.BOOLEAN || args.length != 1 || args[0].getSort() != Type.OBJECT) continue;
                FieldInsnNode feeder = findFeedingGetField(in);
                if (feeder == null || Type.getType(feeder.desc).getSort() != Type.OBJECT) continue;
                Class<?> fieldOwner = loadClass(feeder.owner);
                Class<?> decoderOwner = loadClass(call.owner);
                if (fieldOwner == null || decoderOwner == null) continue;
                Field field = findFieldInHierarchy(fieldOwner, feeder.name);
                if (field == null) continue;
                Method decoder = findStaticUnary(decoderOwner, call.name, typeToClass(args[0]));
                // 必须存在编解码对偶 encoder((decoder返回) -> (decoder入参))，否则视为普通布尔调用而非门控(滤除原版 this.dead 等误报)
                Method encoder = findCodecEncoder(decoderOwner, typeToClass(ret), typeToClass(args[0]));
                if (decoder == null || encoder == null) continue;
                field.setAccessible(true);
                return new DeathGate(field, encoder, decoder, deathValue);
            }
        } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
        return null;
    }

    // 从 INVOKESTATIC 向前找喂入其参数的 GETFIELD(只越过 ALOAD/DUP/CHECKCAST 等无害指令)，限定接收者为 this(ALOAD_0)。
    private static FieldInsnNode findFeedingGetField(AbstractInsnNode from) {
        AbstractInsnNode cur = from.getPrevious();
        int hops = 0;
        while (cur != null && hops++ < 8) {
            int op = cur.getOpcode();
            if (op == Opcodes.GETFIELD) return (FieldInsnNode) cur;
            if (op == Opcodes.ALOAD || op == Opcodes.DUP || op == Opcodes.DUP_X1
                    || op == Opcodes.DUP2 || op == Opcodes.CHECKCAST || op == -1) {
                cur = cur.getPrevious();
                continue;
            }
            return null;
        }
        return null;
    }

    // owner 内静态单参方法，参数类型恰为 paramType。
    private static Method findStaticUnary(Class<?> owner, String name, Class<?> paramType) {
        for (Method m : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !m.getName().equals(name) || m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] == paramType) { m.setAccessible(true); return m; }
        }
        return null;
    }

    // owner 内静态编解码对偶的编码器：参数类型=解码器返回、返回类型=解码器入参(如 decodeBool(String)Z 配 encodeBool(Z)String)。
    private static Method findCodecEncoder(Class<?> owner, Class<?> paramType, Class<?> returnType) {
        for (Method m : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] == paramType && m.getReturnType() == returnType) { m.setAccessible(true); return m; }
        }
        return null;
    }

    private static Class<?> typeToClass(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> boolean.class;
            case Type.BYTE -> byte.class;
            case Type.CHAR -> char.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.LONG -> long.class;
            case Type.FLOAT -> float.class;
            case Type.DOUBLE -> double.class;
            default -> loadClass(t.getInternalName());
        };
    }

    private static Expr analyzeComparisonMethod(Class<?> owner, String name, String desc,
                                                TaintValue[] seedLocals, AnalysisCtx context, int depth) {
        if (owner == null || owner.getClassLoader() == null || depth >= context.maxDepth) return null;
        String cacheKey = owner.getName().replace('.', '/') + "#" + name + "#" + desc + "#comparisons";
        if (!context.inflight.add(cacheKey)) return new UnknownExpr("recursive-cycle-comparisons");
        try {
            byte[] bytes = classBytes(owner);
            if (bytes == null) return null;
            ClassNode classNode = new ClassNode();
            new ClassReader(bytes).accept(classNode, ClassReader.EXPAND_FRAMES);
            MethodNode method = null;
            for (MethodNode candidate : classNode.methods) {
                if (candidate.name.equals(name) && candidate.desc.equals(desc)) {
                    method = candidate;
                    break;
                }
            }
            if (method == null || method.instructions.size() == 0) return null;

            String ownerInternal = internalName(owner);
            TaintInterpreter interpreter = new TaintInterpreter(
                    context, depth, ownerInternal, method, seedLocals);
            Analyzer<TaintValue> analyzer = new Analyzer<>(interpreter);
            Frame<TaintValue>[] frames = analyzer.analyze(ownerInternal, method);
            List<Expr> expressions = new ArrayList<>();
            int index = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                Frame<TaintValue> frame = frames[index++];
                collectComparisonOperands(expressions, frame, instruction.getOpcode());
                if (!(instruction instanceof MethodInsnNode call)
                        || Type.getReturnType(call.desc) != Type.VOID_TYPE
                        || call.name.startsWith("<") || frame == null) continue;
                List<Expr> arguments = invokeValueExprs(call, frame);
                Class<?> referencedOwner = loadClass(call.owner);
                Class<?> targetOwner = referencedOwner == null ? null
                        : resolveInvocationOwner(referencedOwner, call.name, call.desc);
                if (targetOwner != owner || !isEntityControlCall(call, arguments)
                        || context.inlineBudget <= 0) continue;
                TaintValue[] nestedLocals = seedCallLocals(call, arguments, false);
                if (nestedLocals == null) continue;
                context.inlineBudget--;
                Expr nested = analyzeComparisonMethod(
                        targetOwner, call.name, call.desc, nestedLocals, context, depth + 1);
                if (nested != null && !(nested instanceof UnknownExpr)) addUniqueExpr(expressions, nested);
            }
            if (expressions.isEmpty()) return null;
            return expressions.size() == 1 ? expressions.get(0) : new Choice(List.copyOf(expressions));
        } catch (Throwable throwable) {
            if (throwable instanceof VirtualMachineError error) throw error;
            return null;
        } finally {
            context.inflight.remove(cacheKey);
        }
    }

    private static boolean isEntityControlCall(MethodInsnNode call, List<Expr> arguments) {
        if (arguments.isEmpty()) return false;
        if (call.getOpcode() != Opcodes.INVOKESTATIC) return arguments.get(0) == EntityParamMarker.I;
        for (Expr argument : arguments) if (argument == EntityParamMarker.I) return true;
        return false;
    }

    private static AnalysisResult combineExternalScanCandidates(Class<?> entityClass, List<AnalysisResult> candidates) {
        List<Expr> alternatives = new ArrayList<>();
        for (AnalysisResult candidate : candidates) {
            if (candidate == null || candidate.isEmpty() || candidate.sources.isEmpty()) continue;
            addUniqueExpr(alternatives, candidate.returnExpr);
        }
        if (alternatives.isEmpty()) return null;
        Expr combined = alternatives.size() == 1 ? alternatives.get(0) : new Choice(List.copyOf(alternatives));
        List<Source> sources = new ArrayList<>(collectSources(combined));
        sources.sort(Comparator.comparingInt(HealthDataflowAnalyzer::externalSourcePriority)
                .thenComparing(source -> source.label));
        return new AnalysisResult(combined, List.copyOf(sources), entityClass);
    }

    /* ==================== 有效血量模型 ====================
       getHealth 与实际存储解耦时，实体的生死判定仍会读取该存储并计算有效血量。
       从比较指令中提取这一表达式后，可将其用于观测和存储值反演。 */

    /* readExpr 为含 storage 的有效血量表达式；storage 是其中真正可写的存储源。 */
    public record EffectiveHealthModel(Expr readExpr, Source storage) {}

    private static final Map<Class<?>, EffectiveHealthModel> EFFECTIVE_MODEL_CACHE = new ConcurrentHashMap<>();
    /* 分析失败按候选签名缓存，而不对实体类永久缓存；后续新增候选时仍可重新分析。 */
    private static final Map<Class<?>, String> EFFECTIVE_MODEL_MISSES = new ConcurrentHashMap<>();
    /* 写入失败的存储，后续分析不再将其作为候选。 */
    private static final Map<Class<?>, Set<String>> EFFECTIVE_MODEL_REJECTED = new ConcurrentHashMap<>();

    /* 仅查模型缓存、绝不触发分析(供运行期非阻塞查询)。扫全类比较指令可达数秒，必须在后台预填。 */
    public static EffectiveHealthModel peekEffectiveHealthModel(Class<?> entityClass) {
        return entityClass == null ? null : EFFECTIVE_MODEL_CACHE.get(entityClass);
    }

    /* 是否已有任一段比较表达式缓存。有则建模只剩遍历与打分，调用方可当场完成而无需转入后台。 */
    public static boolean hasComparisonCache(Class<?> entityClass) {
        return entityClass != null
                && (PRIORITY_COMPARISON_CACHE.containsKey(entityClass)
                    || FULL_COMPARISON_CACHE.containsKey(entityClass));
    }

    /* 判断当前候选集合是否已经分析失败，供调用方避免重复提交。
       候选为空时仍可能从比较表达式中提取存储源，因此同样按签名缓存。 */
    public static boolean isEffectiveModelMiss(Class<?> entityClass, List<Source> candidates) {
        if (entityClass == null || candidates == null) return true;
        return candidateSignature(candidates).equals(EFFECTIVE_MODEL_MISSES.get(entityClass));
    }

    public static String candidateSignature(List<Source> candidates) {
        List<String> labels = new ArrayList<>(candidates.size());
        for (Source source : candidates) labels.add(source.label);
        Collections.sort(labels);
        return String.join("|", labels);
    }

    /* 从候选存储源推导有效血量模型；无法确定时返回 null。
       该分析需要扫描类指令，必须在后台线程调用。 */
    public static EffectiveHealthModel resolveEffectiveHealthModel(Class<?> entityClass, List<Source> candidates) {
        return resolveEffectiveHealthModel(entityClass, candidates, false);
    }

    /* 仅使用已缓存的比较表达式建模，不触发新的扫描，可在服务器线程直接调用。
       未命中时不记录失败签名：这只表示扫描尚未完成，而非该批候选分析不出模型。 */
    public static EffectiveHealthModel resolveCachedEffectiveHealthModel(Class<?> entityClass,
                                                                         List<Source> candidates) {
        return resolveEffectiveHealthModel(entityClass, candidates, true);
    }

    private static EffectiveHealthModel resolveEffectiveHealthModel(Class<?> entityClass, List<Source> candidates,
                                                                    boolean cachedOnly) {
        if (entityClass == null) return null;
        // 正向缓存先于候选检查：模型一旦定下就长期有效，而候选证据会在校验成功后被清空
        EffectiveHealthModel cached = EFFECTIVE_MODEL_CACHE.get(entityClass);
        if (cached != null) return cached;
        if (candidates == null) return null;
        String signature = candidateSignature(candidates);
        if (signature.equals(EFFECTIVE_MODEL_MISSES.get(entityClass))) return null;
        EffectiveHealthModel model = null;
        try {
            model = computeEffectiveHealthModel(entityClass, candidates, cachedOnly);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[EffectiveHealth] model analysis threw entity={} type={} msg={}",
                    entityClass.getName(), t.getClass().getName(), t.getMessage());
        }
        if (model != null) {
            EFFECTIVE_MODEL_CACHE.put(entityClass, model);
            EFFECTIVE_MODEL_MISSES.remove(entityClass);
            EcaLogger.info("[EffectiveHealth] model entity={} storage={} readExpr={}",
                    entityClass.getName(), model.storage().label, model.readExpr());
        } else if (!cachedOnly) {
            EFFECTIVE_MODEL_MISSES.put(entityClass, signature);
            EcaLogger.info("[EffectiveHealth] no model entity={} candidates={} signature={}",
                    entityClass.getName(), candidates.size(), signature);
        }
        return model;
    }

    /* 先扫外部扫描所用的同一组方法，未得到模型再回退到全类扫描。
       优先段只分析含浮点比较指令的方法：血量判定必然表现为浮点比较，
       而 getter、注册逻辑等方法一条都没有，据此可跳过大部分方法及其依赖类字节码。 */
    private static EffectiveHealthModel computeEffectiveHealthModel(Class<?> entityClass, List<Source> evidence,
                                                                    boolean cachedOnly) {
        List<Expr> priority = cachedOnly
                ? PRIORITY_COMPARISON_CACHE.get(entityClass) : comparisonsOf(entityClass, true);
        if (priority != null) {
            EffectiveHealthModel model = selectEffectiveModel(entityClass, evidence, priority, "priority");
            if (model != null) return model;
        }
        List<Expr> full = cachedOnly
                ? FULL_COMPARISON_CACHE.get(entityClass) : comparisonsOf(entityClass, false);
        return full == null ? null : selectEffectiveModel(entityClass, evidence, full, "full");
    }

    /* 比较表达式与证据无关，只取决于类的字节码，因此可跨次复用。
       扫描是本流程中最慢的部分，缓存后运行期只需重新匹配证据。 */
    private static final Map<Class<?>, List<Expr>> PRIORITY_COMPARISON_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<Expr>> FULL_COMPARISON_CACHE = new ConcurrentHashMap<>();

    private static List<Expr> comparisonsOf(Class<?> entityClass, boolean priorityOnly) {
        Map<Class<?>, List<Expr>> cache = priorityOnly ? PRIORITY_COMPARISON_CACHE : FULL_COMPARISON_CACHE;
        List<Expr> cached = cache.get(entityClass);
        if (cached != null) return cached;
        List<Expr> scanned = collectClassComparisons(entityClass, priorityOnly);
        cache.put(entityClass, scanned);
        return scanned;
    }

    /* 预热期只填充优先段比较表达式缓存。此时没有写入记录，无法确立模型，
       但扫描结果可复用，运行期取得证据后即可直接匹配。
       全类扫描要慢数倍，留到优先段确实推不出模型时再由后台补做。 */
    public static void prewarmClassComparisons(Class<?> entityClass) {
        if (entityClass == null) return;
        comparisonsOf(entityClass, true);
    }

    /* 指令级筛选，不跑数据流，开销远低于逐方法完整分析。
       浮点比较是血量判定的必要特征，据此可在优先段排除绝大多数无关方法。 */
    private static boolean hasFloatComparison(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.FCMPL || opcode == Opcodes.FCMPG
                    || opcode == Opcodes.DCMPL || opcode == Opcodes.DCMPG) return true;
        }
        return false;
    }

    private static EffectiveHealthModel selectEffectiveModel(Class<?> entityClass, List<Source> evidence,
                                                             List<Expr> comparisons, String stage) {
        Set<String> rejected = EFFECTIVE_MODEL_REJECTED.getOrDefault(entityClass, Set.of());
        EffectiveHealthModel best = null;
        int bestScore = Integer.MIN_VALUE;
        int matched = 0;
        for (Expr expr : comparisons) {
            /* 候选由外部记录和表达式内提取的存储源共同组成。
               直接提取可使模型分析不依赖其他通道的完成时间。 */
            if (!isHealthShapedExpr(expr)) continue;
            /* 候选只取有写入记录的存储。校验读的就是本模型的表达式，若存储也从同一表达式中提取，
               写入与校验会构成闭环而必然通过；写入记录来自实际写入尝试，与表达式无关，可打破该闭环。 */
            for (Source candidate : evidence) {
                if (!isStorageSource(candidate)) continue;
                if (rejected.contains(candidate.canonicalKey())) continue;
                // 表达式即存储本身时两者同向，由原通道处理，无需模型
                if (sameSource(expr, candidate) || !containsSink(expr, candidate)) continue;
                matched++;
                int score = effectiveModelScore(expr);
                if (score > bestScore) {
                    bestScore = score;
                    best = new EffectiveHealthModel(expr, candidate);
                }
            }
        }
        EcaLogger.info("[EffectiveHealth] scan entity={} stage={} comparisons={} matched={}",
                entityClass.getName(), stage, comparisons.size(), matched);
        // 未找到匹配项时输出样本，用于区分存储未参与比较和调用未内联两种情况。
        // 优先段未命中属于正常回退，只在全类扫描仍无结果时输出。
        if (matched == 0 && "full".equals(stage)) dumpComparisonSamples(entityClass, comparisons);
        if (best == null) return null;
        return new EffectiveHealthModel(pruneChoicesTo(best.readExpr(), best.storage()), best.storage());
    }

    /* 将 Choice 限定到包含目标存储的分支。
       分析期的分支合并会并列存储读取与常数分支；选择常数分支会使锚点与存储失去关联。
       反解则会主动选取含 sink 的分支，两者走法不一致会导致写入正确也无法通过校验。
       不含存储的 Choice 保持原样，其取值不影响观测结果。 */
    private static Expr pruneChoicesTo(Expr expr, Source storage) {
        if (expr instanceof Choice choice) {
            List<Expr> kept = new ArrayList<>();
            for (Expr alternative : choice.alternatives()) {
                if (containsSink(alternative, storage)) kept.add(pruneChoicesTo(alternative, storage));
            }
            if (kept.isEmpty()) return expr;
            return kept.size() == 1 ? kept.get(0) : new Choice(List.copyOf(kept));
        }
        if (expr instanceof Op op) {
            List<Expr> args = new ArrayList<>(op.args().size());
            for (Expr arg : op.args()) args.add(pruneChoicesTo(arg, storage));
            return new Op(op.opcode(), List.copyOf(args));
        }
        if (expr instanceof Call call) {
            List<Expr> args = new ArrayList<>(call.args().size());
            for (Expr arg : call.args()) args.add(pruneChoicesTo(arg, storage));
            return new Call(call.owner(), call.name(), call.desc(), List.copyOf(args));
        }
        return expr;
    }

    private static final int COMPARISON_SAMPLE_LIMIT = 6;
    private static final int COMPARISON_SAMPLE_CHARS = 300;

    /* 优先输出含可写源的表达式，用于判断内联展开到哪一层，以及存储是否参与了比较。 */
    private static void dumpComparisonSamples(Class<?> entityClass, List<Expr> comparisons) {
        List<Expr> withSource = new ArrayList<>();
        for (Expr expr : comparisons) {
            if (containsAnySource(expr)) withSource.add(expr);
            if (withSource.size() >= COMPARISON_SAMPLE_LIMIT) break;
        }
        EcaLogger.info("[EffectiveHealth]   samples withSource={} total={}", withSource.size(), comparisons.size());
        List<Expr> samples = withSource.isEmpty() ? comparisons : withSource;
        int limit = Math.min(samples.size(), COMPARISON_SAMPLE_LIMIT);
        for (int i = 0; i < limit; i++) {
            String text = String.valueOf(samples.get(i));
            if (text.length() > COMPARISON_SAMPLE_CHARS) text = text.substring(0, COMPARISON_SAMPLE_CHARS) + "...";
            EcaLogger.info("[EffectiveHealth]   sample#{} {}", i, text);
        }
    }

    private static boolean containsAnySource(Expr e) {
        if (e instanceof Source) return true;
        if (e instanceof Op op) {
            for (Expr a : op.args()) if (containsAnySource(a)) return true;
        } else if (e instanceof Call call) {
            for (Expr a : call.args()) if (containsAnySource(a)) return true;
        } else if (e instanceof Choice choice) {
            for (Expr a : choice.alternatives()) if (containsAnySource(a)) return true;
        }
        return false;
    }

    /* 同一存储可能参与冷却、计数或阶段判断，因此需要对候选表达式评分。
       引用最大血量且结构较简单的表达式优先；已有成功写入记录的候选获得额外权重，
       但该记录不是必需条件，以免模型分析依赖其他通道。 */
    private static int effectiveModelScore(Expr expr) {
        return (referencesMaxHealth(expr) ? 100 : 0) - exprNodeCount(expr);
    }

    /* 血量由存储经算术运算得出，因此表达式须为浮点算术运算的结果。
       布尔判定、整数取值、集合判空等表达式虽然也含存储，但不表示血量。 */
    private static boolean isHealthShapedExpr(Expr expr) {
        return isFloatingPointExpr(expr) && containsArithmeticOp(expr);
    }

    private static boolean isFloatingPointExpr(Expr expr) {
        if (expr instanceof Op op) return isFloatingPointOpcode(op.opcode());
        if (expr instanceof Call call) {
            int sort = Type.getReturnType(call.desc()).getSort();
            return sort == Type.FLOAT || sort == Type.DOUBLE;
        }
        if (expr instanceof Choice choice) {
            for (Expr alternative : choice.alternatives()) {
                if (isFloatingPointExpr(alternative)) return true;
            }
        }
        return false;
    }

    private static boolean containsArithmeticOp(Expr expr) {
        if (expr instanceof Op op) {
            if (isFloatingPointOpcode(op.opcode())) return true;
            for (Expr arg : op.args()) if (containsArithmeticOp(arg)) return true;
        } else if (expr instanceof Call call) {
            for (Expr arg : call.args()) if (containsArithmeticOp(arg)) return true;
        } else if (expr instanceof Choice choice) {
            for (Expr alternative : choice.alternatives()) {
                if (containsArithmeticOp(alternative)) return true;
            }
        }
        return false;
    }

    private static boolean isFloatingPointOpcode(int opcode) {
        return opcode == Opcodes.FADD || opcode == Opcodes.FSUB || opcode == Opcodes.FMUL
                || opcode == Opcodes.FDIV || opcode == Opcodes.FREM || opcode == Opcodes.FNEG
                || opcode == Opcodes.DADD || opcode == Opcodes.DSUB || opcode == Opcodes.DMUL
                || opcode == Opcodes.DDIV || opcode == Opcodes.DREM || opcode == Opcodes.DNEG;
    }

    /* 只有存储型的源可作血量载体：常数覆写源改读不改存储，静态字段为全局共享，
       写入要么无效，要么波及同类其他实体。 */
    private static boolean isStorageSource(Source source) {
        return source instanceof SynchedDataSource || source instanceof FieldChainSource
                || source instanceof ChainedFieldSource || source instanceof CapabilityDataSource
                || source instanceof MapEntrySource || source instanceof ArrayElementSource;
    }

    /* 记录写入失败的存储并清除缓存模型。
       只清缓存会让重新分析得到相同结果并反复失败，因此需要排除该存储后再分析。 */
    public static void rejectEffectiveModel(Class<?> entityClass, EffectiveHealthModel model) {
        if (entityClass == null || model == null || model.storage() == null) return;
        EFFECTIVE_MODEL_REJECTED.computeIfAbsent(entityClass, k -> ConcurrentHashMap.newKeySet())
                .add(model.storage().canonicalKey());
        invalidateEffectiveModel(entityClass);
    }

    /* 清除未经写入确认的缓存模型，使后续分析可以使用更新后的候选集合。 */
    public static void invalidateEffectiveModel(Class<?> entityClass) {
        if (entityClass == null) return;
        EFFECTIVE_MODEL_CACHE.remove(entityClass);
        EFFECTIVE_MODEL_MISSES.remove(entityClass);
    }

    private static boolean referencesMaxHealth(Expr expr) {
        if (expr instanceof Call call) {
            if ((call.name().equals(GET_MAX_HEALTH.srg()) || call.name().equals(GET_MAX_HEALTH.mcp()))
                    && call.desc().equals(GET_MAX_HEALTH.desc())) return true;
            for (Expr arg : call.args()) if (referencesMaxHealth(arg)) return true;
        } else if (expr instanceof Op op) {
            for (Expr arg : op.args()) if (referencesMaxHealth(arg)) return true;
        } else if (expr instanceof Choice choice) {
            for (Expr alt : choice.alternatives()) if (referencesMaxHealth(alt)) return true;
        }
        return false;
    }

    private static int exprNodeCount(Expr expr) {
        if (expr instanceof Call call) {
            int n = 1;
            for (Expr arg : call.args()) n += exprNodeCount(arg);
            return n;
        }
        if (expr instanceof Op op) {
            int n = 1;
            for (Expr arg : op.args()) n += exprNodeCount(arg);
            return n;
        }
        if (expr instanceof Choice choice) {
            int n = 1;
            for (Expr alt : choice.alternatives()) n += exprNodeCount(alt);
            return n;
        }
        return 1;
    }

    /* 扫描类实例方法中的比较指令，收集与常数比较的表达式；生死判定通常表现为剩余量小于等于零。
       单个方法分析失败不影响其余方法，整体尽力而为。 */
    private static List<Expr> collectClassComparisons(Class<?> entityClass, boolean priorityOnly) {
        List<Expr> out = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        byte[] bytes = classBytes(entityClass);
        if (bytes == null) return out;
        ClassNode classNode = new ClassNode();
        new ClassReader(bytes).accept(classNode, ClassReader.EXPAND_FRAMES);
        String ownerInternal = internalName(entityClass);
        // 后台分析串行执行，单个类达到时间预算后使用已经收集的结果
        long deadline = System.nanoTime() + COMPARISON_SCAN_BUDGET_NANOS;
        boolean timedOut = false;
        for (MethodNode method : classNode.methods) {
            if (method.instructions.size() == 0 || method.name.startsWith("<")) continue;
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            if (priorityOnly && !hasFloatComparison(method)) continue;
            if (System.nanoTime() > deadline) { timedOut = true; break; }
            /* 每个方法使用独立的预算和递归检测集，避免前序方法耗尽预算或残留状态影响后续分析。 */
            AnalysisCtx ctx = new AnalysisCtx(DEFAULT_MAX_DEPTH);
            ctx.inheritedInline = true;
            try {
                TaintValue[] seed = seedMethodInputs(method.desc, false);
                TaintInterpreter interpreter = new TaintInterpreter(ctx, 0, ownerInternal, method, seed);
                Frame<TaintValue>[] frames = new Analyzer<>(interpreter).analyze(ownerInternal, method);
                int index = 0;
                for (AbstractInsnNode insn : method.instructions) {
                    collectComparisonOperands(out, frames[index++], insn.getOpcode());
                }
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                // 大型方法可能包含生死判定，分析失败时记录诊断以区分无匹配结果
                if (failures.size() < COMPARISON_FAILURE_LIMIT) {
                    failures.add(method.name + method.desc + " -> " + t.getClass().getSimpleName());
                }
            }
        }
        if (timedOut) {
            EcaLogger.info("[EffectiveHealth]   scan timed out entity={} budgetMs={} collected={}",
                    entityClass.getName(), COMPARISON_SCAN_BUDGET_NANOS / 1_000_000L, out.size());
        }
        if (!failures.isEmpty()) {
            EcaLogger.info("[EffectiveHealth]   method analysis failures entity={} count={} {}",
                    entityClass.getName(), failures.size(), failures);
        }
        return out;
    }

    private static final int COMPARISON_FAILURE_LIMIT = 8;
    private static final long COMPARISON_SCAN_BUDGET_NANOS = 15_000_000_000L;

    private static int externalSourcePriority(Source source) {
        if (source instanceof SynchedDataSource) return 0;
        if (source instanceof MapEntrySource || source instanceof CapabilityDataSource) return 1;
        if (source instanceof FieldChainSource) return 2;
        if (source instanceof MethodCallSource) return 3;
        return 4;
    }

    /* ==================== AnalysisResult + 入口 ==================== */

    private static AnalysisResult analyzeDamageWriteMethod(Class<?> owner, String srgName, String mcpName, String desc) {
        AnalysisCtx ctx = new AnalysisCtx(DEFAULT_MAX_DEPTH);
        TaintValue[] seedLocals = seedMethodInputs(desc, false);
        Expr writes = analyzeMethodWrites(owner, srgName, desc, seedLocals, ctx, 0);
        if (writes == null || writes instanceof UnknownExpr) {
            writes = analyzeMethodWrites(owner, mcpName, desc, seedLocals, ctx, 0);
        }
        if (writes == null || writes instanceof UnknownExpr) return null;
        Expr stripped = stripEcaHealthWrappers(writes);
        if (stripped == null || stripped instanceof UnknownExpr) return null;
        return AnalysisResult.of(stripped, owner);
    }

    private static Expr analyzeMethodWrites(Class<?> owner, String name, String desc,
                                            TaintValue[] seedLocals, AnalysisCtx ctx, int depth) {
        if (owner == null || owner.getClassLoader() == null || depth >= ctx.maxDepth) return null;
        String cacheKey = owner.getName().replace('.', '/') + "#" + name + "#" + desc + "#writes";
        if (!ctx.inflight.add(cacheKey)) return new UnknownExpr("recursive-cycle-writes");
        try {
            byte[] bytes = classBytes(owner);
            if (bytes == null) return null;
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
            MethodNode mn = null;
            for (MethodNode method : cn.methods) {
                if (method.name.equals(name) && method.desc.equals(desc)) {
                    mn = method;
                    break;
                }
            }
            if (mn == null || mn.instructions.size() == 0) return null;

            String ownerInternal = internalName(owner);
            TaintInterpreter interpreter = new TaintInterpreter(ctx, depth, ownerInternal, mn, seedLocals);
            Analyzer<TaintValue> analyzer = new Analyzer<>(interpreter);
            Frame<TaintValue>[] frames = analyzer.analyze(ownerInternal, mn);

            List<Expr> writes = new ArrayList<>();
            int idx = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                Expr write = extractWriteExpression(frames[idx], insn, ctx, depth);
                if (write != null && !(write instanceof UnknownExpr)) addUniqueExpr(writes, write);
                idx++;
            }
            if (writes.isEmpty()) return null;
            return writes.size() == 1 ? writes.get(0) : new Choice(List.copyOf(writes));
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        } finally {
            ctx.inflight.remove(cacheKey);
        }
    }

    private static Expr extractWriteExpression(Frame<TaintValue> frame, AbstractInsnNode insn,
                                               AnalysisCtx ctx, int depth) {
        if (frame == null) return null;
        int opcode = insn.getOpcode();
        if (insn instanceof FieldInsnNode field) {
            if (opcode == Opcodes.PUTFIELD && frame.getStackSize() >= 2) {
                Source sink = buildFieldSourceFromReceiver(
                        field, frame.getStack(frame.getStackSize() - 2).expr);
                Expr value = frame.getStack(frame.getStackSize() - 1).expr;
                return sink == null ? null : new StoreWrite(sink, value);
            }
            if (opcode == Opcodes.PUTSTATIC && frame.getStackSize() >= 1) {
                Source sink = buildStaticFieldWriteSource(field);
                return sink == null ? null
                        : new StoreWrite(sink, frame.getStack(frame.getStackSize() - 1).expr);
            }
            return null;
        }
        if (!(insn instanceof MethodInsnNode call)) return null;
        List<Expr> args = invokeValueExprs(call, frame);
        if (args.isEmpty()) return null;
        Expr known = extractKnownWriteCall(call, args);
        if (known != null) return known;
        Expr inlined = tryInlineWriteCall(call, args, ctx, depth);
        return inlined != null && !(inlined instanceof UnknownExpr) ? inlined : null;
    }

    private static Expr extractKnownWriteCall(MethodInsnNode call, List<Expr> args) {
        if (isSynchedDataSet(call) && args.size() >= 3) {
            Expr accessor = args.get(1);
            if (accessor instanceof Reference ref && ref.value() instanceof EntityDataAccessor<?> acc) {
                return new StoreWrite(new SynchedDataSource(acc, Object.class), args.get(2));
            }
        }
        if (isMapPut(call) && args.size() >= 3) {
            Expr container = args.get(0);
            Expr key = args.get(1);
            Source sink = new MapEntrySource(
                    container, key, detectKeyKind(key), mapOwnerHint(container), Object.class, call.owner);
            return new StoreWrite(sink, args.get(2));
        }
        return null;
    }

    private static Expr tryInlineWriteCall(MethodInsnNode call, List<Expr> args, AnalysisCtx ctx, int depth) {
        if (isMethodHandleInvoke(call) && !args.isEmpty()) {
            if (depth + 1 >= ctx.maxDepth || ctx.inlineBudget <= 0) return null;
            MethodTarget target = resolveMethodHandleTarget(args.get(0));
            if (target == null) return null;
            Type[] targetArgs = Type.getArgumentTypes(target.desc());
            if (args.size() - 1 != targetArgs.length) return null;
            TaintValue[] seedLocals = new TaintValue[localCount(targetArgs) + 8];
            int local = 0;
            for (int i = 0; i < targetArgs.length; i++) {
                Expr argExpr = isNumericAsmType(targetArgs[i])
                        ? new WriteInput(i, descriptorChar(targetArgs[i]))
                        : args.get(i + 1);
                seedLocals[local] = new TaintValue(targetArgs[i].getSize(), argExpr);
                local += targetArgs[i].getSize();
            }
            ctx.inlineBudget--;
            return analyzeMethodWrites(target.owner(), target.name(), target.desc(), seedLocals, ctx, depth + 1);
        }
        if (depth + 1 >= ctx.maxDepth || ctx.inlineBudget <= 0 || call.name.startsWith("<")) return null;
        if (call.getOpcode() == Opcodes.INVOKESTATIC && !isHealthLifecycleCall(call)) return null;
        if (call.owner.startsWith("java/") || call.owner.startsWith("javax/") || call.owner.startsWith("jdk/")) {
            return null;
        }
        if (call.owner.startsWith("net/minecraftforge/") || call.owner.startsWith("org/spongepowered/")
                || call.owner.startsWith("com/google/")) return null;
        Class<?> referencedOwner = loadClass(call.owner);
        if (referencedOwner == null) return null;
        if (call.owner.startsWith("net/minecraft/") && !isHealthLifecycleCall(call)) return null;
        Class<?> owner = resolveInvocationOwner(referencedOwner, call.name, call.desc);
        if (owner == null) return null;
        boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
        Type[] argTypes = Type.getArgumentTypes(call.desc);
        int expectedArgs = argTypes.length + (isStatic ? 0 : 1);
        if (args.size() != expectedArgs) return null;

        int localCount = isStatic ? 0 : 1;
        for (Type argType : argTypes) localCount += argType.getSize();
        TaintValue[] seedLocals = new TaintValue[localCount + 8];
        int local = 0;
        int arg = 0;
        if (!isStatic) seedLocals[local++] = new TaintValue(1, args.get(arg++));
        for (int i = 0; i < argTypes.length; i++) {
            Type argType = argTypes[i];
            Expr argExpr = isNumericAsmType(argType) ? new WriteInput(i, descriptorChar(argType)) : args.get(arg);
            seedLocals[local] = new TaintValue(argType.getSize(), argExpr);
            arg++;
            local += argType.getSize();
        }
        ctx.inlineBudget--;
        return analyzeMethodWrites(owner, call.name, call.desc, seedLocals, ctx, depth + 1);
    }

    private static boolean isHealthLifecycleCall(MethodInsnNode call) {
        for (McMethod method : new McMethod[]{HURT, ACTUALLY_HURT, SET_HEALTH}) {
            if (method.desc().equals(call.desc)
                    && (method.srg().equals(call.name) || method.mcp().equals(call.name))) return true;
        }
        return false;
    }

    private static Class<?> resolveInvocationOwner(Class<?> referencedOwner, String name, String desc) {
        for (Class<?> current = referencedOwner; current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (classDefinesMethod(current, name, desc)) return current;
        }
        return null;
    }

    private static int localCount(Type[] types) {
        int count = 0;
        for (Type type : types) count += type.getSize();
        return count;
    }

    private static TaintValue[] seedMethodInputs(String desc, boolean isStatic) {
        Type[] argumentTypes = Type.getArgumentTypes(desc);
        int localCount = isStatic ? 0 : 1;
        for (Type argumentType : argumentTypes) localCount += argumentType.getSize();
        TaintValue[] locals = new TaintValue[localCount + 8];
        int local = 0;
        if (!isStatic) locals[local++] = new TaintValue(1, EntityParamMarker.I);
        int numericIndex = 0;
        for (Type argumentType : argumentTypes) {
            Expr expression = isNumericAsmType(argumentType)
                    ? new WriteInput(numericIndex++, descriptorChar(argumentType))
                    : UnknownExpr.UNKNOWN;
            locals[local] = new TaintValue(argumentType.getSize(), expression);
            local += argumentType.getSize();
        }
        return locals;
    }

    private static TaintValue[] seedCallLocals(MethodInsnNode call, List<Expr> arguments,
                                               boolean replaceNumericArguments) {
        boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
        Type[] argumentTypes = Type.getArgumentTypes(call.desc);
        int expected = argumentTypes.length + (isStatic ? 0 : 1);
        if (arguments.size() != expected) return null;
        int localCount = isStatic ? 0 : 1;
        for (Type argumentType : argumentTypes) localCount += argumentType.getSize();
        TaintValue[] locals = new TaintValue[localCount + 8];
        int local = 0;
        int argument = 0;
        if (!isStatic) locals[local++] = new TaintValue(1, arguments.get(argument++));
        int numericIndex = 0;
        for (Type argumentType : argumentTypes) {
            Expr argumentExpression = arguments.get(argument++);
            Expr expression = replaceNumericArguments && isNumericAsmType(argumentType)
                    ? new WriteInput(numericIndex++, descriptorChar(argumentType))
                    : argumentExpression;
            locals[local] = new TaintValue(argumentType.getSize(), expression);
            local += argumentType.getSize();
        }
        return locals;
    }

    private static boolean isNumericAsmType(Type type) {
        if (type == null) return false;
        return switch (type.getSort()) {
            case Type.BYTE, Type.SHORT, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE, Type.CHAR -> true;
            default -> false;
        };
    }

    private static char descriptorChar(Type type) {
        return type == null || type.getDescriptor().isEmpty() ? '?' : type.getDescriptor().charAt(0);
    }

    private static Source buildFieldSourceFromReceiver(FieldInsnNode field, Expr receiver) {
        FieldStep step = new FieldStep(field.owner, field.name, field.desc);
        if (receiver == EntityParamMarker.I) {
            Expr source = makeFieldChainSource(List.of(step));
            return source instanceof Source s ? s : null;
        }
        if (receiver instanceof FieldChainSource fcs) {
            List<FieldStep> chain = new ArrayList<>(fcs.chain);
            chain.add(step);
            Expr source = makeFieldChainSource(chain);
            return source instanceof Source s ? s : null;
        }
        Class<?> type = descriptorToClass(step.desc());
        return new ChainedFieldSource(receiver, List.of(step), type == null ? Object.class : type);
    }

    private static Source buildStaticFieldWriteSource(FieldInsnNode field) {
        try {
            Class<?> owner = loadClass(field.owner);
            if (owner == null) return null;
            Field reflected = findFieldInHierarchy(owner, field.name);
            if (reflected == null) return null;
            reflected.setAccessible(true);
            return new StaticFieldSource(reflected);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static Expr makeFieldChainSource(List<FieldStep> chain) {
        try {
            VarHandle[] handles = new VarHandle[chain.size()];
            Class<?> lastType = null;
            for (int i = 0; i < chain.size(); i++) {
                FieldStep step = chain.get(i);
                Class<?> owner = loadClass(step.ownerInternal());
                Class<?> fieldType = descriptorToClass(step.desc());
                if (owner == null || fieldType == null) return UnknownExpr.UNKNOWN;
                Field field = findFieldInHierarchy(owner, step.name());
                if (field == null) return UnknownExpr.UNKNOWN;
                Class<?> declaring = field.getDeclaringClass();
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(declaring, MethodHandles.lookup());
                handles[i] = lookup.findVarHandle(declaring, step.name(), fieldType);
                lastType = fieldType;
            }
            return new FieldChainSource(chain, handles, lastType);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return UnknownExpr.UNKNOWN;
        }
    }

    private static boolean isSynchedDataSet(MethodInsnNode call) {
        return call.owner.equals("net/minecraft/network/syncher/SynchedEntityData")
                && (call.name.equals("m_135381_") || call.name.equals("set"));
    }

    private static boolean isMapPut(MethodInsnNode call) {
        return (call.name.equals("put") || call.name.equals("putIfAbsent")) && isMapClassByName(call.owner);
    }

    private static boolean isNumericType(Type type) {
        return switch (type.getSort()) {
            case Type.BYTE, Type.SHORT, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE -> true;
            case Type.OBJECT -> {
                String name = type.getInternalName();
                yield name.equals("java/lang/Number") || name.equals("java/lang/Float")
                        || name.equals("java/lang/Double") || name.equals("java/lang/Integer")
                        || name.equals("java/lang/Long") || name.equals("java/lang/Short")
                        || name.equals("java/lang/Byte");
            }
            default -> false;
        };
    }

    private static void addUniqueExpr(List<Expr> expressions, Expr expression) {
        if (expression == null) return;
        if (expression instanceof Choice choice) {
            for (Expr alternative : choice.alternatives()) addUniqueExpr(expressions, alternative);
            return;
        }
        if (!expressions.contains(expression)) expressions.add(expression);
    }

    private record MethodTarget(Class<?> owner, String name, String desc) {}

    private static boolean isMethodHandleInvoke(MethodInsnNode call) {
        return call.owner.equals("java/lang/invoke/MethodHandle")
                && (call.name.equals("invokeExact") || call.name.equals("invoke"));
    }

    private static MethodTarget resolveMethodHandleTarget(Expr handleExpr) {
        if (!(handleExpr instanceof Reference reference) || !(reference.value() instanceof MethodHandle handle)) {
            return null;
        }
        try {
            MethodHandleInfo info = MethodHandles.lookup().revealDirect(handle);
            Class<?> owner = info.getDeclaringClass();
            String name = info.getName();
            String desc = info.getMethodType().toMethodDescriptorString();
            MethodTarget remapped = remapHiddenNestmateTarget(owner, name, desc);
            return remapped != null ? remapped : new MethodTarget(owner, name, desc);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
        return resolveHandleBySignature(handle, reference.className());
    }

    /* revealDirect 只能揭示本 Lookup 有权访问的句柄，指向隐藏类成员的句柄必然失败。
       这类隐藏类通常只是转发壳，真实实现仍留在定义它的宿主类中，因此改按句柄类型在句柄字段
       所属类里反查同签名静态方法。同签名命中多个实现时放弃，宁可无解也不猜错目标。 */
    private static MethodTarget resolveHandleBySignature(MethodHandle handle, String ownerInternal) {
        if (handle == null || ownerInternal == null) return null;
        Class<?> owner = loadClass(ownerInternal);
        if (owner == null) return null;
        String desc;
        try {
            desc = handle.type().toMethodDescriptorString();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
        Set<String> dispatchers = methodHandleDispatchers(owner);
        MethodTarget found = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            String methodDesc = Type.getMethodDescriptor(method);
            if (!methodDesc.equals(desc)) continue;
            // 自身经句柄再分发的方法是调用方而非实现，选中它只会解析回原点
            if (dispatchers.contains(method.getName() + methodDesc)) continue;
            if (found != null) return null;
            found = new MethodTarget(owner, method.getName(), desc);
        }
        return found;
    }

    /* owner 中经由 MethodHandle 再分发的方法，按 name+desc 标识。 */
    private static Set<String> methodHandleDispatchers(Class<?> owner) {
        Set<String> names = new HashSet<>();
        try {
            byte[] bytes = classBytes(owner);
            if (bytes == null) return names;
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof MethodInsnNode call && isMethodHandleInvoke(call)) {
                        names.add(mn.name + mn.desc);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
        return names;
    }

    private static MethodTarget remapHiddenNestmateTarget(Class<?> owner, String name, String desc) {
        try {
            Class<?> host = owner.getNestHost();
            if (host == null || host == owner || name == null || name.length() != 1) return null;
            String hostName = name + "0";
            if (findAnyMethod(host, hostName, desc) == null) return null;
            return new MethodTarget(host, hostName, desc);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    public static final class AnalysisResult {
        public static final AnalysisResult EMPTY = new AnalysisResult(UnknownExpr.UNKNOWN, List.of(), null);
        /* 数据流分析失败哨兵，调用方通过身份比较区分成功结果与分析失败。 */
        public static final AnalysisResult DATA_FLOW_ANALYZER_FAILED = new AnalysisResult(UnknownExpr.UNKNOWN, List.of(), null);
        public final Expr returnExpr;
        public final List<Source> sources;
        /* 实际定义 getHealth()F 方法的类（可能是实体类的父类），retransform 必须针对此类 */
        public final Class<?> definingClass;
        private AnalysisResult(Expr e, List<Source> s, Class<?> dc) {
            this.returnExpr = e; this.sources = s; this.definingClass = dc;
        }
        public boolean isEmpty() {
            return returnExpr == null || returnExpr instanceof UnknownExpr;
        }

        // getHealth 返回值的分类语义，决定改血手段的分流
        // CONST_OVERRIDE：树中存在常数语义（纯常数或 Choice 含常数分支），走常数覆写 patch
        // DATAFLOW：包含可写 Source，使用数据流结果写入存储
        // UNRESOLVED：含 Unknown 或无法符号化的非常数计算，留给后续模块
        public enum Kind { DATAFLOW, CONST_OVERRIDE, UNRESOLVED }

        /* CONST_OVERRIDE 优先级高于 DATAFLOW：只要有常数语义就优先走常数覆写 patch，
           即使树中也存在 Source（如加密保护的存储），常数覆写仍能生效。
           DATAFLOW 仅用于纯数据流可写、无常数语义的实体。 */
        public Kind classify() {
            if (hasConstantBranch(returnExpr)) return Kind.CONST_OVERRIDE;
            if (sources.stream().anyMatch(ConstOverrideSource.class::isInstance)) return Kind.CONST_OVERRIDE;
            if (returnExpr == null || containsUnknown(returnExpr)) return Kind.UNRESOLVED;
            if (!sources.isEmpty()) return Kind.DATAFLOW;
            return Kind.UNRESOLVED;
        }

        public static AnalysisResult of(Expr e, Class<?> definingClass) {
            Expr rewritten = rewriteConstOverrides(e);
            return new AnalysisResult(rewritten, List.copyOf(collectSources(rewritten)), definingClass);
        }
    }

    private static boolean shouldKeepAsRuntimeCall(Class<?> owner, String name, String desc) {
        if (owner.getName().startsWith("java.")) return false;
        try {
            byte[] bytes = classBytes(owner);
            if (bytes == null) return false;
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
            for (MethodNode method : cn.methods) {
                if (method.name.equals(name) && method.desc.equals(desc)) {
                    if (hasStaticCodecInverse(cn, method)) return true;
                    return Type.getReturnType(desc).equals(Type.getType(String.class))
                            && hasComplexStringRuntimeBoundary(method);
                }
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
        return false;
    }

    private static boolean hasComplexStringRuntimeBoundary(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            int op = insn.getOpcode();
            if (op == Opcodes.NEWARRAY || op == Opcodes.ANEWARRAY || op == Opcodes.MULTIANEWARRAY
                    || isArrayStoreOpcode(op)) {
                return true;
            }
            if (insn instanceof JumpInsnNode jump && jump.label != null) {
                int insnIndex = method.instructions.indexOf(insn);
                int targetIndex = method.instructions.indexOf(jump.label);
                if (targetIndex >= 0 && targetIndex <= insnIndex) return true;
            }
        }
        return false;
    }

    public static AnalysisResult analyze(Class<?> entityClass) {
        return analyze(entityClass, DEFAULT_MAX_DEPTH);
    }

    public static AnalysisResult analyze(Class<?> entityClass, int maxDepth) {
        try {
            byte[] classBytes = classBytes(entityClass);
            if (classBytes == null) {
                if (entityClass.getName().contains("/0x") && entityClass.getSuperclass() != null) {
                    return analyze(entityClass.getSuperclass(), maxDepth);
                }
                return AnalysisResult.EMPTY;
            }
            // 运行时 klass 替换后的隐藏类不能从资源回退；若没有捕获到其覆写，继承的可读 getter
            // 仍可描述同一存储协议，优先交给父类分析而非把伪字节码当作真实实现。
            if (entityClass.getName().contains("/0x")
                    && !classDefinesMethodInBytes(entityClass, classBytes, GET_HEALTH.srg())
                    && !classDefinesMethodInBytes(entityClass, classBytes, GET_HEALTH.mcp())
                    && entityClass.getSuperclass() != null) {
                return analyze(entityClass.getSuperclass(), maxDepth);
            }
            ClassAndMethod target = findMethodOwnerFromBytes(entityClass, classBytes);
            if (target == null) return AnalysisResult.EMPTY;
            AnalysisCtx ctx = new AnalysisCtx(maxDepth);
            Class<?> defClass = target.owner();
            Expr ret = analyzeMethod(defClass, target.name(), GET_HEALTH.desc(), null, ctx, 0);
            if (ret == null) return AnalysisResult.EMPTY;
            Expr stripped = stripEcaHealthWrappers(ret);
            if (stripped == null || stripped instanceof UnknownExpr) return AnalysisResult.EMPTY;
            return AnalysisResult.of(stripped, target.owner());
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            return AnalysisResult.EMPTY;
        }
    }

    /* ==================== 统一核心入口：方法 × 提取策略 ==================== */

    /* 从字节码 Frame[] 中提取目标 Expr 的策略——同一套抽象解释引擎之上的不同收尾。
       新增策略时务必同步 analyzeMethod 的分发 switch，否则会落到 EMPTY 默认分支。 */
    public enum ExtractionStrategy {
        /* FRETURN 栈顶 → 方法返回值表达式。适用：getHealth/getMaxHealth 等返回血量的方法 */
        RETURN_VALUE,
        /* 所有 PUTFIELD/PUTSTATIC/SynchedData.set/Map.put → 写位置与写值。适用：hurt/actuallyHurt 等修改血量的方法 */
        METHOD_WRITES,
    /* FCMPL/FCMPG 前栈上的非常数侧是与阈值比较的表达式，适用于 isAlive 和 isDeadOrDying 等方法。 */
        COMPARISON_OPERANDS,
    }

    /* 收集条件分支依赖的数值表达式，保留多个独立生命状态候选。 */
    private static final java.util.function.BiFunction<MethodNode, Frame<TaintValue>[], Expr> COMPARISON_OPERANDS_EXTRACTOR = (mn, frames) -> {
        List<Expr> candidates = new ArrayList<>();
        int idx = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            Frame<TaintValue> frame = frames[idx];
            collectComparisonOperands(candidates, frame, insn.getOpcode());
            idx++;
        }
        if (candidates.isEmpty()) return null;
        return candidates.size() == 1 ? candidates.get(0) : new Choice(List.copyOf(candidates));
    };

    private static void collectComparisonOperands(List<Expr> candidates, Frame<TaintValue> frame, int opcode) {
        if (frame == null) return;
        boolean twoOperands = opcode == Opcodes.FCMPL || opcode == Opcodes.FCMPG
                || opcode == Opcodes.DCMPL || opcode == Opcodes.DCMPG || opcode == Opcodes.LCMP
                || opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE;
        if (twoOperands && frame.getStackSize() >= 2) {
            Expr left = frame.getStack(frame.getStackSize() - 2).expr;
            Expr right = frame.getStack(frame.getStackSize() - 1).expr;
            addComparisonCandidate(candidates, left, right);
            addComparisonCandidate(candidates, right, left);
            return;
        }
        if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE && frame.getStackSize() >= 1) {
            addComparisonCandidate(candidates, frame.getStack(frame.getStackSize() - 1).expr, null);
        }
    }

    private static void addComparisonCandidate(List<Expr> candidates, Expr candidate, Expr counterpart) {
        if (candidate == null || candidate instanceof Primitive || candidate instanceof UnknownExpr) return;
        if (counterpart != null && !(counterpart instanceof Primitive) && !(counterpart instanceof UnknownExpr)) return;
        addUniqueExpr(candidates, candidate);
    }

    /* 通用分析入口(查表版)：传入方法表项 + 提取策略即可分析。method.matchIn(cls) 内置 SRG 优先 MCP 后备查找。 */
    public static AnalysisResult analyzeMethod(Class<?> cls, McMethod method, ExtractionStrategy strategy) {
        if (cls == null || method == null || strategy == null) return AnalysisResult.EMPTY;
        String name = method.matchIn(cls);
        if (name == null) return AnalysisResult.EMPTY;
        try {
            return switch (strategy) {
                case RETURN_VALUE -> {
                    AnalysisCtx ctx = new AnalysisCtx(DEFAULT_MAX_DEPTH);
                    Expr ret = analyzeMethod(cls, name, method.desc(), null, ctx, 0);
                    yield wrapResult(ret, cls);
                }
                case METHOD_WRITES -> {
                    AnalysisCtx ctx = new AnalysisCtx(DEFAULT_MAX_DEPTH);
                    Expr writes = analyzeMethodWrites(cls, name, method.desc(), null, ctx, 0);
                    yield wrapResult(writes, cls);
                }
                case COMPARISON_OPERANDS -> {
                    AnalysisResult ar = analyzeForHealthExpr(cls, name, method.desc(), COMPARISON_OPERANDS_EXTRACTOR);
                    yield ar != null ? ar : AnalysisResult.EMPTY;
                }
            };
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return AnalysisResult.EMPTY;
        }
    }

    private static AnalysisResult wrapResult(Expr raw, Class<?> definingClass) {
        if (raw == null || raw instanceof UnknownExpr) return AnalysisResult.EMPTY;
        Expr stripped = stripEcaHealthWrappers(raw);
        if (stripped == null || stripped instanceof UnknownExpr) return AnalysisResult.EMPTY;
        return AnalysisResult.of(stripped, definingClass);
    }

    /* ==================== MC 实体方法预设快捷入口 ==================== */

    /* 分析 getHealth()F：返回值即血量本身 */
    public static AnalysisResult analyzeGetHealth(Class<?> cls) {
        return analyzeMethod(cls, GET_HEALTH, ExtractionStrategy.RETURN_VALUE);
    }

    /* 分析 isAlive()Z：从血量与 0 比较的表达式反推血量 */
    public static AnalysisResult analyzeIsAlive(Class<?> cls) {
        return analyzeMethod(cls, IS_ALIVE, ExtractionStrategy.COMPARISON_OPERANDS);
    }

    /* 分析 isDeadOrDying()Z：从血量与 0 比较的表达式反推血量 */
    public static AnalysisResult analyzeIsDeadOrDying(Class<?> cls) {
        return analyzeMethod(cls, IS_DEAD_OR_DYING, ExtractionStrategy.COMPARISON_OPERANDS);
    }

    /* 分析 hurt(DamageSource,F)Z：从方法体内的所有写位置定位真实血量存储 */
    public static AnalysisResult analyzeHurt(Class<?> cls) {
        return analyzeMethod(cls, HURT, ExtractionStrategy.METHOD_WRITES);
    }

    /* 分析 actuallyHurt(DamageSource,F)V：从方法体内的所有写位置定位真实血量存储 */
    public static AnalysisResult analyzeActuallyHurt(Class<?> cls) {
        return analyzeMethod(cls, ACTUALLY_HURT, ExtractionStrategy.METHOD_WRITES);
    }

    /* 只载一次 classBytes，沿继承链扫描 getHealth()F 定义类 */
    private static ClassAndMethod findMethodOwnerFromBytes(Class<?> startClass, byte[] startBytes) {
        if (classDefinesMethodInBytes(startClass, startBytes, GET_HEALTH.srg())) return new ClassAndMethod(startClass, GET_HEALTH.srg());
        if (classDefinesMethodInBytes(startClass, startBytes, GET_HEALTH.mcp())) return new ClassAndMethod(startClass, GET_HEALTH.mcp());
        for (Class<?> c = startClass.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (classDefinesMethod(c, GET_HEALTH.srg(), GET_HEALTH.desc())) return new ClassAndMethod(c, GET_HEALTH.srg());
            if (classDefinesMethod(c, GET_HEALTH.mcp(), GET_HEALTH.desc())) return new ClassAndMethod(c, GET_HEALTH.mcp());
        }
        return null;
    }

    private static ClassAndMethod findMethodOwner(Class<?> startClass, McMethod method) {
        for (Class<?> current = startClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (classDefinesMethod(current, method.srg(), method.desc())) {
                return new ClassAndMethod(current, method.srg());
            }
            if (classDefinesMethod(current, method.mcp(), method.desc())) {
                return new ClassAndMethod(current, method.mcp());
            }
        }
        return null;
    }

    private static boolean classDefinesMethodInBytes(Class<?> owner, byte[] bytes, String name) {
        if (bytes == null) return false;
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(GET_HEALTH.desc())) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /* 动态求解用：以具体种子分析任意方法的返回表达式。seedExprs[i] 对应方法第 i 个局部槽
       (实例方法 0=this)；通常 this 传 Reference(真实对象)，参数传 Primitive(真实值)，
       于是树中所有字段/数组叶子都根植于具体对象，可被 evaluate/solveFor 直接 concrete 求值。 */
    private static Expr stripEcaHealthWrappers(Expr expr) {
        Expr stripped = stripEcaHealthWrappers(expr, false);
        return stripped == null ? UnknownExpr.UNKNOWN : stripped;
    }

    private static Expr stripEcaHealthWrappers(Expr expr, boolean discardWrapper) {
        if (expr == null) return null;
        if (expr instanceof Source source) {
            if (isEcaHealthWrapperSource(source)) return discardWrapper ? null : UnknownExpr.UNKNOWN;
            return expr;
        }
        if (expr instanceof Reference reference && isEcaHealthWrapperReference(reference)) {
            return discardWrapper ? null : UnknownExpr.UNKNOWN;
        }
        if (expr instanceof Call call) {
            if (isEcaHealthWrapperCall(call)) return discardWrapper ? null : UnknownExpr.UNKNOWN;
            List<Expr> args = new ArrayList<>(call.args().size());
            for (Expr arg : call.args()) {
                Expr stripped = stripEcaHealthWrappers(arg, true);
                if (stripped == null) return discardWrapper ? null : UnknownExpr.UNKNOWN;
                args.add(stripped);
            }
            return args.equals(call.args()) ? expr : new Call(call.owner(), call.name(), call.desc(), List.copyOf(args));
        }
        if (expr instanceof Op op) {
            List<Expr> args = new ArrayList<>(op.args().size());
            for (Expr arg : op.args()) {
                Expr stripped = stripEcaHealthWrappers(arg, true);
                if (stripped == null) return discardWrapper ? null : UnknownExpr.UNKNOWN;
                args.add(stripped);
            }
            return args.equals(op.args()) ? expr : new Op(op.opcode(), List.copyOf(args));
        }
        if (expr instanceof Choice choice) {
            List<Expr> alternatives = new ArrayList<>();
            for (Expr alternative : choice.alternatives()) {
                Expr stripped = stripEcaHealthWrappers(alternative, true);
                if (stripped != null && !(stripped instanceof UnknownExpr) && !alternatives.contains(stripped)) {
                    alternatives.add(stripped);
                }
            }
            if (alternatives.isEmpty()) return discardWrapper ? null : UnknownExpr.UNKNOWN;
            return alternatives.size() == 1 ? alternatives.get(0) : new Choice(List.copyOf(alternatives));
        }
        if (expr instanceof Closure closure) {
            List<Expr> captured = new ArrayList<>(closure.captured().size());
            for (Expr arg : closure.captured()) {
                Expr stripped = stripEcaHealthWrappers(arg, true);
                if (stripped == null) return discardWrapper ? null : UnknownExpr.UNKNOWN;
                captured.add(stripped);
            }
            return captured.equals(closure.captured()) ? expr
                    : new Closure(closure.implementation(), closure.samName(), closure.samDesc(), List.copyOf(captured));
        }
        if (expr instanceof OptionalContentExpr optional) {
            Expr stripped = stripEcaHealthWrappers(optional.optionalExpr(), true);
            if (stripped == null) return discardWrapper ? null : UnknownExpr.UNKNOWN;
            return stripped == optional.optionalExpr() ? expr : new OptionalContentExpr(stripped);
        }
        return expr;
    }

    private static boolean isEcaHealthWrapperCall(Call call) {
        return isWrapperOwner(call.owner());
    }

    private static boolean isEcaHealthWrapperSource(Source source) {
        for (String prefix : WRAPPER_SOURCE_LABEL_PREFIXES) {
            if (source.label.startsWith(prefix)) return true;
        }
        return hasWrapperOwnedFieldStep(source);
    }

    /* 调用者 hook 的中转存储会被建模成字段链源，字段名与实体自身的血量字段无法区分，
       只能按 chain 上的 owner 归属剥离。这类源写入无效，也不代表目标实体的存储。 */
    private static boolean hasWrapperOwnedFieldStep(Source source) {
        List<FieldStep> chain = source instanceof FieldChainSource fieldChain ? fieldChain.chain
                : source instanceof ChainedFieldSource chained ? chained.chain : null;
        if (chain == null) return false;
        for (FieldStep step : chain) {
            if (isWrapperOwner(step.ownerInternal())) return true;
        }
        return false;
    }

    /* hook 的私有 record 与内部类以嵌套类形式出现，登记 owner 的嵌套类同属调用者注入 */
    private static boolean isWrapperOwner(String ownerInternal) {
        if (ownerInternal == null) return false;
        if (WRAPPER_CALL_OWNERS.contains(ownerInternal)) return true;
        for (String owner : WRAPPER_CALL_OWNERS) {
            if (ownerInternal.length() > owner.length()
                    && ownerInternal.charAt(owner.length()) == '$'
                    && ownerInternal.startsWith(owner)) return true;
        }
        return false;
    }

    /* 保留可验证的静态编解码边界，避免分析器把解码器展开成 AES/Base64 等不可逆实现细节。 */
    private static boolean hasStaticCodecInverse(ClassNode owner, MethodNode decoder) {
        if ((decoder.access & Opcodes.ACC_STATIC) == 0) return false;
        Type[] decoderArgs = Type.getArgumentTypes(decoder.desc);
        Type decoderReturn = Type.getReturnType(decoder.desc);
        if (decoderArgs.length != 1 || decoderArgs[0].getSort() != Type.OBJECT
                || !isNumericAsmType(decoderReturn)) return false;
        String encodedDesc = decoderArgs[0].getDescriptor();
        for (MethodNode encoder : owner.methods) {
            if ((encoder.access & Opcodes.ACC_STATIC) == 0) continue;
            Type[] encoderArgs = Type.getArgumentTypes(encoder.desc);
            if (encoderArgs.length == 1 && isNumericAsmType(encoderArgs[0])
                    && Type.getReturnType(encoder.desc).getDescriptor().equals(encodedDesc)) return true;
        }
        return false;
    }

    /* GETSTATIC 常量折叠会把 hook 的 MethodHandle 与 ThreadLocal 常量变成 Reference，
       此时 value 不是 String，只有 className 表明其来源，需要一并判定。 */
    private static boolean isEcaHealthWrapperReference(Reference reference) {
        if (isWrapperOwner(reference.className())) return true;
        Object value = reference.value();
        return value instanceof String s && WRAPPER_REFERENCE_VALUES.contains(s);
    }

    public static Expr analyzeSeeded(Class<?> owner, String methodName, String desc, Expr[] seedExprs) {
        try {
            TaintValue[] seed = new TaintValue[seedExprs.length];
            for (int i = 0; i < seedExprs.length; i++) {
                if (seedExprs[i] == null) continue;
                int size = (seedExprs[i] instanceof Primitive p && (p.jvmType() == 'J' || p.jvmType() == 'D')) ? 2 : 1;
                seed[i] = new TaintValue(size, seedExprs[i]);
            }
            AnalysisCtx ctx = new AnalysisCtx(DEFAULT_MAX_DEPTH);
            Expr ret = analyzeMethod(owner, methodName, desc, seed, ctx, 0);
            return (ret == null || ret instanceof UnknownExpr) ? null : ret;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            return null;
        }
    }

    /* Analyze the complete write set of a runtime-revealed writer. The entity receiver and the
       single numeric input are seeded symbolically so every correlated store can be solved together. */
    public static AnalysisResult analyzeWriterMethod(Class<?> owner, String methodName, String desc,
                                                     boolean isStatic) {
        if (owner == null || methodName == null || desc == null) return null;
        try {
            Type[] argumentTypes = Type.getArgumentTypes(desc);
            int localCount = isStatic ? 0 : 1;
            for (Type argumentType : argumentTypes) localCount += argumentType.getSize();
            TaintValue[] seed = new TaintValue[localCount + 8];
            int local = 0;
            if (!isStatic) seed[local++] = new TaintValue(1, EntityParamMarker.I);
            int numericIndex = 0;
            for (Type argumentType : argumentTypes) {
                Expr expression;
                Class<?> argumentClass = asmTypeToClass(argumentType);
                if (argumentType.getSort() == Type.OBJECT && argumentClass != null
                        && LivingEntity.class.isAssignableFrom(argumentClass)) {
                    expression = EntityParamMarker.I;
                } else if (isNumericAsmType(argumentType)) {
                    expression = new WriteInput(numericIndex++, descriptorChar(argumentType));
                } else {
                    expression = UnknownExpr.UNKNOWN;
                }
                seed[local] = new TaintValue(argumentType.getSize(), expression);
                local += argumentType.getSize();
            }
            if (numericIndex != 1) return null;
            AnalysisCtx ctx = new AnalysisCtx(DEFAULT_MAX_DEPTH);
            Expr writes = analyzeMethodWrites(owner, methodName, desc, seed, ctx, 0);
            if (writes == null || writes instanceof UnknownExpr) return null;
            Expr stripped = stripEcaHealthWrappers(writes);
            if (stripped == null || stripped instanceof UnknownExpr) return null;
            AnalysisResult result = AnalysisResult.of(stripped, owner);
            return result.sources.size() < 2 ? null : result;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private record ClassAndMethod(Class<?> owner, String name) {}

    static boolean classDefinesMethod(Class<?> clazz, String name, String desc) {
        byte[] bytes = classBytes(clazz);
        if (bytes == null) return false;
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(desc)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /* 通过 ClassBytesProvider 读取字节码；调用者可提供运行期转换结果，否则从类资源读取。 */
    private static byte[] classBytes(Class<?> clazz) {
        return bytesProvider.get(clazz);
    }

    //类内部名：剥去隐藏类的运行期后缀(Foo/0x000... → Foo)，以便按磁盘模板类定位字节码。
    //隐藏类 getName() 可能不含包路径(只剩 SimpleName)，此时用其超类的包补全(隐藏类与模板同包)。
    private static String internalName(Class<?> clazz) {
        String n = clazz.getName().replace('.', '/');
        int hidden = n.indexOf("/0x");
        if (hidden < 0) return n;
        String stripped = n.substring(0, hidden);
        if (stripped.indexOf('/') < 0) {                 // 丢了包路径，用超类包补全
            Class<?> sup = clazz.getSuperclass();
            if (sup != null) {
                String supInternal = sup.getName().replace('.', '/');
                int lastSlash = supInternal.lastIndexOf('/');
                if (lastSlash > 0) stripped = supInternal.substring(0, lastSlash + 1) + stripped;
            }
        }
        return stripped;
    }

    private static final class AnalysisCtx {
        final int maxDepth;
        final Map<String, Expr> methodCache = new HashMap<>();
        //当前调用栈上正在分析的方法,用于内联环检测
        final Set<String> inflight = new HashSet<>();
        //全局熔断：内联次数上限,超出后直接返回 Unknown,防止指数膨胀
        int inlineBudget = DEFAULT_INLINE_BUDGET;
        //表达式节点预算：构造组合表达式时递减,耗尽即坍缩 Unknown
        int nodeBudget = DEFAULT_NODE_BUDGET;
        /* 方法在当前类找不到时是否沿继承链上溯查找。默认关闭，保持既有各通道的分析结果不变；
           有效血量模型扫描需要展开继承自基类的访问器，单独开启。 */
        boolean inheritedInline = false;
        AnalysisCtx(int maxDepth) { this.maxDepth = maxDepth; }
    }

    /* ==================== ASM analyzeMethod / Interpreter ==================== */

    @SuppressWarnings("unchecked")
    private static Expr analyzeMethod(Class<?> owner, String name, String desc,
                                       TaintValue[] seedLocals, AnalysisCtx ctx, int depth) {
        if (owner.getClassLoader() == null) return null;

        String cacheKey = owner.getName().replace('.', '/') + "#" + name + "#" + desc;
        if (seedLocals == null) {
            Expr cached = ctx.methodCache.get(cacheKey);
            if (cached != null) return cached;
        }

        //环检测：覆盖顶层方法 + 跨方法环(如 getHealth↔position↔setHealth 互相调用),已在分析栈上则坍缩 Unknown
        if (!ctx.inflight.add(cacheKey)) return new UnknownExpr("recursive-cycle");
        try {
            byte[] bytes = classBytes(owner);
            if (bytes == null) return null;
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
            MethodNode mn = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) { mn = m; break; }
            }
            // 子类未声明该方法时上溯基类，否则继承的访问器不展开，血量表达式会停在 Call 节点
            if (mn == null && ctx.inheritedInline && owner.getSuperclass() != null) {
                return analyzeMethod(owner.getSuperclass(), name, desc, seedLocals, ctx, depth);
            }
            if (mn == null || mn.instructions.size() == 0) return null;

            String ownerInternal = internalName(owner);
            TaintInterpreter interp = new TaintInterpreter(ctx, depth, ownerInternal, mn, seedLocals);
            Analyzer<TaintValue> analyzer = new Analyzer<>(interp);
            Frame<TaintValue>[] frames = analyzer.analyze(ownerInternal, mn);

            List<Expr> returns = new ArrayList<>();
            int idx = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                int op = insn.getOpcode();
                if (op == Opcodes.IRETURN || op == Opcodes.LRETURN || op == Opcodes.FRETURN
                    || op == Opcodes.DRETURN || op == Opcodes.ARETURN) {
                    Frame<TaintValue> f = frames[idx];
                    if (f != null && f.getStackSize() > 0) {
                        Expr e = f.getStack(f.getStackSize() - 1).expr;
                        Expr expanded = expandArrayReturn(mn, frames, idx, e, ctx, depth);
                        if (expanded != null && !collectSources(expanded).isEmpty()) e = expanded;
                        if (e != null && !(e instanceof UnknownExpr)) returns.add(e);
                    }
                }
                idx++;
            }
            Expr result = returns.isEmpty() ? new UnknownExpr("no-return-in-method")
                : (returns.size() == 1 ? returns.get(0) : new Choice(dedupe(returns)));
            if (seedLocals == null) ctx.methodCache.put(cacheKey, result);
            return result;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            return null;
        } finally {
            ctx.inflight.remove(cacheKey);
        }
    }

    private static List<Expr> dedupe(List<Expr> in) {
        List<Expr> out = new ArrayList<>();
        for (Expr e : in) if (!out.contains(e)) out.add(e);
        return out;
    }

    private static Expr expandArrayReturn(MethodNode mn, Frame<TaintValue>[] frames, int returnIndex,
                                          Expr returnExpr, AnalysisCtx ctx, int depth) {
        if (!(returnExpr instanceof ArrayElementSource array)
                || !(array.arrayExpr instanceof ArrayAllocExpr targetArray)) {
            return null;
        }
        Expr targetIndex = array.indexExpr;
        List<Expr> writes = new ArrayList<>();
        int i = 0;
        for (AbstractInsnNode insn : mn.instructions) {
            if (i >= returnIndex) break;
            Frame<TaintValue> frame = frames[i];
            Expr direct = directArrayStore(frame, insn, targetArray, targetIndex);
            if (direct != null && !(direct instanceof UnknownExpr) && !writes.contains(direct)) {
                writes.add(direct);
            }
            if (insn instanceof MethodInsnNode call && isIfPresentCall(call)) {
                Expr fromLambda = ifPresentArrayStore(frame, call, targetArray, targetIndex, ctx, depth);
                if (fromLambda != null && !(fromLambda instanceof UnknownExpr) && !writes.contains(fromLambda)) {
                    writes.add(fromLambda);
                }
            }
            i++;
        }
        if (writes.isEmpty()) return null;
        return writes.size() == 1 ? writes.get(0) : new Choice(writes);
    }

    private static Expr directArrayStore(Frame<TaintValue> frame, AbstractInsnNode insn,
                                         ArrayAllocExpr targetArray, Expr targetIndex) {
        if (frame == null || !isArrayStoreOpcode(insn.getOpcode()) || frame.getStackSize() < 3) return null;
        Expr array = frame.getStack(frame.getStackSize() - 3).expr;
        Expr index = frame.getStack(frame.getStackSize() - 2).expr;
        Expr value = frame.getStack(frame.getStackSize() - 1).expr;
        return targetArray.equals(array) && sameIndex(targetIndex, index) ? value : null;
    }

    private static Expr ifPresentArrayStore(Frame<TaintValue> frame, MethodInsnNode call,
                                           ArrayAllocExpr targetArray, Expr targetIndex,
                                           AnalysisCtx ctx, int depth) {
        if (frame == null) return null;
        List<Expr> args = invokeValueExprs(call, frame);
        if (args.size() < 2 || !(args.get(args.size() - 1) instanceof Closure closure)) return null;
        Expr optional = args.get(0);
        return analyzeClosureArrayStore(closure, List.of(new OptionalContentExpr(optional)),
                targetArray, targetIndex, ctx, depth + 1);
    }

    private static boolean isIfPresentCall(MethodInsnNode call) {
        if (!call.name.equals("ifPresent")) return false;
        Type[] args = Type.getArgumentTypes(call.desc);
        if (args.length != 1 || Type.getReturnType(call.desc) != Type.VOID_TYPE) return false;
        return args[0].getSort() == Type.OBJECT;
    }

    private static List<Expr> invokeValueExprs(MethodInsnNode call, Frame<TaintValue> frame) {
        Type[] args = Type.getArgumentTypes(call.desc);
        boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
        int count = args.length + (isStatic ? 0 : 1);
        if (frame.getStackSize() < count) return List.of();
        int start = frame.getStackSize() - count;
        List<Expr> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(frame.getStack(start + i).expr);
        return values;
    }

    private static Expr analyzeClosureArrayStore(Closure closure, List<Expr> invocationArgs,
                                                 ArrayAllocExpr targetArray, Expr targetIndex,
                                                 AnalysisCtx ctx, int depth) {
        Handle implementation = closure.implementation();
        Class<?> owner = loadClass(implementation.getOwner());
        if (owner == null || ctx.inlineBudget <= 0) return null;

        boolean isStatic = implementation.getTag() == Opcodes.H_INVOKESTATIC;
        List<Expr> seeds = new ArrayList<>();
        if (isStatic) {
            seeds.addAll(closure.captured());
        } else {
            if (closure.captured().isEmpty()) return null;
            seeds.add(closure.captured().get(0));
            seeds.addAll(closure.captured().subList(1, closure.captured().size()));
        }
        seeds.addAll(invocationArgs);

        Type[] argTypes = Type.getArgumentTypes(implementation.getDesc());
        int expectedSeeds = argTypes.length + (isStatic ? 0 : 1);
        if (seeds.size() != expectedSeeds) return null;

        int localCount = isStatic ? 0 : 1;
        for (Type type : argTypes) localCount += type.getSize();
        TaintValue[] locals = new TaintValue[localCount + 8];
        int local = 0;
        int seed = 0;
        if (!isStatic) locals[local++] = new TaintValue(1, seeds.get(seed++));
        for (Type type : argTypes) {
            locals[local] = new TaintValue(type.getSize(), seeds.get(seed++));
            local += type.getSize();
        }
        ctx.inlineBudget--;
        return analyzeArrayStore(owner, implementation.getName(), implementation.getDesc(),
                locals, targetArray, targetIndex, ctx, depth + 1);
    }

    private static Expr analyzeArrayStore(Class<?> owner, String name, String desc, TaintValue[] seedLocals,
                                          ArrayAllocExpr targetArray, Expr targetIndex,
                                          AnalysisCtx ctx, int depth) {
        String cacheKey = owner.getName().replace('.', '/') + "#" + name + "#" + desc + "#arrayStore";
        if (!ctx.inflight.add(cacheKey)) return new UnknownExpr("recursive-cycle-arrayStore");
        try {
            byte[] bytes = classBytes(owner);
            if (bytes == null) return null;
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
            MethodNode mn = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) { mn = m; break; }
            }
            if (mn == null || mn.instructions.size() == 0) return null;

            String ownerInternal = internalName(owner);
            TaintInterpreter interp = new TaintInterpreter(ctx, depth, ownerInternal, mn, seedLocals);
            Analyzer<TaintValue> analyzer = new Analyzer<>(interp);
            Frame<TaintValue>[] frames = analyzer.analyze(ownerInternal, mn);
            List<Expr> writes = new ArrayList<>();
            int i = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                Expr direct = directArrayStore(frames[i], insn, targetArray, targetIndex);
                if (direct != null && !(direct instanceof UnknownExpr) && !writes.contains(direct)) {
                    writes.add(direct);
                }
                i++;
            }
            if (writes.isEmpty()) return null;
            return writes.size() == 1 ? writes.get(0) : new Choice(writes);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        } finally {
            ctx.inflight.remove(cacheKey);
        }
    }

    private static boolean isArrayStoreOpcode(int opcode) {
        return opcode == Opcodes.IASTORE || opcode == Opcodes.LASTORE || opcode == Opcodes.FASTORE
                || opcode == Opcodes.DASTORE || opcode == Opcodes.AASTORE || opcode == Opcodes.BASTORE
                || opcode == Opcodes.CASTORE || opcode == Opcodes.SASTORE;
    }

    private static boolean sameIndex(Expr a, Expr b) {
        if (Objects.equals(a, b)) return true;
        if (a instanceof Primitive pa && b instanceof Primitive pb) {
            return pa.value().intValue() == pb.value().intValue();
        }
        return false;
    }

    private static final class TaintValue implements Value {
        final int size;
        final Expr expr;
        TaintValue(int size, Expr expr) { this.size = size; this.expr = expr; }
        @Override public int getSize() { return size; }
        @Override public boolean equals(Object o) {
            return o instanceof TaintValue v && size == v.size && Objects.equals(expr, v.expr);
        }
        @Override public int hashCode() { return Objects.hash(size, expr); }
    }

    private static final class EntityParamMarker implements Expr {
        static final EntityParamMarker I = new EntityParamMarker();
    }

    private static final class TaintInterpreter extends Interpreter<TaintValue> {
        final AnalysisCtx ctx;
        final int depth;
        final String currentOwner;
        final MethodNode currentMethod;
        final TaintValue[] seedLocals;

        TaintInterpreter(AnalysisCtx ctx, int depth, String currentOwner, MethodNode currentMethod, TaintValue[] seedLocals) {
            super(Opcodes.ASM9);
            this.ctx = ctx; this.depth = depth;
            this.currentOwner = currentOwner; this.currentMethod = currentMethod; this.seedLocals = seedLocals;
        }

        /* 为常数加载指令构造来源坐标:当前类/方法/指令下标 + 持有方法 receiver(local 0)。
           seedLocals 为空(顶层 getHealth)时 receiver 即实体本身(EntityParamMarker)。 */
        private ConstProvenance provenanceOf(AbstractInsnNode insn) {
            Expr receiver = (seedLocals != null && seedLocals.length > 0 && seedLocals[0] != null)
                    ? seedLocals[0].expr : EntityParamMarker.I;
            int index = currentMethod != null ? currentMethod.instructions.indexOf(insn) : -1;
            boolean holderIsStatic = currentMethod != null && (currentMethod.access & Opcodes.ACC_STATIC) != 0;
            return new ConstProvenance(currentOwner,
                    currentMethod != null ? currentMethod.name : null,
                    currentMethod != null ? currentMethod.desc : null,
                    index, holderIsStatic, receiver);
        }

        @Override public TaintValue newValue(Type type) {
            if (type == null) return new TaintValue(1, UnknownExpr.UNKNOWN);
            if (type == Type.VOID_TYPE) return null;
            return new TaintValue(type.getSize(), UnknownExpr.UNKNOWN);
        }

        @Override public TaintValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
            if (seedLocals != null && local < seedLocals.length && seedLocals[local] != null) return seedLocals[local];
            if (isInstanceMethod && local == 0) return new TaintValue(1, EntityParamMarker.I);
            return new TaintValue(type.getSize(), UnknownExpr.UNKNOWN);
        }

        @Override public TaintValue newOperation(AbstractInsnNode insn) {
            ConstProvenance prov = provenanceOf(insn);
            return switch (insn.getOpcode()) {
                case Opcodes.ACONST_NULL -> new TaintValue(1, UnknownExpr.UNKNOWN);
                case Opcodes.ICONST_M1 -> primI(prov, -1);
                case Opcodes.ICONST_0 -> primI(prov, 0);
                case Opcodes.ICONST_1 -> primI(prov, 1);
                case Opcodes.ICONST_2 -> primI(prov, 2);
                case Opcodes.ICONST_3 -> primI(prov, 3);
                case Opcodes.ICONST_4 -> primI(prov, 4);
                case Opcodes.ICONST_5 -> primI(prov, 5);
                case Opcodes.LCONST_0 -> primL(prov, 0);
                case Opcodes.LCONST_1 -> primL(prov, 1);
                case Opcodes.FCONST_0 -> primF(prov, 0f);
                case Opcodes.FCONST_1 -> primF(prov, 1f);
                case Opcodes.FCONST_2 -> primF(prov, 2f);
                case Opcodes.DCONST_0 -> primD(prov, 0d);
                case Opcodes.DCONST_1 -> primD(prov, 1d);
                case Opcodes.BIPUSH, Opcodes.SIPUSH -> primI(prov, ((IntInsnNode) insn).operand);
                case Opcodes.LDC -> ldcValue(prov, (LdcInsnNode) insn);
                case Opcodes.GETSTATIC -> {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    Type t = Type.getType(f.desc);
                    Expr resolved = resolveStaticField(f.owner, f.name);
                    if (resolved != null) yield new TaintValue(t.getSize(), resolved);
                    yield new TaintValue(t.getSize(), UnknownExpr.UNKNOWN);
                }
                default -> new TaintValue(1, new UnknownExpr("newOperation-unsupported-" + insn.getOpcode()));
            };
        }

        @Override public TaintValue copyOperation(AbstractInsnNode insn, TaintValue value) { return value; }

        @Override public TaintValue unaryOperation(AbstractInsnNode insn, TaintValue value) {
            int op = insn.getOpcode();
            return switch (op) {
                case Opcodes.INEG, Opcodes.FNEG -> new TaintValue(1, new Op(op, List.of(value.expr)));
                case Opcodes.LNEG, Opcodes.DNEG -> new TaintValue(2, new Op(op, List.of(value.expr)));
                case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> new TaintValue(1, new Op(op, List.of(value.expr)));
                case Opcodes.F2I, Opcodes.L2I, Opcodes.D2I, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S
                    -> new TaintValue(1, new Op(op, List.of(value.expr)));
                case Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> new TaintValue(2, new Op(op, List.of(value.expr)));
                case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> new TaintValue(2, new Op(op, List.of(value.expr)));
                case Opcodes.GETFIELD -> {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    Type t = Type.getType(f.desc);
                    Expr field = buildGetFieldSource(f, value.expr);
                    if (field == null) field = UnknownExpr.UNKNOWN;
                    yield new TaintValue(t.getSize(), field);
                }
                case Opcodes.CHECKCAST -> value;
                case Opcodes.INSTANCEOF -> new TaintValue(1, UnknownExpr.UNKNOWN);
                case Opcodes.ARRAYLENGTH -> new TaintValue(1, UnknownExpr.UNKNOWN);
                case Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> new TaintValue(1, new ArrayAllocExpr(System.identityHashCode(insn)));
                case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE,
                     Opcodes.IFGT, Opcodes.IFLE, Opcodes.IFNULL, Opcodes.IFNONNULL,
                     Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH,
                     Opcodes.PUTSTATIC, Opcodes.ATHROW,
                     Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> null;
                default -> new TaintValue(1, new UnknownExpr("unaryOperation-unknown-" + insn.getOpcode()));
            };
        }

        @Override public TaintValue binaryOperation(AbstractInsnNode insn, TaintValue v1, TaintValue v2) {
            int op = insn.getOpcode();
            return switch (op) {
                case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM,
                     Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM,
                     Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR
                    -> new TaintValue(1, new Op(op, List.of(v1.expr, v2.expr)));
                case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
                     Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM,
                     Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR
                    -> new TaintValue(2, new Op(op, List.of(v1.expr, v2.expr)));
                case Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR
                    -> new TaintValue(2, new Op(op, List.of(v1.expr, v2.expr)));
                case Opcodes.AALOAD, Opcodes.IALOAD, Opcodes.FALOAD,
                     Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD
                    -> new TaintValue(1, new ArrayElementSource(v1.expr, v2.expr, guessArrayElementType(op), "arr"));
                case Opcodes.LALOAD, Opcodes.DALOAD
                    -> new TaintValue(2, new ArrayElementSource(v1.expr, v2.expr, guessArrayElementType(op), "arr"));
                default -> new TaintValue(1, new UnknownExpr("binaryOperation-unknown-" + op));
            };
        }

        @Override public TaintValue ternaryOperation(AbstractInsnNode insn, TaintValue v1, TaintValue v2, TaintValue v3) {
            return null;
        }

        @Override public TaintValue naryOperation(AbstractInsnNode insn, List<? extends TaintValue> values) {
            if (insn.getOpcode() == Opcodes.MULTIANEWARRAY) return new TaintValue(1, UnknownExpr.UNKNOWN);
            if (insn.getOpcode() == Opcodes.INVOKEDYNAMIC) {
                InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) insn;
                Type ret = Type.getReturnType(dynamic.desc);
                Closure closure = closureFrom(dynamic, values);
                return ret == Type.VOID_TYPE ? null
                    : new TaintValue(ret.getSize(), closure == null ? UnknownExpr.UNKNOWN : closure);
            }
            MethodInsnNode m = (MethodInsnNode) insn;
            Type retType = Type.getReturnType(m.desc);
            int sz = retType == Type.VOID_TYPE ? 0 : retType.getSize();
            if (retType == Type.VOID_TYPE) return null;
            //节点预算耗尽：停止展开调用/内联，坍缩 Unknown，防止表达式树爆炸
            if (--ctx.nodeBudget <= 0) return new TaintValue(sz, new UnknownExpr("nodeBudget-exhausted"));

            Expr methodHandle = tryInlineMethodHandleReturn(m, values);
            if (methodHandle != null && !(methodHandle instanceof UnknownExpr)) {
                return new TaintValue(sz, methodHandle);
            }

            Expr functional = tryInlineFunctionalCall(m, values);
            if (functional != null && !(functional instanceof UnknownExpr)) {
                return new TaintValue(sz, functional);
            }

            // super.getHealth() / 父类 INVOKESPECIAL on getHealth → 沿继承链最终读 DATA_HEALTH_ID
            // 假设链路上没有再 override (绝大多数 mod 满足),把 super 调用直接识别为 DATA_HEALTH_ID 的 Source
            if (m.getOpcode() == Opcodes.INVOKESPECIAL
                && m.desc.equals(GET_HEALTH.desc())
                && (m.name.equals(GET_HEALTH.srg()) || m.name.equals(GET_HEALTH.mcp()))
                && values.size() >= 1
                && values.get(0).expr == EntityParamMarker.I) {
                return new TaintValue(sz, new SynchedDataSource(LivingEntity.DATA_HEALTH_ID, float.class));
            }

            // SynchedEntityData.get
            if (m.owner.equals("net/minecraft/network/syncher/SynchedEntityData")
                && (m.name.equals("m_135370_") || m.name.equals("get"))
                && values.size() >= 2) {
                Expr accExpr = values.get(1).expr;
                if (accExpr instanceof Reference ref && ref.value() instanceof EntityDataAccessor<?> acc) {
                    return new TaintValue(sz, new SynchedDataSource(acc, Object.class));
                }
                return new TaintValue(sz, UnknownExpr.UNKNOWN);
            }

            // Map.get / getOrDefault (任意 owner,运行时反射检测)
            if (isCapabilityValueGet(m) && values.size() >= 2) {
                return new TaintValue(sz, new CapabilityDataSource(values.get(0).expr, values.get(1).expr,
                        List.of(), Object.class));
            }

            if ((m.name.equals("get") || m.name.equals("getOrDefault"))
                && values.size() >= 2
                && isMapClassByName(m.owner)) {
                Expr container = values.get(0).expr;
                Expr key = values.get(1).expr;
                MapEntrySource.KeyKind kk = detectKeyKind(key);
                String ownerHint = mapOwnerHint(container);
                return new TaintValue(sz, new MapEntrySource(container, key, kk, ownerHint, Object.class, m.owner));
            }

            // 反射常量化：getDeclaredMethod/getMethod(name).invoke(recv) 或 getDeclaredField/getField(name).get(recv)
            // 当目标名是分析期常量时，重写为对真实方法的内联 / 真实字段的 Source，使反射隐藏的存储也能被符号化
            {
                TaintValue reflective = tryReflection(m, values, sz);
                if (reflective != null) return reflective;
            }

            // 递归内联
            Expr getter = tryInlineSimpleGetter(m, values);
            if (getter != null && !(getter instanceof UnknownExpr)) {
                return new TaintValue(sz, getter);
            }

            if (depth + 1 < ctx.maxDepth && !m.name.startsWith("<")) {
                Expr inlined = tryInline(m, values);
                if (inlined != null && !(inlined instanceof UnknownExpr)) {
                    return new TaintValue(sz, inlined);
                }
            }

            // 无法内联时保留 Call 节点
            List<Expr> argExprs = new ArrayList<>(values.size());
            for (TaintValue v : values) argExprs.add(v.expr);
            return new TaintValue(sz, new Call(m.owner, m.name, m.desc, argExprs));
        }

        private Expr tryInlineMethodHandleReturn(MethodInsnNode method, List<? extends TaintValue> values) {
            if (!isMethodHandleInvoke(method) || values.isEmpty()) return null;
            if (depth + 1 >= ctx.maxDepth || ctx.inlineBudget <= 0) return null;
            MethodTarget target = resolveMethodHandleTarget(values.get(0).expr);
            if (target == null) return null;
            Type[] targetArgs = Type.getArgumentTypes(target.desc());
            if (values.size() - 1 != targetArgs.length) return null;

            TaintValue[] seedLocals = new TaintValue[localCount(targetArgs) + 8];
            int local = 0;
            for (int i = 0; i < targetArgs.length; i++) {
                seedLocals[local] = values.get(i + 1);
                local += targetArgs[i].getSize();
            }
            ctx.inlineBudget--;
            return analyzeMethod(target.owner(), target.name(), target.desc(), seedLocals, ctx, depth + 1);
        }

        //闭包节点保留实现句柄与捕获值，使 SAM 调用可以按真实实现继续分析。
        private Closure closureFrom(InvokeDynamicInsnNode dynamic, List<? extends TaintValue> values) {
            List<Expr> captured = new ArrayList<>(values.size());
            for (TaintValue value : values) captured.add(value.expr);
            return closureFromExprs(dynamic, captured);
        }

        private Closure closureFromExprs(InvokeDynamicInsnNode dynamic, List<Expr> captured) {
            if (!dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) return null;
            Handle implementation = null;
            String samDesc = null;
            for (Object arg : dynamic.bsmArgs) {
                if (arg instanceof Handle handle) implementation = handle;
                else if (samDesc == null && arg instanceof Type type) samDesc = type.getDescriptor();
            }
            return implementation == null ? null
                : new Closure(implementation, dynamic.name, samDesc == null ? "" : samDesc, List.copyOf(captured));
        }

        private Expr tryInlineFunctionalCall(MethodInsnNode method, List<? extends TaintValue> values) {
            if (values.isEmpty()) return null;
            Expr receiver = values.get(0).expr;
            Closure closure = receiver instanceof Closure direct ? direct : resolveFunctionalField(receiver);
            if (closure == null || !closure.samName().equals(method.name)) return null;

            List<Expr> invocationArgs = new ArrayList<>();
            for (int i = 1; i < values.size(); i++) invocationArgs.add(values.get(i).expr);
            return inlineClosure(closure, invocationArgs);
        }

        private Closure resolveFunctionalField(Expr receiver) {
            if (!(receiver instanceof FieldChainSource source) || source.chain.isEmpty()) return null;
            FieldStep field = source.chain.get(source.chain.size() - 1);
            Class<?> fieldOwner = loadClass(field.ownerInternal());
            if (fieldOwner == null) return null;
            Expr captureRoot = source.chain.size() == 1
                ? EntityParamMarker.I : makeFieldChain(source.chain.subList(0, source.chain.size() - 1));
            /* 取最后一次有效赋值：字段初始化器(如 = ()->默认值)会被编译进 <init> 且排在构造器体的真实赋值之前，
               命中首个 PUTFIELD 会取到初始化器 lambda 而漏掉真实实现。遍历完 <init> 保留最后一个匹配的 closure。
               沿继承链向上扫，最先出现匹配的(最派生)定义类即生效——其初始化器/构造器在构造序列中最后执行。 */
            for (Class<?> scan = fieldOwner; scan != null && scan != Object.class; scan = scan.getSuperclass()) {
                try {
                    byte[] bytes = classBytes(scan);
                    if (bytes == null) continue;
                    ClassNode node = new ClassNode();
                    new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
                    Closure lastInClass = null;
                    for (MethodNode method : node.methods) {
                        if (!method.name.equals("<init>")) continue;
                        InvokeDynamicInsnNode nearest = null;
                        int distance = 0;
                        for (AbstractInsnNode instruction : method.instructions) {
                            if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                                nearest = dynamic;
                                distance = 0;
                                continue;
                            }
                            if (nearest != null && ++distance > 16) nearest = null;
                            if (!(instruction instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTFIELD) continue;
                            if (!put.name.equals(field.name()) || !put.desc.equals(field.desc()) || nearest == null) continue;
                            if (!put.owner.equals(scan.getName().replace('.', '/'))) continue;

                            Type[] capturedTypes = Type.getArgumentTypes(nearest.desc);
                            List<Expr> captured = new ArrayList<>(capturedTypes.length);
                            for (int i = 0; i < capturedTypes.length; i++) {
                                if (i == 0) {
                                    captured.add(captureRoot);
                                } else {
                                    // 尝试映射捕获参数到构造器参数：回溯 INVOKEDYNAMIC 前的 ALOAD <idx>
                                    Expr resolved = resolveCapturedArg(method.instructions, nearest, i);
                                    captured.add(resolved != null ? resolved
                                        : new UnknownExpr("lambda-capture-" + i));
                                }
                            }
                            Closure closure = closureFromExprs(nearest, captured);
                            if (closure != null) lastInClass = closure;
                        }
                    }
                    if (lastInClass != null) return lastInClass;
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                }
            }
            return null;
        }

        /* 尝试将 lambda 的第 captureIdx 个捕获参数映射到构造器的第 paramIdx 个参数。
           回溯 INVOKEDYNAMIC 前的 ALOAD <idx> 指令：若 idx 在构造器参数范围内，
           则用对应的 Primitive 或 EntityParamMarker 占位。 */
        private Expr resolveCapturedArg(InsnList instructions, InvokeDynamicInsnNode target, int captureIdx) {
            int aloadCount = 0;
            for (AbstractInsnNode insn = target.getPrevious(); insn != null; insn = insn.getPrevious()) {
                if (insn.getOpcode() == Opcodes.ALOAD && insn instanceof VarInsnNode v) {
                    if (aloadCount == captureIdx) {
                        if (v.var == 0) return EntityParamMarker.I;
                        // 构造器参数从 1 开始（0 是 this）
                        return new UnknownExpr("captured-ctor-param-" + v.var);
                    }
                    aloadCount++;
                }
            }
            return null;
        }

        private Expr inlineClosure(Closure closure, List<Expr> invocationArgs) {
            Handle implementation = closure.implementation();
            Class<?> owner = loadClass(implementation.getOwner());
            if (owner == null || ctx.inlineBudget <= 0) return null;

            boolean isStatic = implementation.getTag() == Opcodes.H_INVOKESTATIC;
            List<Expr> seeds = new ArrayList<>();
            if (isStatic) {
                seeds.addAll(closure.captured());
            } else {
                if (closure.captured().isEmpty()) return null;
                seeds.add(closure.captured().get(0));
                seeds.addAll(closure.captured().subList(1, closure.captured().size()));
            }
            seeds.addAll(invocationArgs);

            Type[] argTypes = Type.getArgumentTypes(implementation.getDesc());
            int expectedSeeds = argTypes.length + (isStatic ? 0 : 1);
            if (seeds.size() != expectedSeeds) return null;

            int localCount = isStatic ? 0 : 1;
            for (Type type : argTypes) localCount += type.getSize();
            TaintValue[] locals = new TaintValue[localCount + 8];
            int local = 0;
            int seed = 0;
            if (!isStatic) locals[local++] = new TaintValue(1, seeds.get(seed++));
            for (Type type : argTypes) {
                locals[local] = new TaintValue(type.getSize(), seeds.get(seed++));
                local += type.getSize();
            }
            ctx.inlineBudget--;
            return analyzeMethod(owner, implementation.getName(), implementation.getDesc(), locals, ctx, depth + 1);
        }

        /* 反射常量化只处理类与成员名均可确定的调用；无法确定时保留普通 Call，
           交给动态轨迹继续解析。 */
        private TaintValue tryReflection(MethodInsnNode m, List<? extends TaintValue> values, int sz) {
            if (!m.owner.equals("java/lang/reflect/Method") && !m.owner.equals("java/lang/reflect/Field")) return null;

            boolean isInvoke = m.owner.equals("java/lang/reflect/Method") && m.name.equals("invoke");
            boolean isGet = m.owner.equals("java/lang/reflect/Field") && m.name.equals("get");
            if (!isInvoke && !isGet) return null;
            if (values.isEmpty()) return null;

            // receiver(values[0]) 应是上游 Class.getDeclaredMethod/Field(...) 的 Call 节点
            Expr accessorExpr = values.get(0).expr;
            if (!(accessorExpr instanceof Call acc)) return null;

            Class<?> targetClass = constClass(acc.args());      // 第 1 个 Class 常量参(getDeclaredMethod 的 receiver)
            String memberName = constString(acc.args());        // 第 1 个 String 常量参(成员名)
            if (targetClass == null || memberName == null) return null;

            // invoke/get 的目标对象：Method.invoke(obj, args)→args[1]; Field.get(obj)→args[1]
            Expr targetObj = values.size() >= 2 ? values.get(1).expr : null;
            boolean onEntity = targetObj == EntityParamMarker.I;

            if (isGet) {
                // 反射读字段 → 当作字段链 Source(仅支持 this 上的字段，可写回)
                if (!onEntity) return null;
                try {
                    Field f = findReflectedField(targetClass, acc.name(), memberName);
                    if (f == null) return null;
                    FieldStep step = new FieldStep(f.getDeclaringClass().getName().replace('.', '/'), memberName,
                        Type.getDescriptor(f.getType()));
                    Expr src = makeFieldChain(List.of(step));
                    return src instanceof UnknownExpr ? null : new TaintValue(sz, src);
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
                    return null;
                }
            }

            // isInvoke：无参实例方法 + 目标是 this → 内联该真实方法
            if (!onEntity || depth + 1 >= ctx.maxDepth) return null;
            Method jm = findReflectedZeroArgMethod(targetClass, acc.name(), memberName);
            if (jm == null || ctx.inlineBudget <= 0) return null;
            ctx.inlineBudget--;
            TaintValue[] seed = new TaintValue[8];
            seed[0] = new TaintValue(1, EntityParamMarker.I);   // this
            Expr inlined = analyzeMethod(jm.getDeclaringClass(), jm.getName(), Type.getMethodDescriptor(jm), seed, ctx, depth + 1);
            return (inlined == null || inlined instanceof UnknownExpr) ? null : new TaintValue(sz, inlined);
        }

        //取 Call 参数里第一个解析到具体 Class 的常量(类字面量经 ldcValue → Reference(Class))
        private static Class<?> constClass(List<Expr> args) {
            for (Expr a : args) {
                if (a instanceof Reference r && r.value() instanceof Class<?> k) return k;
            }
            return null;
        }

        //取 Call 参数里第一个 String 常量(成员名)
        private static String constString(List<Expr> args) {
            for (Expr a : args) {
                if (a instanceof Reference r && r.value() instanceof String s) return s;
            }
            return null;
        }

        private static Field findReflectedField(Class<?> owner, String accessorName, String fieldName) {
            try {
                Field field = switch (accessorName) {
                    case "getField" -> owner.getField(fieldName);
                    case "getDeclaredField" -> owner.getDeclaredField(fieldName);
                    default -> null;
                };
                if (field != null) field.setAccessible(true);
                return field;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
                return null;
            }
        }

        private static Method findReflectedZeroArgMethod(Class<?> owner, String accessorName, String name) {
            return switch (accessorName) {
                case "getMethod" -> findZeroArgPublicMethod(owner, name);
                case "getDeclaredMethod" -> findDeclaredZeroArgMethod(owner, name);
                default -> findZeroArgMethod(owner, name);
            };
        }

        private static Method findDeclaredZeroArgMethod(Class<?> owner, String name) {
            try {
                Method method = owner.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
                return null;
            }
        }

        private static Method findZeroArgPublicMethod(Class<?> owner, String name) {
            try {
                Method method = owner.getMethod(name);
                method.setAccessible(true);
                return method;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
                return null;
            }
        }

        private static Method findZeroArgMethod(Class<?> owner, String name) {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method mm : c.getDeclaredMethods()) {
                    if (mm.getName().equals(name) && mm.getParameterCount() == 0) return mm;
                }
            }
            return null;
        }

        //识别简单 getter：实例方法 ALOD+GETFIELD+RETURN / 静态方法 GETSTATIC+RETURN
        private Expr tryInlineSimpleGetter(MethodInsnNode m, List<? extends TaintValue> values) {
            if (m.name.startsWith("<")) return null;
            if (m.owner.startsWith("java/") || m.owner.startsWith("javax/") || m.owner.startsWith("jdk/")) return null;
            if (TABLE.lookupCall(m.owner, m.name, m.desc) != null) return null;
            boolean isStatic = m.getOpcode() == Opcodes.INVOKESTATIC;
            // 实例 getter：无参 + 有 receiver；静态 getter：无参即可
            if (!isStatic && (Type.getArgumentTypes(m.desc).length != 0 || values.isEmpty())) return null;
            if (isStatic && Type.getArgumentTypes(m.desc).length != 0) return null;
            // 静态方法无需 receiver，实例方法从 values[0] 取
            Expr receiver = isStatic ? EntityParamMarker.I : values.get(0).expr;
            if (receiver == null || receiver instanceof UnknownExpr) return null;
            try {
                Class<?> owner = loadClass(m.owner);
                if (owner == null) return null;
                byte[] bytes = classBytes(owner);
                if (bytes == null) return null;
                ClassNode cn = new ClassNode();
                new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
                for (MethodNode method : cn.methods) {
                    if (!method.name.equals(m.name) || !method.desc.equals(m.desc)) continue;
                    if (isStatic) {
                        FieldInsnNode sf = simpleStaticGetterField(method);
                        if (sf == null) return null;
                        return buildStaticFieldSource(sf);
                    }
                    FieldInsnNode field = simpleGetterField(method);
                    if (field == null) return null;
                    return buildGetFieldSource(field, receiver);
                }
                return null;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return null;
            }
        }

        //识别 ALOD+GETFIELD+RETURN(3 指令) 的实例简单 getter
        private FieldInsnNode simpleGetterField(MethodNode method) {
            List<AbstractInsnNode> ops = meaningfulInstructions(method);
            if (ops.size() != 3) return null;
            if (ops.get(0).getOpcode() != Opcodes.ALOAD) return null;
            if (!(ops.get(1) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD) return null;
            if (!isReturnOpcode(ops.get(2).getOpcode())) return null;
            Type returnType = Type.getReturnType(method.desc);
            if (!field.desc.equals(returnType.getDescriptor())) return null;
            return field;
        }

        //识别 GETSTATIC+RETURN(2 指令) 的静态简单 getter
        private FieldInsnNode simpleStaticGetterField(MethodNode method) {
            List<AbstractInsnNode> ops = meaningfulInstructions(method);
            if (ops.size() != 2) return null;
            if (!(ops.get(0) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC) return null;
            if (!isReturnOpcode(ops.get(1).getOpcode())) return null;
            Type returnType = Type.getReturnType(method.desc);
            if (!field.desc.equals(returnType.getDescriptor())) return null;
            return field;
        }

        private static List<AbstractInsnNode> meaningfulInstructions(MethodNode method) {
            List<AbstractInsnNode> ops = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() >= 0) ops.add(insn);
            }
            return ops;
        }

        private static boolean isReturnOpcode(int opcode) {
            return opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN
                    || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN;
        }

        private Expr tryInline(MethodInsnNode m, List<? extends TaintValue> values) {
            Class<?> owner = loadClass(m.owner);
            if (owner == null) return null;
            if (shouldKeepAsRuntimeCall(owner, m.name, m.desc)) return null;
            /* 按实际声明类判定，而非调用点 owner。子类调用继承自原版基类的方法时调用点 owner 是子类，
               据此判断会绕过对原版方法的限制，展开属性系统等实现链，结果通常坍缩为 Unknown。 */
            Method jm = findAnyMethod(owner, m.name, m.desc);
            Class<?> declaring = jm == null ? owner : jm.getDeclaringClass();
            String declaringInternal = internalName(declaring);
            if (declaringInternal.startsWith("java/") || declaringInternal.startsWith("net/minecraft/")) {
                if (jm == null) return null;
                int mods = jm.getModifiers();
                if (!(Modifier.isFinal(mods) || Modifier.isStatic(mods) || Modifier.isPrivate(mods))) return null;
            }
            //全局熔断：内联次数上限(环检测已由 analyzeMethod 的 inflight 统一负责)
            if (ctx.inlineBudget <= 0) return new UnknownExpr("inlineBudget-exhausted");
            ctx.inlineBudget--;
            boolean isStatic = m.getOpcode() == Opcodes.INVOKESTATIC;
            Type[] argTypes = Type.getArgumentTypes(m.desc);
            int localCount = (isStatic ? 0 : 1);
            for (Type at : argTypes) localCount += at.getSize();
            TaintValue[] seed = new TaintValue[localCount + 8];
            int idx = 0, vidx = 0;
            if (!isStatic) seed[idx++] = values.get(vidx++);
            for (Type at : argTypes) {
                seed[idx] = values.get(vidx++);
                idx += at.getSize();
            }
            return analyzeMethod(owner, m.name, m.desc, seed, ctx, depth + 1);
        }

        @Override public void returnOperation(AbstractInsnNode insn, TaintValue value, TaintValue expected) {}

        @Override public TaintValue merge(TaintValue v1, TaintValue v2) {
            if (v1.equals(v2)) return v1;
            int size = Math.max(v1.size, v2.size);
            if (--ctx.nodeBudget <= 0) return new TaintValue(size, new UnknownExpr("merge-nodeBudget-exhausted"));
            List<Expr> alts = new ArrayList<>();
            addAlt(alts, v1.expr);
            addAlt(alts, v2.expr);
            //加宽：分支过多直接坍缩 Unknown，让格有限高、循环处数据流收敛
            if (alts.size() > MAX_CHOICE_ALTS) return new TaintValue(size, new UnknownExpr("merge-tooManyAlternatives(" + alts.size() + ">" + MAX_CHOICE_ALTS + ")"));
            if (alts.size() == 1) return new TaintValue(size, alts.get(0));
            return new TaintValue(size, new Choice(alts));
        }

        private static void addAlt(List<Expr> into, Expr e) {
            if (e instanceof Choice c) {
                for (Expr a : c.alternatives()) if (!into.contains(a)) into.add(a);
            } else if (!into.contains(e)) into.add(e);
        }

        private Expr buildGetFieldSource(FieldInsnNode f, Expr receiver) {
            FieldStep step = new FieldStep(f.owner, f.name, f.desc);
            if (receiver == EntityParamMarker.I) {
                return makeFieldChain(List.of(step));
            }
            if (receiver instanceof FieldChainSource fcs) {
                List<FieldStep> ext = new ArrayList<>(fcs.chain);
                ext.add(step);
                return makeFieldChain(ext);
            }
            if (receiver instanceof ChainedFieldSource cfs) {
                List<FieldStep> ext = new ArrayList<>(cfs.chain);
                ext.add(step);
                Class<?> vt = descriptorToClass(step.desc());
                return new ChainedFieldSource(cfs.root, ext, vt == null ? Object.class : vt);
            }
            if (receiver instanceof CapabilityDataSource capability) {
                List<FieldStep> ext = new ArrayList<>(capability.chain);
                ext.add(step);
                Class<?> vt = descriptorToClass(step.desc());
                return new CapabilityDataSource(capability.containerExpr, capability.keyExpr, ext,
                        vt == null ? Object.class : vt);
            }
            if (receiver instanceof UnknownExpr || receiver == null) {
                return UnknownExpr.UNKNOWN;
            }
            // receiver 是 MapEntrySource / ArrayElementSource / Reference / Call / Op：启动非 this 字段链
            Class<?> vt = descriptorToClass(step.desc());
            return new ChainedFieldSource(receiver, List.of(step), vt == null ? Object.class : vt);
        }

        private Expr makeFieldChain(List<FieldStep> chain) {
            try {
                VarHandle[] handles = new VarHandle[chain.size()];
                Class<?> lastType = null;
                for (int i = 0; i < chain.size(); i++) {
                    FieldStep s = chain.get(i);
                    Class<?> owner = loadClass(s.ownerInternal());
                    if (owner == null) return new UnknownExpr("fieldChain-ownerNotFound-" + s.ownerInternal());
                    Class<?> ft = descriptorToClass(s.desc());
                    if (ft == null) return new UnknownExpr("fieldChain-typeNotFound-" + s.desc());
                    Field field = findFieldInHierarchy(owner, s.name());
                    if (field == null) return new UnknownExpr("fieldChain-fieldNotFound-" + s.name());
                    Class<?> declaring = field.getDeclaringClass();
                    MethodHandles.Lookup lk = MethodHandles.privateLookupIn(declaring, MethodHandles.lookup());
                    handles[i] = lk.findVarHandle(declaring, s.name(), ft);
                    lastType = ft;
                }
                return new FieldChainSource(chain, handles, lastType);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
                return new UnknownExpr("fieldChain-VarHandle-failed");
            }
        }

        //从 GETSTATIC FieldInsnNode 构建静态字段 Source；final static 常量折叠为 Reference
        private Expr buildStaticFieldSource(FieldInsnNode field) {
            try {
                Class<?> owner = loadClass(field.owner);
                if (owner == null) return null;
                Field f = findFieldInHierarchy(owner, field.name);
                if (f == null) return null;
                f.setAccessible(true);
                if (Modifier.isFinal(f.getModifiers())) {
                    Object value = f.get(null);
                    return value == null ? null : new Reference(value, field.owner);
                }
                return new StaticFieldSource(f);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return null;
            }
        }

        private Expr resolveStaticField(String ownerInternal, String name) {
            try {
                Class<?> owner = loadClass(ownerInternal);
                if (owner == null) return null;
                Field f = findFieldInHierarchy(owner, name);
                if (f == null) return null;
                f.setAccessible(true);
                if (!Modifier.isFinal(f.getModifiers())) return new StaticFieldSource(f);
                Object value = f.get(null);
                return value == null ? null : new Reference(value, ownerInternal);
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
        }
    }

    //从 Map.get 的 receiver 表达式推断容器所属类(用于兄弟表扫描),无法确定返回 null
    private static String mapOwnerHint(Expr container) {
        if (container instanceof Reference ref) return ref.className();
        if (container instanceof Call call) return call.owner();
        return null;
    }

    /* ==================== 工具 ==================== */

    private static TaintValue primI(ConstProvenance p, int v) { return new TaintValue(1, new Primitive(v, 'I', p)); }
    private static TaintValue primL(ConstProvenance p, long v) { return new TaintValue(2, new Primitive(v, 'J', p)); }
    private static TaintValue primF(ConstProvenance p, float v) { return new TaintValue(1, new Primitive(v, 'F', p)); }
    private static TaintValue primD(ConstProvenance p, double v) { return new TaintValue(2, new Primitive(v, 'D', p)); }

    private static TaintValue ldcValue(ConstProvenance p, LdcInsnNode insn) {
        Object cst = insn.cst;
        int sz = (cst instanceof Long || cst instanceof Double) ? 2 : 1;
        if (cst instanceof Integer i) return new TaintValue(sz, new Primitive(i, 'I', p));
        if (cst instanceof Long l) return new TaintValue(sz, new Primitive(l, 'J', p));
        if (cst instanceof Float f) return new TaintValue(sz, new Primitive(f, 'F', p));
        if (cst instanceof Double d) return new TaintValue(sz, new Primitive(d, 'D', p));
        if (cst instanceof String s) return new TaintValue(sz, new Reference(s, "java/lang/String"));
        //类字面量 X.class：解析为具体 Class 引用，供反射 getDeclaredMethod/Field 定位 owner
        if (cst instanceof Type tp && (tp.getSort() == Type.OBJECT || tp.getSort() == Type.ARRAY)) {
            Class<?> k = loadClass(tp.getInternalName());
            if (k != null) return new TaintValue(sz, new Reference(k, "java/lang/Class"));
        }
        return new TaintValue(sz, UnknownExpr.UNKNOWN);
    }

    private static boolean isMapClassByName(String internal) {
        if (internal.equals("java/util/Map") || internal.equals("java/util/HashMap")
            || internal.equals("java/util/concurrent/ConcurrentHashMap") || internal.equals("java/util/WeakHashMap")
            || internal.equals("java/util/LinkedHashMap") || internal.equals("java/util/IdentityHashMap")
            || internal.equals("java/util/TreeMap")) return true;
        if (internal.endsWith("Map") || internal.endsWith("HashMap")) return true;
        Class<?> c = loadClass(internal);
        return c != null && Map.class.isAssignableFrom(c);
    }

    private static boolean isCapabilityValueGet(MethodInsnNode method) {
        if (!method.name.equals("getValue")) return false;
        Type[] args = Type.getArgumentTypes(method.desc);
        if (args.length != 1 || Type.getReturnType(method.desc) == Type.VOID_TYPE) return false;
        Class<?> owner = loadClass(method.owner);
        Class<?> keyType = asmTypeToClass(args[0]);
        if (owner == null || keyType == null) return false;
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method candidate : c.getDeclaredMethods()) {
                if (!candidate.getName().equals("setValue")) continue;
                Class<?>[] params = candidate.getParameterTypes();
                if (params.length != 2) continue;
                if (boxedType(params[0]).isAssignableFrom(boxedType(keyType))
                        || boxedType(keyType).isAssignableFrom(boxedType(params[0]))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static MapEntrySource.KeyKind detectKeyKind(Expr key) {
        if (key == EntityParamMarker.I) return MapEntrySource.KeyKind.ENTITY;
        if (key instanceof Call call && call.args().size() == 1 && call.args().get(0) == EntityParamMarker.I) {
            if (call.name().equals("m_19879_") || call.name().equals("getId")) return MapEntrySource.KeyKind.ENTITY_ID;
            if (call.name().equals("m_20148_") || call.name().equals("getUUID")) return MapEntrySource.KeyKind.ENTITY_UUID;
        }
        return MapEntrySource.KeyKind.UNKNOWN;
    }

    private static Class<?> guessArrayElementType(int loadOp) {
        return switch (loadOp) {
            case Opcodes.IALOAD -> int.class;
            case Opcodes.LALOAD -> long.class;
            case Opcodes.FALOAD -> float.class;
            case Opcodes.DALOAD -> double.class;
            case Opcodes.BALOAD -> byte.class;
            case Opcodes.CALOAD -> char.class;
            case Opcodes.SALOAD -> short.class;
            default -> Object.class;
        };
    }

    //从内部名加载类：依次尝试上下文类加载器、系统类加载器、本类加载器，确保模组类能被定位
    static Class<?> loadClass(String internalName) {
        String className = internalName.replace('/', '.');
        Throwable last = null;
        for (ClassLoader cl : new ClassLoader[]{
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                HealthDataflowAnalyzer.class.getClassLoader()
        }) {
            try { return Class.forName(className, false, cl); }
            catch (Throwable t) {
                if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
                last = t;
            }
        }
        //终极回退：不指定类加载器(使用调用类的加载器)
        try { return Class.forName(className); }
        catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; }
        return null;
    }

    private static Method findAnyMethod(Class<?> owner, String name, String desc) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) return m;
            }
        }
        return null;
    }

    static Method findMethod(Class<?> owner, String name, Class<?>[] paramTypes, Object[] argValues) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] mp = m.getParameterTypes();
                if (mp.length != paramTypes.length) continue;
                boolean match = true;
                for (int i = 0; i < mp.length; i++) {
                    if (!methodArgMatches(mp[i], paramTypes[i], argValues == null ? null : argValues[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) return m;
            }
        }
        return findInterfaceMethod(owner, name, paramTypes, argValues);
    }

    private static Method findInterfaceMethod(Class<?> owner, String name, Class<?>[] paramTypes, Object[] argValues) {
        for (Class<?> iface : allInterfaces(owner)) {
            for (Method method : iface.getMethods()) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != paramTypes.length) continue;
                boolean match = true;
                for (int i = 0; i < params.length; i++) {
                    if (!methodArgMatches(params[i], paramTypes[i], argValues == null ? null : argValues[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) return method;
            }
        }
        return null;
    }

    private static Method findMethodByRuntimeArgs(Class<?> owner, String name, Object[] argValues) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != argValues.length) continue;
                boolean match = true;
                for (int i = 0; i < params.length; i++) {
                    Object arg = argValues[i];
                    if (arg == null) {
                        if (params[i].isPrimitive()) {
                            match = false;
                            break;
                        }
                        continue;
                    }
                    Class<?> boxedParam = boxedType(params[i]);
                    if (!boxedParam.isAssignableFrom(arg.getClass()) && coerceArg(arg, params[i]) == arg) {
                        match = false;
                        break;
                    }
                }
                if (match) return method;
            }
        }
        for (Class<?> iface : allInterfaces(owner)) {
            for (Method method : iface.getMethods()) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != argValues.length) continue;
                boolean match = true;
                for (int i = 0; i < params.length; i++) {
                    if (!runtimeArgMatches(params[i], argValues[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) return method;
            }
        }
        return null;
    }

    private static Set<Class<?>> allInterfaces(Class<?> type) {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) collectInterfaces(c, result);
        return result;
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> result) {
        for (Class<?> iface : type.getInterfaces()) {
            if (result.add(iface)) collectInterfaces(iface, result);
        }
    }

    private static boolean runtimeArgMatches(Class<?> param, Object arg) {
        if (arg == null) return !param.isPrimitive();
        Class<?> boxedParam = boxedType(param);
        return boxedParam.isAssignableFrom(arg.getClass()) || coerceArg(arg, param) != arg;
    }

    static boolean methodArgMatches(Class<?> methodParam, Class<?> descriptorParam, Object value) {
        Class<?> boxedMethod = boxedType(methodParam);
        Class<?> boxedDescriptor = boxedType(descriptorParam);
        if (boxedMethod.equals(boxedDescriptor) || boxedMethod.isAssignableFrom(boxedDescriptor)) return true;
        if (value == null) return !methodParam.isPrimitive();
        return boxedMethod.isAssignableFrom(value.getClass()) || coerceArg(value, methodParam) != value;
    }

    private static Class<?> boxedType(Class<?> type) {
        if (type == null || !type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == void.class) return Void.class;
        return type;
    }

    static Class<?> asmTypeToClass(Type t) {
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
                catch (Throwable e) { if (e instanceof VirtualMachineError) throw (VirtualMachineError) e; yield null; }
            }
            default -> null;
        };
    }

    static Class<?> descriptorToClass(String d) {
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
            case 'L' -> loadClass(d.substring(1, d.length() - 1));
            case '[' -> loadClass(d);
            default -> null;
        };
    }

    static Object coerceForType(Object v, Class<?> type) {
        if (v == null) return null;
        if (type == float.class || type == Float.class)
            return v instanceof Number n ? n.floatValue() : null;
        if (type == double.class || type == Double.class)
            return v instanceof Number n ? n.doubleValue() : null;
        if (type == int.class || type == Integer.class)
            return v instanceof Number n ? n.intValue() : null;
        if (type == long.class || type == Long.class)
            return v instanceof Number n ? n.longValue() : null;
        if (type == short.class || type == Short.class)
            return v instanceof Number n ? n.shortValue() : null;
        if (type == byte.class || type == Byte.class)
            return v instanceof Number n ? n.byteValue() : null;
        if (type == char.class || type == Character.class)
            return v instanceof Number n ? (char) n.intValue() : null;
        if (type == String.class) return v.toString();
        return v;
    }

    static Object coerceSameType(Object reference, Object value) {
        if (reference == null) return value;
        if (reference instanceof Float) return value instanceof Number n ? n.floatValue() : null;
        if (reference instanceof Double) return value instanceof Number n ? n.doubleValue() : null;
        if (reference instanceof Integer) return value instanceof Number n ? n.intValue() : null;
        if (reference instanceof Long) return value instanceof Number n ? n.longValue() : null;
        if (reference instanceof Short) return value instanceof Number n ? n.shortValue() : null;
        if (reference instanceof Byte) return value instanceof Number n ? n.byteValue() : null;
        if (reference instanceof String) return value == null ? null : value.toString();
        return value;
    }

    public static Object coerceArgPublic(Object v, Class<?> targetType) {
        return coerceArg(v, targetType);
    }

    private static Object coerceArg(Object v, Class<?> targetType) {
        if (v == null) return targetType.isPrimitive() ? defaultPrim(targetType) : null;
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

    private static Object defaultPrim(Class<?> t) {
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

    private static Object readField(Object target, FieldStep step) {
        try {
            Class<?> owner = loadClass(step.ownerInternal());
            if (owner == null) return null;
            Field f = findFieldInHierarchy(owner, step.name());
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
    }

    static Field findFieldInHierarchy(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /* 求值上下文：携带当前实体实例，让 solve/evaluate 现读其字段值代入演算(只读不写) */
    public record SimpleEvalContext(LivingEntity entity) implements EvalContext {
        @Override public Object eval(Expr e) { return evaluate(e, this); }
    }

    /* 公开求值上下文工厂：供写入侧/外部模块在反演前为某个实体构造一个轻量上下文 */
    public static EvalContext newContext(LivingEntity entity) {
        return new SimpleEvalContext(entity);
    }

    /* 公开反演入口，行为等同于 solveDetailed。
       输入 IR 根 + 一个候选 sink + 目标值 + 求值上下文，返回 sink 应被写入的具体值或失败枚举。 */
    public static HealthSolveResult buildWritePath(Expr root, Source sink, Object target, EvalContext ctx) {
        return solveDetailed(root, sink, target, ctx);
    }

    /* Path-insensitive control flow can expose several writes to the same storage location. Keep
       every representable value so the landing layer can validate complete transactions. */
    public static List<Object> buildWriteCandidates(Expr root, Source sink, Object target,
                                                    EvalContext ctx, int limit) {
        if (root == null || sink == null || ctx == null || limit <= 0) return List.of();
        List<Object> candidates = new ArrayList<>();
        collectWriteCandidates(root, sink, target, ctx, limit, candidates);
        return List.copyOf(candidates);
    }

    private static void collectWriteCandidates(Expr root, Source sink, Object target, EvalContext ctx,
                                               int limit, List<Object> candidates) {
        if (candidates.size() >= limit) return;
        if (root instanceof Choice choice) {
            for (Expr alternative : choice.alternatives()) {
                if (candidates.size() >= limit) return;
                if (containsSink(alternative, sink)) {
                    collectWriteCandidates(alternative, sink, target, ctx, limit, candidates);
                }
            }
            return;
        }
        HealthSolveResult result = solveDetailed(root, sink, target, ctx);
        if (!result.solved() || result.value() == null) return;
        for (Object candidate : candidates) {
            if (Objects.equals(candidate, result.value())) return;
        }
        candidates.add(result.value());
    }
}
