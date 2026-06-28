package net.eca.util.health;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.eca.agent.EcaAgent;
import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.eca.util.reflect.UnsafeUtil;
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

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 字节码反向数据流分析 + 对偶表求逆
 * 从 getHealth 的 FRETURN 反向遍历指令序列构造 Expr 树,然后按 DualityTable 反推 sink 应写值
 * Expr 节点覆盖常量、运算、调用、闭包、存储位置与控制流汇合，所有类型和运算差异，全部交给 DualityTable 的方法对偶对处理,不再按 leaf 类型展开
 */
public final class HealthDataflowAnalyzer {

    private static final String GETHEALTH_NAME = "m_21223_";
    private static final String GETHEALTH_NAME_ALT = "getHealth";
    private static final String GETHEALTH_DESC = "()F";
    private static final int DEFAULT_MAX_DEPTH = 6;
    private static final int DEFAULT_INLINE_BUDGET = 500;
    //表达式节点总预算：构造组合表达式时递减，耗尽即坍缩为 Unknown，防止复杂/互递归 getHealth 把表达式树撑爆导致分析卡死
    private static final int DEFAULT_NODE_BUDGET = 500_000;
    //控制流汇合处 Choice 分支上限，超出即加宽为 Unknown，保证格有限高、数据流分析收敛
    private static final int MAX_CHOICE_ALTS = 16;
    private static final String ECA_LIVING_HOOK = "net/eca/coremod/LivingEntityHook";
    private static final String ECA_LOCK_MANAGER = "net/eca/util/health/HealthLockManager";
    private static final String ISALIVE_NAME = "m_6084_";
    private static final String ISALIVE_NAME_ALT = "isAlive";
    private static final String ISDEAD_NAME = "m_21224_";
    private static final String ISDEAD_NAME_ALT = "isDeadOrDying";
    private static final String BOOL_DESC = "()Z";
    private static final String HURT_NAME = "m_6469_";
    private static final String HURT_NAME_ALT = "hurt";
    private static final String ACTUALLY_HURT_NAME = "m_6475_";
    private static final String ACTUALLY_HURT_NAME_ALT = "actuallyHurt";
    private static final String DAMAGE_SOURCE_DESC = "Lnet/minecraft/world/damagesource/DamageSource;";
    private static final String HURT_DESC = "(" + DAMAGE_SOURCE_DESC + "F)Z";
    private static final String ACTUALLY_HURT_DESC = "(" + DAMAGE_SOURCE_DESC + "F)V";

    /* ==================== 外部扫描：isAlive/isDeadOrDying 数据流逆向 ==================== */

    private static final Map<Class<?>, AnalysisResult> EXTERNAL_SCAN_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> EXTERNAL_SCAN_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> EXTERNAL_WRITE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> STORE_WRITE_DUMPED = ConcurrentHashMap.newKeySet();

    private HealthDataflowAnalyzer() {}

    @FunctionalInterface
    private interface HealthVerifier {
        boolean verify(LivingEntity entity, float expected, Source sink);
    }

    /* ==================== 对外：产出可重放改血路径 ==================== */

    //每个实体类只打印一次首次分析树 / 一次失败结果，避免每-tick 改血刷屏
    private static final Set<String> TREE_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAIL_DUMPED = ConcurrentHashMap.newKeySet();
    //每个方法只打印一次 totalReturns 诊断，避免同一 getHealth 反复分析刷屏
    private static final Set<String> METHOD_DIAG_DUMPED = ConcurrentHashMap.newKeySet();

    //分析实体类 getHealth，符号反演定位真实血量存储，产出可重放 HealthPath；无法识别返回 null
    public static EcaSetHealthManager.HealthPath resolvePath(Class<?> entityClass) {
        if (entityClass == null) return null;
        AnalysisResult ar;
        try {
            ar = analyze(entityClass);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
        if (TREE_DUMPED.add(entityClass.getName())) dumpTree(entityClass, ar);   // 首次分析打印完整逆向树
        if (ar == null) return null;
        /* SOLVABLE 必走 dataflow 真写存储。HAS_CONSTANT 若同时含可写源(如 getHealth 带 try/catch 兜底常数、
           真血却存在加密/SynchedData 源里)也产出 dataflow 路径，优先真写源——写不动或 verify 不过会回滚返回 false，
           降级链再交给 setHealthByConstOverride。真写源能覆盖所有读取方(isAlive/isDeadOrDying 等)，
           而常数覆盖只动 getHealth 返回值，对绕过 getHealth 的实体无效。CONSTANT/OPAQUE 不在此处理。 */
        AnalysisResult.Kind kind = ar.classify();
        boolean dataflowEligible = kind == AnalysisResult.Kind.SOLVABLE
                || (kind == AnalysisResult.Kind.HAS_CONSTANT && !ar.sources.isEmpty());
        if (!dataflowEligible) return null;
        return new EcaSetHealthManager.HealthPath(EcaSetHealthManager.WriteMethod.DATAFLOW,
                (entity, target) -> writeViaSources(entityClass, ar, entity, target,
                        (verifiedEntity, verifiedTarget, sink) ->
                                EcaSetHealthManager.verify(verifiedEntity, verifiedTarget)));
    }

    /* 对 CONSTANT 实体类触发同步 retransform，使 EcaClassTransformer 内联 resolveHealth 调用。
       优先走 JVM TI 原生通道（激进防御时），回退到 Instrumentation（常规路径）。失败仅记日志。 */
    static void retransformClass(Class<?> entityClass) {
        // JVM TI 原生通道（激进防御开启时生效）
        net.eca.coremod.JvmTiChannel.retransformClasses(entityClass);

        // Instrumentation 常规通道
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            EcaLogger.info("[HealthDataflow] retransform {} skipped: Instrumentation unavailable", entityClass.getName());
            return;
        }
        if (!inst.isModifiableClass(entityClass)) {
            EcaLogger.info("[HealthDataflow] retransform {} skipped: class not modifiable", entityClass.getName());
            return;
        }
        try {
            inst.retransformClasses(entityClass);
            EcaLogger.info("[HealthDataflow] retransform {} completed", entityClass.getName());
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[HealthDataflow] retransform {} failed: {}", entityClass.getName(), t.toString());
        }
    }

    //逐个验证候选 Source，单点均未命中时再联合写入；失败尝试恢复原值
    private static boolean writeViaSources(Class<?> cls, AnalysisResult ar, LivingEntity entity, float expected,
                                           HealthVerifier verifier) {
        EvalContext ctx = new EvalCtx(entity);
        List<String> diag = new ArrayList<>();
        List<PreparedSourceWrite> solvedWrites = new ArrayList<>();
        for (Source sink : ar.sources) {
            HealthSolveResult solved = solveDetailed(ar.returnExpr, sink, Float.valueOf(expected), ctx);
            if (!solved.solved() || solved.value() == null) {
                diag.add("    [" + sink.label + "] solve=FAIL " + solved.failure() + " (" + solved.detail() + ")");
                continue;
            }

            Object snapshot = sink.read(entity);
            solvedWrites.add(new PreparedSourceWrite(sink, snapshot, solved.value()));   // 留给联合写入复用，免重算
            if (!sink.write(entity, solved.value())) {
                boolean restored = sink.write(entity, snapshot);
                diag.add("    [" + sink.label + "] solved=" + solved.value()
                        + " write=FAIL restore=" + (restored ? "OK" : "FAIL"));
                continue;
            }
            if (verifier.verify(entity, expected, sink)) {
                EcaLogger.info("[HealthDataflow] setHealth success entity={} sink={} solved={} expected={}",
                        cls.getName(), sink.label, solved.value(), expected);
                return true;
            }

            boolean restored = sink.write(entity, snapshot);
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

    /* ≥2 个可解 Source 时联合写入（应对双源防御等需同时写多处的形态），失败逆序回滚。
       writes 由单源循环收集，其 snapshot 均为原值（循环对每次尝试都已回滚），故回滚即复原。 */
    private static boolean writeAllSources(List<PreparedSourceWrite> writes, LivingEntity entity, float expected,
                                           List<String> diag, HealthVerifier verifier) {
        if (writes.size() < 2) return false;

        boolean wroteAll = true;
        for (PreparedSourceWrite write : writes) {
            if (!write.sink().write(entity, write.value())) {
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
            if (!write.sink().write(entity, write.snapshot())) restoredAll = false;
        }
        diag.add("    [all sources] write=" + (wroteAll ? "OK" : "FAIL")
                + " verify=FAIL restore=" + (restoredAll ? "OK" : "FAIL"));
        return false;
    }

    private record PreparedSourceWrite(Source sink, Object snapshot, Object value) {}

    private static boolean verifyExternalDataflow(Expr root, LivingEntity entity, float expected, Source sink) {
        if (entity == null) return false;
        boolean healthLikeSink = isExternalHealthLikeSink(sink);
        if (expected <= 0.0f) {
            return healthLikeSink && (!entity.isAlive() || entity.isDeadOrDying());
        }
        float actual = safeGetHealth(entity);
        if (Float.isFinite(actual)) {
            float tolerance = Math.max(0.5f, Math.abs(expected) * 0.02f);
            if (Math.abs(actual - expected) <= tolerance) return true;
        }
        return healthLikeSink && verifiesExternalExpression(root, entity, expected, sink);
    }

    private static boolean isExternalHealthLikeSink(Source sink) {
        return sink instanceof SynchedDataSource || sink instanceof MapEntrySource || sink instanceof CapabilityDataSource;
    }

    private static boolean verifiesExternalExpression(Expr expr, LivingEntity entity, float expected, Source sink) {
        if (expr == null || sink == null || !containsSink(expr, sink)) return false;
        if (expr instanceof Choice choice) {
            for (Expr alternative : choice.alternatives()) {
                if (verifiesExternalExpression(alternative, entity, expected, sink)) return true;
            }
            return false;
        }
        Object value = evaluate(expr, new EvalCtx(entity));
        if (!(value instanceof Number number)) return false;
        float actual = number.floatValue();
        if (!Float.isFinite(actual)) return false;
        float tolerance = Math.max(0.5f, Math.abs(expected) * 0.02f);
        return Math.abs(actual - expected) <= tolerance || Math.abs(Math.abs(actual) - expected) <= tolerance;
    }

    private static float safeGetHealth(LivingEntity entity) {
        try {
            return entity.getHealth();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Float.NaN;
        }
    }

    private record EvalCtx(LivingEntity entity) implements EvalContext {
        @Override public Object eval(Expr e) { return evaluate(e, this); }
    }

    //每个实体类首次分析后打印完整 Expr 逆向树 + 定位到的 Source（无行数上限）
    private static void dumpTree(Class<?> cls, AnalysisResult ar) {
        EcaLogger.info("[HealthDataflow] === first analysis: {} ===", cls.getName());
        if (ar == null || ar.isEmpty()) {
            EcaLogger.info("[HealthDataflow]   kind={} EMPTY — getHealth 未覆写或字节码无法符号化",
                    ar == null ? "null" : ar.classify());
            return;
        }
        EcaLogger.info("[HealthDataflow]   kind={} sources={} definingClass={}", ar.classify(), ar.sources.size(),
                ar.definingClass != null ? ar.definingClass.getName() : "null");
        EcaLogger.info("[HealthDataflow]   hasConstantBranch={}", hasConstantBranch(ar.returnExpr));
        logUnknownNodes(ar.returnExpr);
        EcaLogger.info("[HealthDataflow]   returnExpr:");
        List<String> tree = new ArrayList<>();
        appendExpr(tree, ar.returnExpr, "    ");
        for (String line : tree) EcaLogger.info("[HealthDataflow] {}", line);
        EcaLogger.info("[HealthDataflow]   source list:");
        int i = 0;
        for (Source s : ar.sources) {
            EcaLogger.info("[HealthDataflow]     #{} {}  type={}", i++, s.label, s.valueType.getName());
        }
    }

    /* 收集所有 UnknownExpr 并打印其 provenance，使分析边界一目了然 */
    private static void logUnknownNodes(Expr e) {
        List<String> unknowns = new ArrayList<>();
        collectUnknowns(e, unknowns);
        if (!unknowns.isEmpty()) {
            for (String u : unknowns) EcaLogger.info("[HealthDataflow]   Unknown: {}", u);
        }
    }
    private static void collectUnknowns(Expr e, List<String> out) {
        if (e instanceof UnknownExpr u) { out.add(u.provenance().isEmpty() ? "<empty>" : u.provenance()); return; }
        if (e instanceof Op op) { for (Expr a : op.args()) collectUnknowns(a, out); }
        else if (e instanceof Call call) { for (Expr a : call.args()) collectUnknowns(a, out); }
        else if (e instanceof Closure cl) { for (Expr a : cl.captured()) collectUnknowns(a, out); }
        else if (e instanceof StoreWrite sw) collectUnknowns(sw.valueExpr(), out);
        else if (e instanceof Choice ch) { for (Expr a : ch.alternatives()) collectUnknowns(a, out); }
        else if (e instanceof OptionalContentExpr o) collectUnknowns(o.optionalExpr(), out);
    }

    //递归把 Expr 树缩进展开，覆盖全部节点类型，无行数上限
    private static void appendExpr(List<String> out, Expr e, String indent) {
        if (e == null) { out.add(indent + "<null>"); return; }
        if (e == thisMarker()) { out.add(indent + "this"); return; }
        if (e instanceof Source s) { out.add(indent + "Source " + s.label + " (type=" + s.valueType.getName() + ")"); return; }
        if (e instanceof WriteInput input) { out.add(indent + "WriteInput #" + input.index() + " [" + input.jvmType() + "]"); return; }
        if (e instanceof StoreWrite write) {
            out.add(indent + "StoreWrite -> " + write.sink().label + " (type=" + write.sink().valueType.getName() + ")");
            appendExpr(out, write.valueExpr(), indent + "  value ");
            return;
        }
        if (e instanceof Primitive p) { out.add(indent + "Primitive " + p.value() + " [" + p.jvmType() + "]"); return; }
        if (e instanceof Reference r) { out.add(indent + "Reference(" + r.className() + ")"); return; }
        if (e instanceof Op op) {
            out.add(indent + "Op " + opcodeName(op.opcode()));
            for (Expr a : op.args()) appendExpr(out, a, indent + "  ");
            return;
        }
        if (e instanceof Call call) {
            out.add(indent + "Call " + call.owner() + "#" + call.name() + call.desc());
            for (Expr a : call.args()) appendExpr(out, a, indent + "  ");
            return;
        }
        if (e instanceof Closure cl) {
            out.add(indent + "Closure " + cl.samName() + cl.samDesc());
            for (Expr a : cl.captured()) appendExpr(out, a, indent + "  ");
            return;
        }
        if (e instanceof ArrayAllocExpr a) {
            out.add(indent + "ArrayAlloc #" + a.id());
            return;
        }
        if (e instanceof OptionalContentExpr o) {
            out.add(indent + "OptionalContent");
            appendExpr(out, o.optionalExpr(), indent + "  ");
            return;
        }
        if (e instanceof Choice ch) {
            List<Expr> alts = ch.alternatives();
            out.add(indent + "Choice (" + alts.size() + " alt" + (alts.size() > 1 ? "s" : "") + ")");
            for (int i = 0; i < alts.size(); i++) {
                Expr a = alts.get(i);
                boolean isConst = hasConstantBranch(a);
                out.add(indent + "  [" + i + "] constant=" + isConst);
                appendExpr(out, a, indent + "    ");
            }
            return;
        }
        if (e instanceof UnknownExpr u) {
            out.add(indent + "Unknown" + (u.provenance().isEmpty() ? "" : " [" + u.provenance() + "]"));
            return;
        }
        out.add(indent + e.getClass().getSimpleName());
    }

    //JVM 算术/位/类型转换 opcode 转可读名，未知回退 op#<n>
    private static String opcodeName(int op) {
        return switch (op) {
            case Opcodes.IADD -> "IADD"; case Opcodes.LADD -> "LADD"; case Opcodes.FADD -> "FADD"; case Opcodes.DADD -> "DADD";
            case Opcodes.ISUB -> "ISUB"; case Opcodes.LSUB -> "LSUB"; case Opcodes.FSUB -> "FSUB"; case Opcodes.DSUB -> "DSUB";
            case Opcodes.IMUL -> "IMUL"; case Opcodes.LMUL -> "LMUL"; case Opcodes.FMUL -> "FMUL"; case Opcodes.DMUL -> "DMUL";
            case Opcodes.IDIV -> "IDIV"; case Opcodes.LDIV -> "LDIV"; case Opcodes.FDIV -> "FDIV"; case Opcodes.DDIV -> "DDIV";
            case Opcodes.IREM -> "IREM"; case Opcodes.LREM -> "LREM"; case Opcodes.FREM -> "FREM"; case Opcodes.DREM -> "DREM";
            case Opcodes.INEG -> "INEG"; case Opcodes.LNEG -> "LNEG"; case Opcodes.FNEG -> "FNEG"; case Opcodes.DNEG -> "DNEG";
            case Opcodes.ISHL -> "ISHL"; case Opcodes.LSHL -> "LSHL"; case Opcodes.ISHR -> "ISHR"; case Opcodes.LSHR -> "LSHR";
            case Opcodes.IUSHR -> "IUSHR"; case Opcodes.LUSHR -> "LUSHR";
            case Opcodes.IAND -> "IAND"; case Opcodes.LAND -> "LAND"; case Opcodes.IOR -> "IOR"; case Opcodes.LOR -> "LOR";
            case Opcodes.IXOR -> "IXOR"; case Opcodes.LXOR -> "LXOR";
            case Opcodes.I2L -> "I2L"; case Opcodes.I2F -> "I2F"; case Opcodes.I2D -> "I2D";
            case Opcodes.L2I -> "L2I"; case Opcodes.L2F -> "L2F"; case Opcodes.L2D -> "L2D";
            case Opcodes.F2I -> "F2I"; case Opcodes.F2L -> "F2L"; case Opcodes.F2D -> "F2D";
            case Opcodes.D2I -> "D2I"; case Opcodes.D2L -> "D2L"; case Opcodes.D2F -> "D2F";
            case Opcodes.I2B -> "I2B"; case Opcodes.I2C -> "I2C"; case Opcodes.I2S -> "I2S";
            case Opcodes.LCMP -> "LCMP"; case Opcodes.FCMPL -> "FCMPL"; case Opcodes.FCMPG -> "FCMPG";
            case Opcodes.DCMPL -> "DCMPL"; case Opcodes.DCMPG -> "DCMPG";
            default -> "op#" + op;
        };
    }

    private static final Map<Class<?>, Long> ENTRY_VALUE_OFFSET_CACHE = new ConcurrentHashMap<>();

    /* ==================== Expr 类型系统 ==================== */

    public interface Expr {}

    //字面常量,jvmType 标记 IJFD/CSB/Z 等 JVM 类型字符
    public record Primitive(Number value, char jvmType) implements Expr {}

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

    //数据源(sink 候选),内部封装 read/write IO,equals 按 canonicalKey
    public static abstract class Source implements Expr {
        public final Class<?> valueType;
        public final String label;
        protected Source(Class<?> valueType, String label) {
            this.valueType = valueType;
            this.label = label;
        }
        public abstract Object read(LivingEntity entity);
        public abstract boolean write(LivingEntity entity, Object value);
        protected abstract String canonicalKey();
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
        private final VarHandle[] handles;

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

        @Override public boolean write(LivingEntity entity, Object value) {
            int n = handles.length;
            FieldStep last = chain.get(n - 1);
            Object coerced;
            try {
                coerced = coerceForType(value, valueType);
                if (coerced == null && valueType.isPrimitive()) return false;
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return false; }

            //最后一段属于 record:component 不可写(VarHandle/Unsafe 均被 JVM 拦),改为重建 record 写回上一级字段
            Class<?> leafOwner = loadClass(last.ownerInternal());
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
                } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return false; }
            }

            Object container;
            try {
                Object cur = entity;
                for (int i = 0; i < n - 1; i++) {
                    cur = handles[i].get(cur);
                    if (cur == null) return false;
                }
                container = cur;
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return false; }

            //普通字段:先走 VarHandle,失败(普通类的 final 字段)再走 Unsafe 兜底
            try {
                handles[n - 1].set(container, coerced);
                return true;
            } catch (Throwable ignored) {
                if (ignored instanceof VirtualMachineError) throw (VirtualMachineError) ignored;
                Field f = resolveField(last);
                if (f == null) return false;
                return UnsafeUtil.unsafePutField(container, f, coerced);
            }
        }

        @Override protected String canonicalKey() {
            StringBuilder sb = new StringBuilder("FC:");
            for (FieldStep s : chain) sb.append(s.ownerInternal()).append('.').append(s.name()).append(';');
            return sb.toString();
        }
    }

    public static final class StaticFieldSource extends Source {
        private final Field field;

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
        public boolean write(LivingEntity entity, Object value) {
            try {
                field.set(null, coerceForType(value, valueType));
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return false;
            }
        }

        @Override
        protected String canonicalKey() {
            return "SF:" + field.getDeclaringClass().getName() + "." + field.getName();
        }
    }

    //从 FieldStep 解析出 java.lang.reflect.Field,失败返回 null
    private static Field resolveField(FieldStep step) {
        try {
            Class<?> owner = loadClass(step.ownerInternal());
            if (owner == null) return null;
            Field f = findFieldInHierarchy(owner, step.name());
            if (f == null) return null;
            f.setAccessible(true);
            return f;
        } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
    }

    //重建 record 实例:目标 component 用新值,其余 component 保留 current 的旧值。失败返回 null
    private static Object rebuildRecord(Class<?> recordClass, Object current, String targetComponent, Object newValue) {
        try {
            RecordComponent[] comps = recordClass.getRecordComponents();
            Class<?>[] types = new Class<?>[comps.length];
            Object[] args = new Object[comps.length];
            for (int i = 0; i < comps.length; i++) {
                types[i] = comps[i].getType();
                if (comps[i].getName().equals(targetComponent)) {
                    args[i] = coerceForType(newValue, types[i]);
                } else if (current != null) {
                    Method acc = comps[i].getAccessor();
                    acc.setAccessible(true);
                    args[i] = acc.invoke(current);
                } else {
                    args[i] = types[i].isPrimitive() ? coerceForType(0, types[i]) : null;
                }
            }
            Constructor<?> ctor = recordClass.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
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

        @Override public boolean write(LivingEntity entity, Object value) {
            try {
                Object cur = evaluate(root, new SimpleEvalContext(entity));
                if (cur == null) return false;
                for (int i = 0; i < chain.size() - 1; i++) {
                    cur = readField(cur, chain.get(i));
                    if (cur == null) return false;
                }
                return writeField(cur, chain.get(chain.size() - 1), value);
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return false; }
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

        @Override public boolean write(LivingEntity entity, Object value) {
            try {
                EvalContext ctx = new SimpleEvalContext(entity);
                Object container = evaluate(containerExpr, ctx);
                Object key = evaluate(keyExpr, ctx);
                if (container == null || key == null) return false;
                if (chain.isEmpty()) return writeCapabilitySlot(container, key, value);

                Object slot = readCapabilitySlot(container, key);
                if (slot == null) return false;
                Object cur = slot;
                for (int i = 0; i < chain.size() - 1; i++) {
                    cur = readField(cur, chain.get(i));
                    if (cur == null) return false;
                }
                FieldStep leaf = chain.get(chain.size() - 1);
                if (!writeFieldViaSetter(cur, leaf, value) && !writeField(cur, leaf, value)) return false;
                writeCapabilitySlot(container, key, slot);
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
                return false;
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

        private static boolean writeCapabilitySlot(Object container, Object key, Object value) {
            return invokeCompatible(container, "setValue", key, value) != InvokeFailed.INSTANCE;
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

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public boolean write(LivingEntity entity, Object value) {
            try {
                SynchedEntityData ed = entity.getEntityData();
                Int2ObjectMap<?> map = (Int2ObjectMap<?>) ed.itemsById;
                SynchedEntityData.DataItem item = (SynchedEntityData.DataItem) map.get(accessor.getId());
                if (item == null) return false;
                Object coerced = coerceSameType(item.value, value);
                if (coerced == null) return false;
                item.value = coerced;
                item.dirty = true;
                ed.isDirty = true;
                entity.onSyncedDataUpdated(accessor);
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return false; }
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

        @Override public Object read(LivingEntity entity) {
            try {
                Object obj = evaluate(containerExpr, new SimpleEvalContext(entity));
                if (!(obj instanceof Map<?, ?> map)) return null;
                Object key = matchKey(map, entity, keyKind);
                return key == null ? null : map.get(key);
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return null; }
        }

        @Override public boolean write(LivingEntity entity, Object value) {
            boolean any = false;
            Set<Object> writtenMaps = Collections.newSetFromMap(new IdentityHashMap<>());

            // 主表
            try {
                Object obj = evaluate(containerExpr, new SimpleEvalContext(entity));
                if (obj instanceof Map<?, ?> map && writtenMaps.add(map)) {
                    Object key = matchKey(map, entity, keyKind);
                    if (key != null && unsafeModifyMapEntry(map, key, value)) any = true;
                }
            } catch (Throwable ignored) { if (ignored instanceof VirtualMachineError) throw (VirtualMachineError) ignored; }

            // 兄弟表：owner class 的所有静态 Map 字段一起写,对抗影子表回滚
            if (ownerClassInternal != null) {
                Class<?> ownerClass = loadClass(ownerClassInternal);
                if (ownerClass != null) {
                    for (Field f : ownerClass.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        if (!Map.class.isAssignableFrom(f.getType())) continue;
                        try {
                            f.setAccessible(true);
                            Object obj = f.get(null);
                            if (!(obj instanceof Map<?, ?> map)) continue;
                            if (!writtenMaps.add(map)) continue;
                            Object key = matchKey(map, entity, keyKind);
                            if (key == null) continue;
                            if (unsafeModifyMapEntry(map, key, value)) any = true;
                        } catch (Throwable ignored) { if (ignored instanceof VirtualMachineError) throw (VirtualMachineError) ignored; }
                    }
                }
            }
            return any;
        }

        @Override protected String canonicalKey() {
            return "ME:" + System.identityHashCode(containerExpr) + ":" + keyKind;
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

        @Override public boolean write(LivingEntity entity, Object value) {
            try {
                EvalContext ctx = new SimpleEvalContext(entity);
                Object arr = evaluate(arrayExpr, ctx);
                Object idx = evaluate(indexExpr, ctx);
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
            } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return false; }
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

        @Override public boolean write(LivingEntity entity, Object value) {
            try {
                Class<?> owner = loadClass(ownerInternal);
                if (owner == null) return false;
                Type[] argTypes = Type.getArgumentTypes(desc);
                boolean isStatic = args.size() == argTypes.length;
                int start = isStatic ? 0 : 1;
                Object receiver = null;
                EvalContext ctx = new SimpleEvalContext(entity);
                if (!isStatic) {
                    receiver = evaluate(args.get(0), ctx);
                    if (receiver == null) return false;
                }
                Object[] values = new Object[argTypes.length];
                Class<?>[] paramTypes = new Class<?>[argTypes.length];
                for (int i = 0; i < argTypes.length; i++) {
                    paramTypes[i] = asmTypeToClass(argTypes[i]);
                    if (paramTypes[i] == null) return false;
                    Object argValue = i == valueArgIndex ? value : evaluate(args.get(start + i), ctx);
                    values[i] = coerceArg(argValue, paramTypes[i]);
                }
                Method method = findMethod(isStatic ? owner : receiver.getClass(), name, paramTypes, values);
                if (method == null && !isStatic) method = findMethod(owner, name, paramTypes, values);
                if (method == null) return false;
                method.setAccessible(true);
                method.invoke(receiver, values);
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return false;
            }
        }

        @Override protected String canonicalKey() {
            return "MC:" + ownerInternal + "#" + name + "#" + desc + "#" + valueArgIndex;
        }
    }

    /* ==================== Map 写入：兄弟表 + entrySet 遍历 + Unsafe ==================== */

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
                Object boxed = cur == null ? newValue : coerceSameType(cur, newValue);
                if (boxed == null) boxed = newValue;

                boolean wrote = false;
                try {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Map.Entry rawEntry = entry;
                    rawEntry.setValue(boxed);
                    if (boxed.equals(entry.getValue())) wrote = true;
                } catch (Throwable ignored) { if (ignored instanceof VirtualMachineError) throw (VirtualMachineError) ignored; }

                if (!wrote) {
                    long offset = getEntryValueOffset(entry);
                    if (offset != -1) {
                        UnsafeUtil.lwjglPutObject(entry, offset, boxed);
                        if (boxed.equals(entry.getValue())) wrote = true;
                    }
                }
                if (wrote) written++;
            }
        } catch (Throwable ignored) { if (ignored instanceof VirtualMachineError) throw (VirtualMachineError) ignored; }
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
                Inverter inv = TABLE.lookupCall(call.owner(), call.name(), call.desc());
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
            Inverter inv = TABLE.lookupCall(call.owner(), call.name(), call.desc());
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

    private static void dumpStoreWriteSolve(Expr valueExpr, Object target, Object candidate, String reason) {
        String key = reason + "#" + System.identityHashCode(valueExpr);
        if (!STORE_WRITE_DUMPED.add(key)) return;
        EcaLogger.info("[HealthDataflow] StoreWrite value evaluation {} target={} candidate={}", reason, target, candidate);
        List<String> tree = new ArrayList<>();
        appendExpr(tree, valueExpr, "    ");
        for (String line : tree) EcaLogger.info("[HealthDataflow] {}", line);
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

    // 递归判断表达式树是否含 UnknownExpr，标记 getHealth 实现"过于复杂/无法符号化"
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

    private static void collect(Expr e, Set<Source> out) {
        if (e instanceof Source s) out.add(s);
        else if (e instanceof Op op) for (Expr a : op.args()) collect(a, out);
        else if (e instanceof Call call) for (Expr a : call.args()) collect(a, out);
        else if (e instanceof Closure closure) for (Expr a : closure.captured()) collect(a, out);
        else if (e instanceof OptionalContentExpr optional) collect(optional.optionalExpr(), out);
        else if (e instanceof StoreWrite write) out.add(write.sink());
        else if (e instanceof Choice c) for (Expr a : c.alternatives()) collect(a, out);
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
            for (Expr alternative : choice.alternatives()) {
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
        //Math.max/min 双源防御 (形如 max(vanillaHp, shadowHp) 的两源取最大值)
        //无条件返回 target,依赖 Vanilla 阶段同步写另一边,把双源防御打穿
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

        // String.replace(search, "")：去掉子串。仅当替换串为空且 sink 是 receiver 时可逆——把 search 加回到 target 前缀。
        // 要求 target 不含 search(否则去除非单射)，否则返回 null。covers "去前缀" 这类编码。
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

    private enum InvokeFailed { INSTANCE }

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
     * 通用数据流逆推骨架：从指定方法的指令帧提取血量表达式，统一"读字节码→跑 Analyzer→strip→封 AnalysisResult"。
     * getHealth 与外部扫描各自只传入 extractor（从 MethodNode + 跑完的 Frame[] 中挑出 Expr），骨架完成其余公共部分。
     */
    private static AnalysisResult analyzeForHealthExpr(Class<?> owner, String name, String desc,
                                                        java.util.function.BiFunction<MethodNode, Frame<TaintValue>[], Expr> extractor) {
        if (owner == null || owner.getClassLoader() == null) return null;
        try {
            byte[] bytes = classBytes(owner);
            if (bytes == null) return null;
            String ownerInternal = EcaSetHealthManager.internalName(owner);
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
            MethodNode mn = null;
            for (MethodNode m : cn.methods) {
                if (m.desc.equals(desc) && m.name.equals(name)) { mn = m; break; }
            }
            if (mn == null || mn.instructions.size() == 0) return null;

            AnalysisCtx ctx = new AnalysisCtx(DEFAULT_MAX_DEPTH);
            TaintInterpreter interp = new TaintInterpreter(ctx, 0, ownerInternal, null);
            Analyzer<TaintValue> analyzer = new Analyzer<>(interp);
            Frame<TaintValue>[] frames = analyzer.analyze(ownerInternal, mn);

            Expr raw = extractor.apply(mn, frames);
            if (raw == null || raw instanceof UnknownExpr) return null;
            Expr stripped = stripEcaHealthWrappers(raw);
            if (stripped == null || stripped instanceof UnknownExpr) return null;
            return new AnalysisResult(stripped, List.copyOf(collectSources(stripped)), owner);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
        return null;
    }

    //对外入口：const override 失败后，对 isAlive/isDeadOrDying 跑完整数据流管线
    public static EcaSetHealthManager.HealthPath resolveExternalScan(Class<?> entityClass) {
        if (entityClass == null) return null;
        AnalysisResult ar = EXTERNAL_SCAN_CACHE.computeIfAbsent(entityClass,
                c -> analyzeUnifiedExternalScan(entityClass));
        //纯数据流逆推：无结果或无可写 Source 即放弃，不再有方法对兜底
        if (ar == null || ar.isEmpty() || ar.sources.isEmpty()) return null;
        return new EcaSetHealthManager.HealthPath(EcaSetHealthManager.WriteMethod.EXTERNAL_SCAN,
                (entity, target) -> writeViaSources(entityClass, ar, entity, target,
                        (verifiedEntity, verifiedTarget, sink) ->
                                verifyExternalDataflow(ar.returnExpr, verifiedEntity, verifiedTarget, sink)));
    }

    //同时分析 isAlive 和 isDeadOrDying，取 Sources 更多的结果
    private static AnalysisResult analyzeUnifiedExternalScan(Class<?> entityClass) {
        List<AnalysisResult> candidates = new ArrayList<>();
        for (Class<?> c = entityClass; c != null && c != LivingEntity.class; c = c.getSuperclass()) {
            for (String[] names : new String[][]{{ISALIVE_NAME, ISALIVE_NAME_ALT, "isAlive"},
                                                  {ISDEAD_NAME, ISDEAD_NAME_ALT, "isDeadOrDying"}}) {
                AnalysisResult ar = analyzeExternalScanMethod(c, names[0], names[1], names[2]);
                if (ar != null && !ar.isEmpty() && !ar.sources.isEmpty()) {
                    if (EXTERNAL_SCAN_DUMPED.add(entityClass.getName())) {
                        EcaLogger.info("[HealthDataflow] EXTERNAL_SCAN entity={} via {} in {} sources={}",
                                entityClass.getName(), names[2], c.getName(), ar.sources.size());
                    }
                    candidates.add(ar);
                }
            }
            AnalysisResult writes = analyzeDamageWriteMethods(c, entityClass);
            if (writes != null && !writes.isEmpty() && !writes.sources.isEmpty()) candidates.add(writes);
        }
        AnalysisResult result = combineExternalScanCandidates(entityClass, candidates);
        if (result != null) {
            EcaLogger.info("[HealthDataflow] EXTERNAL_SCAN entity={} mergedCandidates={} mergedSources={}",
                    entityClass.getName(), candidates.size(), result.sources.size());
        }
        return result;
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
        AnalysisResult result = new AnalysisResult(combined, List.copyOf(sources), entityClass);
        if (EXTERNAL_WRITE_DUMPED.add(entityClass.getName() + "#merged")) {
            EcaLogger.info("[HealthDataflow] EXTERNAL_SCAN entity={} complete hierarchy scan candidates={} alternatives={} sources={}",
                    entityClass.getName(), candidates.size(), alternatives.size(), result.sources.size());
            int i = 0;
            for (Source source : result.sources) {
                EcaLogger.info("[HealthDataflow]   external source #{} {} type={}", i++, source.label, source.valueType.getName());
            }
        }
        return result;
    }

    private static int externalSourcePriority(Source source) {
        if (source instanceof SynchedDataSource) return 0;
        if (source instanceof MapEntrySource || source instanceof CapabilityDataSource) return 1;
        if (source instanceof FieldChainSource) return 2;
        if (source instanceof MethodCallSource) return 3;
        return 4;
    }

    public static AnalysisResult analyzeExternalScan(Class<?> entityClass) {
        List<AnalysisResult> candidates = new ArrayList<>();
        //第 1 层：完整数据流逆推——跑 analyzeMethod + FCMPL/FCMPG 栈帧提取
        for (Class<?> c = entityClass; c != null && c != LivingEntity.class; c = c.getSuperclass()) {
            for (String[] names : new String[][]{{ISALIVE_NAME, ISALIVE_NAME_ALT, "isAlive"},
                                                  {ISDEAD_NAME, ISDEAD_NAME_ALT, "isDeadOrDying"}}) {
                AnalysisResult ar = analyzeExternalScanMethod(c, names[0], names[1], names[2]);
                if (ar != null && !ar.isEmpty() && !ar.sources.isEmpty()) {
                    if (EXTERNAL_SCAN_DUMPED.add(entityClass.getName())) {
                        EcaLogger.info("[HealthDataflow] EXTERNAL_SCAN entity={} via {} in {} sources={}",
                                entityClass.getName(), names[2], c.getName(), ar.sources.size());
                    }
                    candidates.add(ar);
                }
            }
        }
        return combineExternalScanCandidates(entityClass, candidates);
    }

    //对 isAlive/isDeadOrDying 跑数据流骨架，从 FCMPL/FCMPG 前栈帧提取血量表达式
    private static AnalysisResult analyzeExternalScanMethod(Class<?> owner, String srgName, String mcpName, String label) {
        java.util.function.BiFunction<MethodNode, Frame<TaintValue>[], Expr> cmpgExtractor = (mn, frames) -> {
            int idx = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                if (insn.getOpcode() == Opcodes.FCMPL || insn.getOpcode() == Opcodes.FCMPG) {
                    Frame<TaintValue> f = frames[idx];
                    if (f != null && f.getStackSize() >= 2) {
                        //浮点比较两操作数中，非 Primitive 常数的那一侧才是真实血量表达式
                        Expr top = f.getStack(f.getStackSize() - 1).expr;
                        Expr second = f.getStack(f.getStackSize() - 2).expr;
                        Expr healthExpr = (top instanceof Primitive && !(second instanceof Primitive)) ? second
                                : (second instanceof Primitive && !(top instanceof Primitive)) ? top : null;
                        if (healthExpr == null && !(top instanceof Primitive)) healthExpr = top;
                        if (healthExpr != null && !(healthExpr instanceof UnknownExpr)) return healthExpr;
                    }
                }
                idx++;
            }
            return null;
        };
        //同名只可能存在其一：先 srgName，未命中再 mcpName，避免重复跑 Analyzer
        AnalysisResult ar = analyzeForHealthExpr(owner, srgName, BOOL_DESC, cmpgExtractor);
        if (ar != null) return ar;
        return analyzeForHealthExpr(owner, mcpName, BOOL_DESC, cmpgExtractor);
    }

    /* ==================== AnalysisResult + 入口 ==================== */

    private static AnalysisResult analyzeDamageWriteMethods(Class<?> owner, Class<?> requestedEntityClass) {
        List<Expr> writes = new ArrayList<>();
        for (String[] names : new String[][]{
                {HURT_NAME, HURT_NAME_ALT, HURT_DESC, "hurt"},
                {ACTUALLY_HURT_NAME, ACTUALLY_HURT_NAME_ALT, ACTUALLY_HURT_DESC, "actuallyHurt"}
        }) {
            AnalysisResult ar = analyzeDamageWriteMethod(owner, names[0], names[1], names[2]);
            if (ar == null || ar.isEmpty()) continue;
            addUniqueExpr(writes, ar.returnExpr);
            if (EXTERNAL_WRITE_DUMPED.add(requestedEntityClass.getName() + "#" + names[3])) {
                EcaLogger.info("[HealthDataflow] EXTERNAL_SCAN entity={} via {} write-sites in {} sources={}",
                        requestedEntityClass.getName(), names[3], owner.getName(), ar.sources.size());
            }
        }
        if (writes.isEmpty()) return null;
        Expr expr = writes.size() == 1 ? writes.get(0) : new Choice(List.copyOf(writes));
        return AnalysisResult.of(expr, owner);
    }

    private static AnalysisResult analyzeDamageWriteMethod(Class<?> owner, String srgName, String mcpName, String desc) {
        AnalysisCtx ctx = new AnalysisCtx(DEFAULT_MAX_DEPTH);
        Expr writes = analyzeMethodWrites(owner, srgName, desc, null, ctx, 0);
        if (writes == null || writes instanceof UnknownExpr) {
            writes = analyzeMethodWrites(owner, mcpName, desc, null, ctx, 0);
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
            TaintInterpreter interpreter = new TaintInterpreter(ctx, depth, ownerInternal, seedLocals);
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
            EcaLogger.info("[HealthDataflow] analyzeMethodWrites {}.{} failed: {}", owner.getName(), name, t.toString());
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
                return buildFieldSourceFromReceiver(field, frame.getStack(frame.getStackSize() - 2).expr);
            }
            if (opcode == Opcodes.PUTSTATIC && frame.getStackSize() >= 1) return buildStaticFieldWriteSource(field);
            return null;
        }
        if (!(insn instanceof MethodInsnNode call)) return null;
        if (Type.getReturnType(call.desc) != Type.VOID_TYPE) return null;
        List<Expr> args = invokeValueExprs(call, frame);
        if (args.isEmpty()) return null;
        Expr known = extractKnownWriteCall(call, args);
        if (known != null) return known;
        Expr inlined = tryInlineWriteCall(call, args, ctx, depth);
        return inlined != null && !(inlined instanceof UnknownExpr) ? inlined : extractMethodCallWriteSource(call, args);
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
            return new MapEntrySource(container, key, detectKeyKind(key), mapOwnerHint(container), Object.class, call.owner);
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
        if (call.owner.startsWith("java/") || call.owner.startsWith("javax/") || call.owner.startsWith("jdk/")
                || call.owner.startsWith("net/minecraft/")) {
            return null;
        }
        Class<?> owner = loadClass(call.owner);
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

    private static int localCount(Type[] types) {
        int count = 0;
        for (Type type : types) count += type.getSize();
        return count;
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

    private static Expr extractMethodCallWriteSource(MethodInsnNode call, List<Expr> args) {
        Type[] argTypes = Type.getArgumentTypes(call.desc);
        boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
        int start = isStatic ? 0 : 1;
        if (args.size() < start + argTypes.length) return null;
        boolean hasEntity = false;
        for (Expr arg : args) {
            if (arg == EntityParamMarker.I) {
                hasEntity = true;
                break;
            }
        }
        if (!hasEntity || call.owner.startsWith("net/minecraft/") || call.owner.startsWith("java/")) return null;
        for (int i = 0; i < argTypes.length; i++) {
            if (!isNumericType(argTypes[i])) continue;
            Expr valueExpr = args.get(start + i);
            if (valueExpr instanceof Primitive) continue;
            return new MethodCallSource(call.owner, call.name, call.desc, List.copyOf(args), i);
        }
        return null;
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
            return null;
        }
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
        // CONSTANT：全树纯字面常数（无 Source/Choice），最简情况
        // HAS_CONSTANT：树中存在常数分支（Choice 中至少一条 alternative 是常数），FRETURN patch 可覆盖
        // SOLVABLE：含可写 Source，走 dataflow 真写存储（优先级高于 HAS_CONSTANT）
        // OPAQUE：含 Unknown 或无法符号化的非常数计算
        public enum Kind { SOLVABLE, CONSTANT, HAS_CONSTANT, OPAQUE }

        /* HAS_CONSTANT 优先级高于 SOLVABLE：只要有常数分支就优先走 FRETURN patch + 常数覆盖，
           即使树中也存在 Source（如加密保护的存储），常数覆盖仍能生效。
           SOLVABLE 仅用于纯数据流可写、无常数分支的实体。 */
        public Kind classify() {
            if (returnExpr == null || containsUnknown(returnExpr)) return Kind.OPAQUE;
            if (hasConstantBranch(returnExpr)) {
                // 全树纯常数（无 Choice/Source）→ CONSTANT；含 Choice 且有常数分支 → HAS_CONSTANT
                return isPureConstant(returnExpr) ? Kind.CONSTANT : Kind.HAS_CONSTANT;
            }
            if (!sources.isEmpty()) return Kind.SOLVABLE;
            return Kind.OPAQUE;
        }
        /* 判断表达式是否为纯常数，不含 Choice：Primitive 或纯常数的 Op。
           用于区分 CONSTANT（全树纯常数）和 HAS_CONSTANT（Choice 中存在常数分支）。 */
        private static boolean isPureConstant(Expr e) {
            if (e instanceof Primitive) return true;
            if (e instanceof Op op) {
                for (Expr a : op.args()) if (!isPureConstant(a)) return false;
                return true;
            }
            return false;
        }

        public static AnalysisResult of(Expr e, Class<?> definingClass) {
            return new AnalysisResult(e, List.copyOf(collectSources(e)), definingClass);
        }
    }

    private static boolean shouldKeepAsRuntimeCall(Class<?> owner, String name, String desc) {
        if (!Type.getReturnType(desc).equals(Type.getType(String.class))) return false;
        if (owner.getName().startsWith("java.")) return false;
        try {
            byte[] bytes = classBytes(owner);
            if (bytes == null) return false;
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
            for (MethodNode method : cn.methods) {
                if (method.name.equals(name) && method.desc.equals(desc)) {
                    return hasComplexStringRuntimeBoundary(method);
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
                EcaLogger.info("[HealthDataflow] classBytes({}) returned null — class bytes unreachable", entityClass.getName());
                return AnalysisResult.EMPTY;
            }
            ClassAndMethod target = findMethodOwnerFromBytes(entityClass, classBytes);
            if (target == null) {
                EcaLogger.info("[HealthDataflow] findMethodOwner({}) returned null — getHealth()F not found in hierarchy bytes", entityClass.getName());
                return AnalysisResult.EMPTY;
            }
            AnalysisCtx ctx = new AnalysisCtx(maxDepth);
            Class<?> defClass = target.owner();
            Expr ret = analyzeMethod(defClass, target.name(), GETHEALTH_DESC, null, ctx, 0);
            if (ret == null) {
                EcaLogger.info("[HealthDataflow] analyzeMethod({}.{}()) returned null — ASM analysis failed", defClass.getName(), target.name());
                return AnalysisResult.EMPTY;
            }
            // 诊断：打印剥离前的原始分析树
            if (ret instanceof UnknownExpr || !TREE_DUMPED.contains(entityClass.getName())) {
                List<String> rawTree = new ArrayList<>();
                appendExpr(rawTree, ret, "    RAW> ");
                EcaLogger.info("[HealthDataflow] raw returnExpr (before strip):");
                for (String line : rawTree) EcaLogger.info("[HealthDataflow] {}", line);
            }
            Expr stripped = stripEcaHealthWrappers(ret);
            if (stripped == null || stripped instanceof UnknownExpr) {
                EcaLogger.info("[HealthDataflow] stripEcaHealthWrappers -> {} — raw analysis = UnknownExpr or null",
                        stripped == null ? "null" : "UnknownExpr");
                return AnalysisResult.EMPTY;
            }
            return AnalysisResult.of(stripped, target.owner());
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            EcaLogger.info("[HealthDataflow] analyze {} failed: {}", entityClass.getName(), t.toString());
            return AnalysisResult.EMPTY;
        }
    }

    /* 只载一次 classBytes，沿继承链扫描 getHealth()F 定义类 */
    private static ClassAndMethod findMethodOwnerFromBytes(Class<?> startClass, byte[] startBytes) {
        if (classDefinesMethodInBytes(startClass, startBytes, GETHEALTH_NAME)) return new ClassAndMethod(startClass, GETHEALTH_NAME);
        if (classDefinesMethodInBytes(startClass, startBytes, GETHEALTH_NAME_ALT)) return new ClassAndMethod(startClass, GETHEALTH_NAME_ALT);
        for (Class<?> c = startClass.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (classDefinesMethod(c, GETHEALTH_NAME, GETHEALTH_DESC)) return new ClassAndMethod(c, GETHEALTH_NAME);
            if (classDefinesMethod(c, GETHEALTH_NAME_ALT, GETHEALTH_DESC)) return new ClassAndMethod(c, GETHEALTH_NAME_ALT);
        }
        return null;
    }

    private static boolean classDefinesMethodInBytes(Class<?> owner, byte[] bytes, String name) {
        if (bytes == null) return false;
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(GETHEALTH_DESC)) return true;
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
        return ECA_LIVING_HOOK.equals(call.owner())
                || ECA_LOCK_MANAGER.equals(call.owner());
    }

    private static boolean isEcaHealthWrapperSource(Source source) {
        if (source.label.startsWith("SF:net.eca.util.EntityUtil.HEALTH_LOCK_VALUE")
                || source.label.startsWith("SF:net.eca.util.EntityUtil.HEAL_BAN_VALUE")) {
            return true;
        }
        return false;
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

    //分析期 this 占位符：代表 getHealth 接收者实体，供 seeded 分析把根植于 this 的存储正确识别为源（super.getHealth/字段链/SynchedData/Map-key）
    public static Expr thisMarker() { return EntityParamMarker.I; }

    private record ClassAndMethod(Class<?> owner, String name) {}

    private static ClassAndMethod findMethodOwner(Class<?> startClass) {
        for (Class<?> c = startClass; c != null && c != Object.class; c = c.getSuperclass()) {
            if (classDefinesMethod(c, GETHEALTH_NAME, GETHEALTH_DESC)) return new ClassAndMethod(c, GETHEALTH_NAME);
            if (classDefinesMethod(c, GETHEALTH_NAME_ALT, GETHEALTH_DESC)) return new ClassAndMethod(c, GETHEALTH_NAME_ALT);
        }
        return null;
    }

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

    /* 取类字节码：优先运行期(mixin/coremod 转换后)版本，使分析能看见 mixin 对 getHealth 等的修改。
       取不到再回退磁盘原始 .class（先走 ClassLoader.getResourceAsStream，再走 SystemClassLoader 兜底，最后用 clazz.getResourceAsStream）。 */
    //统一委托给 EcaSetHealthManager.classBytes(含 RuntimeBytecodeProvider + ClassLoader + CodeSource 回退)
    private static byte[] classBytes(Class<?> clazz) {
        return EcaSetHealthManager.classBytes(clazz);
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
            if (mn == null || mn.instructions.size() == 0) return null;

            String ownerInternal = internalName(owner);
            TaintInterpreter interp = new TaintInterpreter(ctx, depth, ownerInternal, seedLocals);
            Analyzer<TaintValue> analyzer = new Analyzer<>(interp);
            Frame<TaintValue>[] frames = analyzer.analyze(ownerInternal, mn);

            List<Expr> returns = new ArrayList<>();
            int idx = 0;
            int totalReturns = 0, nullFrames = 0, zeroStack = 0, unknownExpr = 0, acceptedReturns = 0;
            List<String> returnDiag = new ArrayList<>();
            for (AbstractInsnNode insn : mn.instructions) {
                int op = insn.getOpcode();
                if (op == Opcodes.IRETURN || op == Opcodes.LRETURN || op == Opcodes.FRETURN
                    || op == Opcodes.DRETURN || op == Opcodes.ARETURN) {
                    totalReturns++;
                    Frame<TaintValue> f = frames[idx];
                    String diag = "  return#" + totalReturns + " op=" + op + " frameNull=" + (f == null);
                    if (f != null) {
                        diag += " stackSize=" + f.getStackSize();
                        if (f.getStackSize() > 0) {
                            Expr e = f.getStack(f.getStackSize() - 1).expr;
                            diag += " topExpr=" + (e == null ? "null" : e.getClass().getSimpleName() + " " + e);
                            if (e instanceof UnknownExpr u) diag += " prov=" + u.provenance();
                            if (e != null && !(e instanceof UnknownExpr)) acceptedReturns++;
                            else unknownExpr++;
                            Expr expanded = expandArrayReturn(mn, frames, idx, e, ctx, depth);
                            if (expanded != null && !collectSources(expanded).isEmpty()) e = expanded;
                            if (e != null && !(e instanceof UnknownExpr)) returns.add(e);
                        } else {
                            zeroStack++;
                        }
                    } else {
                        nullFrames++;
                    }
                    returnDiag.add(diag);
                }
                idx++;
            }
            if (seedLocals == null && (!returns.isEmpty() || !returnDiag.isEmpty())
                    && METHOD_DIAG_DUMPED.add(owner.getName() + "." + name)) {
                EcaLogger.info("[HealthDataflow] {}.{}() totalReturns={} nullFrames={} zeroStack={} unknownExpr={} accepted={}",
                        owner.getName(), name, totalReturns, nullFrames, zeroStack, unknownExpr, acceptedReturns);
                for (String line : returnDiag) EcaLogger.info("[HealthDataflow] {}", line);
            }
            Expr result = returns.isEmpty() ? new UnknownExpr("no-return-in-method")
                : (returns.size() == 1 ? returns.get(0) : new Choice(dedupe(returns)));
            if (seedLocals == null) ctx.methodCache.put(cacheKey, result);
            return result;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            EcaLogger.info("[HealthDataflow] analyzeMethod {}.{} failed: {}", owner.getName(), name, t.toString());
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
            TaintInterpreter interp = new TaintInterpreter(ctx, depth, ownerInternal, seedLocals);
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
        final TaintValue[] seedLocals;

        TaintInterpreter(AnalysisCtx ctx, int depth, String currentOwner, TaintValue[] seedLocals) {
            super(Opcodes.ASM9);
            this.ctx = ctx; this.depth = depth;
            this.currentOwner = currentOwner; this.seedLocals = seedLocals;
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
            return switch (insn.getOpcode()) {
                case Opcodes.ACONST_NULL -> new TaintValue(1, UnknownExpr.UNKNOWN);
                case Opcodes.ICONST_M1 -> primI(-1);
                case Opcodes.ICONST_0 -> primI(0);
                case Opcodes.ICONST_1 -> primI(1);
                case Opcodes.ICONST_2 -> primI(2);
                case Opcodes.ICONST_3 -> primI(3);
                case Opcodes.ICONST_4 -> primI(4);
                case Opcodes.ICONST_5 -> primI(5);
                case Opcodes.LCONST_0 -> primL(0);
                case Opcodes.LCONST_1 -> primL(1);
                case Opcodes.FCONST_0 -> primF(0f);
                case Opcodes.FCONST_1 -> primF(1f);
                case Opcodes.FCONST_2 -> primF(2f);
                case Opcodes.DCONST_0 -> primD(0d);
                case Opcodes.DCONST_1 -> primD(1d);
                case Opcodes.BIPUSH, Opcodes.SIPUSH -> primI(((IntInsnNode) insn).operand);
                case Opcodes.LDC -> ldcValue((LdcInsnNode) insn);
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
                && m.desc.equals(GETHEALTH_DESC)
                && (m.name.equals(GETHEALTH_NAME) || m.name.equals(GETHEALTH_NAME_ALT))
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

            // 兜底 Call 节点
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
            if (m.owner.startsWith("java/") || m.owner.startsWith("net/minecraft/")) {
                Method jm = findAnyMethod(owner, m.name, m.desc);
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

    private static TaintValue primI(int v) { return new TaintValue(1, new Primitive(v, 'I')); }
    private static TaintValue primL(long v) { return new TaintValue(2, new Primitive(v, 'J')); }
    private static TaintValue primF(float v) { return new TaintValue(1, new Primitive(v, 'F')); }
    private static TaintValue primD(double v) { return new TaintValue(2, new Primitive(v, 'D')); }

    private static TaintValue ldcValue(LdcInsnNode insn) {
        Object cst = insn.cst;
        int sz = (cst instanceof Long || cst instanceof Double) ? 2 : 1;
        if (cst instanceof Integer i) return new TaintValue(sz, new Primitive(i, 'I'));
        if (cst instanceof Long l) return new TaintValue(sz, new Primitive(l, 'J'));
        if (cst instanceof Float f) return new TaintValue(sz, new Primitive(f, 'F'));
        if (cst instanceof Double d) return new TaintValue(sz, new Primitive(d, 'D'));
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
    private static Class<?> loadClass(String internalName) {
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

    private static Method findMethod(Class<?> owner, String name, Class<?>[] paramTypes, Object[] argValues) {
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

    private static boolean methodArgMatches(Class<?> methodParam, Class<?> descriptorParam, Object value) {
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

    private static Field findFieldInHierarchy(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static boolean writeFieldViaSetter(Object target, FieldStep step, Object value) {
        if (target == null) return false;
        Class<?> fieldType = descriptorToClass(step.desc());
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
                method.invoke(target, coerceArg(value, method.getParameterTypes()[0]));
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
                if (methodArgMatches(params[0], fieldType, value)) return method;
            }
        }
        return null;
    }

    private static boolean writeField(Object target, FieldStep step, Object value) {
        Field f;
        Class<?> ft;
        try {
            Class<?> owner = loadClass(step.ownerInternal());
            if (owner == null) return false;
            f = findFieldInHierarchy(owner, step.name());
            if (f == null) return false;
            f.setAccessible(true);
            ft = f.getType();
        } catch (Throwable t) { if (t instanceof VirtualMachineError) throw (VirtualMachineError) t; return false; }

        try {
            if (ft == float.class) f.setFloat(target, ((Number) value).floatValue());
            else if (ft == double.class) f.setDouble(target, ((Number) value).doubleValue());
            else if (ft == int.class) f.setInt(target, ((Number) value).intValue());
            else if (ft == long.class) f.setLong(target, ((Number) value).longValue());
            else if (ft == short.class) f.setShort(target, ((Number) value).shortValue());
            else if (ft == byte.class) f.setByte(target, ((Number) value).byteValue());
            else f.set(target, coerceForType(value, ft));
            return true;
        } catch (Throwable ignored) {
            if (ignored instanceof VirtualMachineError) throw (VirtualMachineError) ignored;
            //final 字段 / 模块系统访问限制 → 走 Unsafe 兜底
            return UnsafeUtil.unsafePutField(target, f, value);
        }
    }

    private record SimpleEvalContext(LivingEntity entity) implements EvalContext {
        @Override public Object eval(Expr e) { return evaluate(e, this); }
    }
}
