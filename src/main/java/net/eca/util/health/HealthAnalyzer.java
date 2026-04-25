package net.eca.util.health;

import net.eca.coremod.TransformerWhitelist;
import net.eca.util.EcaLogger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.io.InputStream;
import java.util.*;
import java.util.function.BiFunction;

/*
 * Phase 3 数据流分析：反向追踪 getHealth() 的真实存储位置，之前的那个太复杂了，换成了现在通用性更强的版本
 * 沿继承链找到 m_21223_()F 最深的 override，用 ASM Analyzer + TaintInterpreter
 * 把字节码翻译成代数表达式树（Expr）。遇到 mod 方法会递归内联（深度上限 maxDepth），
 * java.lang / net.minecraft 的未识别调用保留为 UnresolvedCall，留给 Manager 走 sister-setter。
 * solve() 对表达式树做反向求解：给定期望返回值和目标叶子，沿运算链逐层求逆，
 * 算出叶子应该写什么值。不可逆节点返回 null，由上层兜底。
 */
public final class HealthAnalyzer {

    private static final String TARGET_METHOD_NAME = "m_21223_";
    private static final String TARGET_METHOD_DESC = "()F";
    private static final int DEFAULT_MAX_DEPTH = 10;

    private HealthAnalyzer() {}

    // ==================== 入口 ====================

    public static AnalysisResult analyze(Class<?> entityClass) {
        return analyze(entityClass, DEFAULT_MAX_DEPTH);
    }

    public static AnalysisResult analyze(Class<?> entityClass, int maxDepth) {
        try {
            Class<?> owner = findMethodOwner(entityClass, TARGET_METHOD_NAME, TARGET_METHOD_DESC);
            if (owner == null) return AnalysisResult.EMPTY;
            AnalysisContext ctx = new AnalysisContext(maxDepth);
            Expr ret = analyzeMethod(owner, TARGET_METHOD_NAME, TARGET_METHOD_DESC, null, ctx, 0);
            if (ret == null) return AnalysisResult.EMPTY;
            return AnalysisResult.from(ret);
        } catch (Throwable t) {
            EcaLogger.info("[HealthAnalyzer] analyze {} failed: {}", entityClass.getName(), t.toString());
            return AnalysisResult.EMPTY;
        }
    }

    // ==================== 表达式树 ====================

    public interface Expr {}

    public record FieldLeaf(List<FieldStep> path) implements Expr {
        public record FieldStep(String ownerInternal, String name, String desc) {}
    }

    public record EntityDataLeaf(String accessorOwnerInternal, String accessorName) implements Expr {}

    public record MapEntryLeaf(String containerOwnerInternal, String containerGetterName, KeyKind keyKind) implements Expr {
        public enum KeyKind { ENTITY, ENTITY_ID, ENTITY_UUID, UNKNOWN }
    }

    //Map.get 受体来自 GETSTATIC 而不是 INVOKESTATIC 时使用
    public record MapByStaticFieldLeaf(String fieldOwnerInternal, String fieldName, MapEntryLeaf.KeyKind keyKind) implements Expr {}

    //数组元素访问：arr[idx]，indexExpr 可以是 Const 或其他 leaf 表达式（如 GETFIELD 链）
    public record ArrayIndexLeaf(Expr arrayExpr, Expr indexExpr) implements Expr {}

    //在非 this 受体上做 GETFIELD 链：root 是某个 leaf（MapEntry / MapByStaticField / ArrayIndex / UnresolvedCall 等），path 是后续字段链
    public record ChainedFieldLeaf(Expr root, List<FieldLeaf.FieldStep> path) implements Expr {}

    public record Const(float value) implements Expr {}

    public record BinaryOp(int opcode, Expr left, Expr right) implements Expr {}

    public record UnaryOp(int opcode, Expr operand) implements Expr {}

    //已知可逆/可特殊处理的方法调用（Long.reverse / Float.intBitsToFloat / Float.floatToRawIntBits / Math.max(F,F)）
    public record InvertibleCall(InvertibleKind kind, List<Expr> args) implements Expr {
        public enum InvertibleKind {
            LONG_REVERSE,           // Long.reverse(long): long, 自逆
            INT_BITS_TO_FLOAT,      // Float.intBitsToFloat(int): float, 反向是 floatToRawIntBits
            FLOAT_TO_RAW_INT_BITS,  // Float.floatToRawIntBits(float): int, 反向是 intBitsToFloat
            MATH_MAX_F              // Math.max(float, float): float, target>0 时若一边是 0 即直通另一边
        }
    }

    public record UnresolvedCall(String owner, String name, String desc, List<Expr> args) implements Expr {}

    public record Choice(List<Expr> alternatives) implements Expr {}

    public record UnknownExpr() implements Expr {
        public static final UnknownExpr I = new UnknownExpr();
    }

    // 分析期内部占位符（最终结果不应该再出现这些）
    record EntityParamMarker() implements Expr { static final EntityParamMarker I = new EntityParamMarker(); }
    record StaticFieldMarker(String ownerInternal, String name, String desc) implements Expr {}
    record StaticMethodResult(String ownerInternal, String name, String desc) implements Expr {}

    // ==================== 分析结果 ====================

    public static final class AnalysisResult {
        public static final AnalysisResult EMPTY = new AnalysisResult(UnknownExpr.I, List.of());
        public final Expr returnExpr;
        public final List<WriteTarget> writeTargets;

        private AnalysisResult(Expr returnExpr, List<WriteTarget> writeTargets) {
            this.returnExpr = returnExpr;
            this.writeTargets = writeTargets;
        }

        public boolean isEmpty() { return writeTargets.isEmpty(); }

        static AnalysisResult from(Expr ret) {
            List<WriteTarget> targets = new ArrayList<>();
            Set<Expr> seenSinks = new HashSet<>();
            collectWriteTargets(ret, ret, targets, seenSinks);
            return new AnalysisResult(ret, List.copyOf(targets));
        }

        private static void collectWriteTargets(Expr root, Expr current, List<WriteTarget> out, Set<Expr> seen) {
            if (current instanceof Choice c) {
                for (Expr alt : c.alternatives) collectWriteTargets(root, alt, out, seen);
                return;
            }
            collectLeaves(current, leaf -> {
                if (seen.add(leaf)) out.add(new WriteTarget(leaf, root));
            });
        }

        private static void collectLeaves(Expr e, java.util.function.Consumer<Expr> sink) {
            if (e instanceof FieldLeaf || e instanceof EntityDataLeaf || e instanceof MapEntryLeaf
                || e instanceof MapByStaticFieldLeaf || e instanceof ArrayIndexLeaf || e instanceof ChainedFieldLeaf) {
                sink.accept(e);
            } else if (e instanceof BinaryOp b) {
                collectLeaves(b.left, sink);
                collectLeaves(b.right, sink);
            } else if (e instanceof UnaryOp u) {
                collectLeaves(u.operand, sink);
            } else if (e instanceof Choice c) {
                for (Expr a : c.alternatives) collectLeaves(a, sink);
            } else if (e instanceof UnresolvedCall uc) {
                for (Expr a : uc.args) collectLeaves(a, sink);
            } else if (e instanceof InvertibleCall ic) {
                for (Expr a : ic.args) collectLeaves(a, sink);
            }
        }
    }

    public record WriteTarget(Expr sink, Expr fullExpr) {}

    // ==================== 分析实现 ====================

    private static final class AnalysisContext {
        final int maxDepth;
        final Map<String, Expr> methodCache = new HashMap<>();
        AnalysisContext(int maxDepth) { this.maxDepth = maxDepth; }
    }

    private static Class<?> findMethodOwner(Class<?> startClass, String name, String desc) {
        for (Class<?> c = startClass; c != null && c != Object.class; c = c.getSuperclass()) {
            if (classDefinesMethod(c, name, desc)) return c;
        }
        return null;
    }

    private static boolean classDefinesMethod(Class<?> clazz, String name, String desc) {
        if (clazz.getClassLoader() == null) return false;
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(clazz.getName().replace('.', '/') + ".class")) {
            if (is == null) return false;
            ClassNode cn = new ClassNode();
            new ClassReader(is).accept(cn, 0);
            for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(desc)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private static Expr analyzeMethod(Class<?> owner, String name, String desc,
                                       TaintValue[] seedLocals, AnalysisContext ctx, int depth) {
        if (owner.getClassLoader() == null) return null;
        if (TransformerWhitelist.isProtected(owner.getName())) return null;

        String cacheKey = owner.getName().replace('.', '/') + "#" + name + "#" + desc;
        if (seedLocals == null) {
            Expr cached = ctx.methodCache.get(cacheKey);
            if (cached != null) return cached;
        }

        try (InputStream is = owner.getClassLoader().getResourceAsStream(
                owner.getName().replace('.', '/') + ".class")) {
            if (is == null) return null;
            ClassNode cn = new ClassNode();
            new ClassReader(is).accept(cn, ClassReader.EXPAND_FRAMES);
            MethodNode mn = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) { mn = m; break; }
            }
            if (mn == null || mn.instructions.size() == 0) return null;

            String ownerInternal = owner.getName().replace('.', '/');
            TaintInterpreter interp = new TaintInterpreter(ctx, depth, ownerInternal, seedLocals);
            Analyzer<TaintValue> analyzer = new Analyzer<>(interp);
            Frame<TaintValue>[] frames = analyzer.analyze(ownerInternal, mn);

            // 收集所有 return 指令处栈顶的 Expr，union 成 Choice
            List<Expr> returns = new ArrayList<>();
            int idx = 0;
            for (AbstractInsnNode insn : mn.instructions) {
                int op = insn.getOpcode();
                if (op == Opcodes.IRETURN || op == Opcodes.LRETURN || op == Opcodes.FRETURN
                    || op == Opcodes.DRETURN || op == Opcodes.ARETURN) {
                    Frame<TaintValue> f = frames[idx];
                    if (f != null && f.getStackSize() > 0) {
                        Expr e = cleanMarkers(f.getStack(f.getStackSize() - 1).expr);
                        if (e != null) returns.add(e);
                    }
                }
                idx++;
            }
            Expr result = returns.isEmpty() ? UnknownExpr.I
                : (returns.size() == 1 ? returns.get(0) : new Choice(dedupe(returns)));
            if (seedLocals == null) ctx.methodCache.put(cacheKey, result);
            return result;
        } catch (Throwable t) {
            EcaLogger.info("[HealthAnalyzer] analyzeMethod {}.{} failed: {}", owner.getName(), name, t.toString());
            return null;
        }
    }

    private static List<Expr> dedupe(List<Expr> in) {
        List<Expr> out = new ArrayList<>();
        for (Expr e : in) if (!out.contains(e)) out.add(e);
        return out;
    }

    // 保留 markers（EntityParamMarker / StaticFieldMarker / StaticMethodResult）在 args 里，给运行时 resolveExprObject 用
    // 它们不会被 collectLeaves 收集为 sink，所以不会污染 WriteTarget 列表
    private static Expr cleanMarkers(Expr e) {
        if (e instanceof BinaryOp b) {
            return new BinaryOp(b.opcode, cleanMarkers(b.left), cleanMarkers(b.right));
        }
        if (e instanceof UnaryOp u) {
            return new UnaryOp(u.opcode, cleanMarkers(u.operand));
        }
        if (e instanceof Choice c) {
            List<Expr> alts = new ArrayList<>();
            for (Expr a : c.alternatives) alts.add(cleanMarkers(a));
            return alts.size() == 1 ? alts.get(0) : new Choice(dedupe(alts));
        }
        if (e instanceof UnresolvedCall uc) {
            List<Expr> args = new ArrayList<>();
            for (Expr a : uc.args) args.add(cleanMarkers(a));
            return new UnresolvedCall(uc.owner, uc.name, uc.desc, args);
        }
        if (e instanceof InvertibleCall ic) {
            List<Expr> args = new ArrayList<>();
            for (Expr a : ic.args) args.add(cleanMarkers(a));
            return new InvertibleCall(ic.kind, args);
        }
        if (e instanceof ArrayIndexLeaf ai) {
            return new ArrayIndexLeaf(cleanMarkers(ai.arrayExpr), cleanMarkers(ai.indexExpr));
        }
        if (e instanceof ChainedFieldLeaf cf) {
            return new ChainedFieldLeaf(cleanMarkers(cf.root), cf.path);
        }
        return e;
    }

    // ==================== Interpreter ====================

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

    private static final class TaintInterpreter extends Interpreter<TaintValue> {
        final AnalysisContext ctx;
        final int depth;
        final String currentOwner;
        final TaintValue[] seedLocals;

        TaintInterpreter(AnalysisContext ctx, int depth, String currentOwner, TaintValue[] seedLocals) {
            super(Opcodes.ASM9);
            this.ctx = ctx;
            this.depth = depth;
            this.currentOwner = currentOwner;
            this.seedLocals = seedLocals;
        }

        @Override public TaintValue newValue(Type type) {
            if (type == null) return new TaintValue(1, UnknownExpr.I);
            if (type == Type.VOID_TYPE) return null;
            return new TaintValue(type.getSize(), UnknownExpr.I);
        }

        @Override public TaintValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
            if (seedLocals != null && local < seedLocals.length && seedLocals[local] != null) {
                return seedLocals[local];
            }
            if (isInstanceMethod && local == 0) return new TaintValue(1, EntityParamMarker.I);
            return new TaintValue(type.getSize(), UnknownExpr.I);
        }

        @Override public TaintValue newOperation(AbstractInsnNode insn) {
            return switch (insn.getOpcode()) {
                case Opcodes.ACONST_NULL -> new TaintValue(1, UnknownExpr.I);
                case Opcodes.ICONST_M1 -> new TaintValue(1, new Const(-1f));
                case Opcodes.ICONST_0 -> new TaintValue(1, new Const(0f));
                case Opcodes.ICONST_1 -> new TaintValue(1, new Const(1f));
                case Opcodes.ICONST_2 -> new TaintValue(1, new Const(2f));
                case Opcodes.ICONST_3 -> new TaintValue(1, new Const(3f));
                case Opcodes.ICONST_4 -> new TaintValue(1, new Const(4f));
                case Opcodes.ICONST_5 -> new TaintValue(1, new Const(5f));
                case Opcodes.LCONST_0 -> new TaintValue(2, new Const(0f));
                case Opcodes.LCONST_1 -> new TaintValue(2, new Const(1f));
                case Opcodes.FCONST_0 -> new TaintValue(1, new Const(0f));
                case Opcodes.FCONST_1 -> new TaintValue(1, new Const(1f));
                case Opcodes.FCONST_2 -> new TaintValue(1, new Const(2f));
                case Opcodes.DCONST_0 -> new TaintValue(2, new Const(0f));
                case Opcodes.DCONST_1 -> new TaintValue(2, new Const(1f));
                case Opcodes.BIPUSH, Opcodes.SIPUSH -> new TaintValue(1, new Const(((IntInsnNode) insn).operand));
                case Opcodes.LDC -> {
                    Object cst = ((LdcInsnNode) insn).cst;
                    int sz = (cst instanceof Long || cst instanceof Double) ? 2 : 1;
                    if (cst instanceof Number n) yield new TaintValue(sz, new Const(n.floatValue()));
                    yield new TaintValue(sz, UnknownExpr.I);
                }
                case Opcodes.GETSTATIC -> {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    Type t = Type.getType(f.desc);
                    yield new TaintValue(t.getSize(), new StaticFieldMarker(f.owner, f.name, f.desc));
                }
                case Opcodes.NEW -> new TaintValue(1, UnknownExpr.I);
                case Opcodes.JSR -> new TaintValue(1, UnknownExpr.I);
                default -> new TaintValue(1, UnknownExpr.I);
            };
        }

        @Override public TaintValue copyOperation(AbstractInsnNode insn, TaintValue value) {
            return value;
        }

        @Override public TaintValue unaryOperation(AbstractInsnNode insn, TaintValue value) {
            int op = insn.getOpcode();
            return switch (op) {
                case Opcodes.INEG, Opcodes.FNEG -> new TaintValue(1, new UnaryOp(op, value.expr));
                case Opcodes.LNEG, Opcodes.DNEG -> new TaintValue(2, new UnaryOp(op, value.expr));
                case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> new TaintValue(1, new UnaryOp(op, value.expr));
                case Opcodes.F2I, Opcodes.L2I, Opcodes.D2I, Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> new TaintValue(1, new UnaryOp(op, value.expr));
                case Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> new TaintValue(2, new UnaryOp(op, value.expr));
                case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> new TaintValue(2, new UnaryOp(op, value.expr));
                case Opcodes.GETFIELD -> {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    Type t = Type.getType(f.desc);
                    FieldLeaf.FieldStep step = new FieldLeaf.FieldStep(f.owner, f.name, f.desc);
                    Expr newExpr;
                    if (value.expr instanceof FieldLeaf existing) {
                        // 现有 entity-rooted 字段链，追加一节
                        List<FieldLeaf.FieldStep> path = new ArrayList<>(existing.path);
                        path.add(step);
                        newExpr = new FieldLeaf(path);
                    } else if (value.expr instanceof EntityParamMarker) {
                        // this.field 起步
                        newExpr = new FieldLeaf(List.of(step));
                    } else if (value.expr instanceof ChainedFieldLeaf existing) {
                        // 已经在非 this 受体上的字段链，追加
                        List<FieldLeaf.FieldStep> path = new ArrayList<>(existing.path);
                        path.add(step);
                        newExpr = new ChainedFieldLeaf(existing.root, path);
                    } else if (value.expr instanceof MapEntryLeaf
                            || value.expr instanceof MapByStaticFieldLeaf
                            || value.expr instanceof ArrayIndexLeaf
                            || value.expr instanceof UnresolvedCall
                            || value.expr instanceof InvertibleCall) {
                        // 受体是其他 leaf 形式（map.get 结果、数组元素、未知方法返回值等）→ 启动新 ChainedFieldLeaf
                        newExpr = new ChainedFieldLeaf(value.expr, List.of(step));
                    } else if (value.expr instanceof StaticFieldMarker sfm) {
                        // 静态字段对象上取字段，把 sfm 当成 root（极少见，但允许）
                        newExpr = new ChainedFieldLeaf(sfm, List.of(step));
                    } else {
                        newExpr = UnknownExpr.I;
                    }
                    yield new TaintValue(t.getSize(), newExpr);
                }
                case Opcodes.CHECKCAST -> value;
                case Opcodes.INSTANCEOF -> new TaintValue(1, UnknownExpr.I);
                case Opcodes.ARRAYLENGTH -> new TaintValue(1, UnknownExpr.I);
                case Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> new TaintValue(1, UnknownExpr.I);
                case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE,
                     Opcodes.IFGT, Opcodes.IFLE, Opcodes.IFNULL, Opcodes.IFNONNULL,
                     Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH,
                     Opcodes.PUTSTATIC, Opcodes.ATHROW,
                     Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> null;
                default -> new TaintValue(1, UnknownExpr.I);
            };
        }

        @Override public TaintValue binaryOperation(AbstractInsnNode insn, TaintValue v1, TaintValue v2) {
            int op = insn.getOpcode();
            return switch (op) {
                case Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM,
                     Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM,
                     Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR ->
                    new TaintValue(1, new BinaryOp(op, v1.expr, v2.expr));
                case Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL, Opcodes.LDIV, Opcodes.LREM,
                     Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL, Opcodes.DDIV, Opcodes.DREM,
                     Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR ->
                    new TaintValue(2, new BinaryOp(op, v1.expr, v2.expr));
                case Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR ->
                    new TaintValue(2, new BinaryOp(op, v1.expr, v2.expr));
                // 数组元素加载：返回元素，size 由元素类型决定
                case Opcodes.AALOAD, Opcodes.IALOAD, Opcodes.FALOAD,
                     Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD ->
                    new TaintValue(1, new ArrayIndexLeaf(v1.expr, v2.expr));
                case Opcodes.LALOAD, Opcodes.DALOAD ->
                    new TaintValue(2, new ArrayIndexLeaf(v1.expr, v2.expr));
                default -> new TaintValue(1, UnknownExpr.I);
            };
        }

        @Override public TaintValue ternaryOperation(AbstractInsnNode insn, TaintValue v1, TaintValue v2, TaintValue v3) {
            return null;
        }

        @Override public TaintValue naryOperation(AbstractInsnNode insn, List<? extends TaintValue> values) {
            if (insn.getOpcode() == Opcodes.MULTIANEWARRAY) return new TaintValue(1, UnknownExpr.I);
            if (insn.getOpcode() == Opcodes.INVOKEDYNAMIC) {
                Type ret = Type.getReturnType(((InvokeDynamicInsnNode) insn).desc);
                return ret == Type.VOID_TYPE ? null : new TaintValue(ret.getSize(), UnknownExpr.I);
            }
            MethodInsnNode m = (MethodInsnNode) insn;
            Type retType = Type.getReturnType(m.desc);
            int sz = retType == Type.VOID_TYPE ? 0 : retType.getSize();
            if (retType == Type.VOID_TYPE) return null;

            // Number 拆箱（floatValue/doubleValue/intValue/...）当 identity，剥掉 SynchedEntityData.get 后编译器插入的 CHECKCAST + 拆箱包装
            if (m.getOpcode() == Opcodes.INVOKEVIRTUAL && values.size() == 1
                && isNumberUnboxingMethod(m.owner, m.name, m.desc)) {
                return new TaintValue(sz, values.get(0).expr);
            }

            // 包装类装箱（Float.valueOf / Integer.valueOf / ...）当 identity
            if (m.getOpcode() == Opcodes.INVOKESTATIC && values.size() == 1
                && isWrapperValueOfMethod(m.owner, m.name, m.desc)) {
                return new TaintValue(sz, values.get(0).expr);
            }

            // 已知可逆/特殊静态方法（位运算反向）
            InvertibleCall.InvertibleKind invKind = detectInvertible(m);
            if (invKind != null) {
                List<Expr> argExprs = new ArrayList<>(values.size());
                for (TaintValue v : values) argExprs.add(v.expr);
                return new TaintValue(sz, new InvertibleCall(invKind, argExprs));
            }

            // SynchedEntityData.get(accessor)
            if (m.owner.equals("net/minecraft/network/syncher/SynchedEntityData")
                && (m.name.equals("m_135370_") || m.name.equals("get"))
                && values.size() >= 2) {
                TaintValue accessorArg = values.get(1);
                if (accessorArg.expr instanceof StaticFieldMarker sfm) {
                    return new TaintValue(sz, new EntityDataLeaf(sfm.ownerInternal, sfm.name));
                }
                return new TaintValue(sz, UnknownExpr.I);
            }

            // Map.get / getOrDefault —— 容器来自静态方法 或 静态字段
            if ((m.name.equals("get") || m.name.equals("getOrDefault"))
                && values.size() >= 2
                && (m.owner.endsWith("Map") || m.owner.endsWith("HashMap")
                    || m.owner.equals("java/util/Map")
                    || m.owner.equals("java/util/HashMap")
                    || m.owner.equals("java/util/WeakHashMap")
                    || m.owner.equals("java/util/concurrent/ConcurrentHashMap"))) {
                TaintValue recv = values.get(0);
                TaintValue keyArg = values.get(1);
                MapEntryLeaf.KeyKind kind = detectKeyKind(keyArg.expr);
                if (recv.expr instanceof StaticMethodResult smr) {
                    return new TaintValue(sz, new MapEntryLeaf(smr.ownerInternal, smr.name, kind));
                }
                if (recv.expr instanceof StaticFieldMarker sfm) {
                    return new TaintValue(sz, new MapByStaticFieldLeaf(sfm.ownerInternal, sfm.name, kind));
                }
            }

            // 尝试递归分析
            if (depth + 1 < ctx.maxDepth && !m.name.startsWith("<")) {
                Expr inlined = tryInlineInvoke(m, values);
                if (inlined != null && !(inlined instanceof UnknownExpr)) {
                    return new TaintValue(sz, inlined);
                }
            }

            // 静态方法返回对象 —— 记为容器 getter 候选
            if (m.getOpcode() == Opcodes.INVOKESTATIC && retType.getSort() == Type.OBJECT) {
                return new TaintValue(sz, new StaticMethodResult(m.owner, m.name, m.desc));
            }

            // 兜底
            List<Expr> argExprs = new ArrayList<>(values.size());
            for (TaintValue v : values) argExprs.add(v.expr);
            return new TaintValue(sz, new UnresolvedCall(m.owner, m.name, m.desc, argExprs));
        }

        private Expr tryInlineInvoke(MethodInsnNode m, List<? extends TaintValue> values) {
            if (m.owner.startsWith("java/") || m.owner.startsWith("net/minecraft/")) return null;
            Class<?> owner;
            try {
                owner = Class.forName(m.owner.replace('/', '.'), false, Thread.currentThread().getContextClassLoader());
            } catch (Throwable t) { return null; }
            boolean isStatic = m.getOpcode() == Opcodes.INVOKESTATIC;
            Type[] argTypes = Type.getArgumentTypes(m.desc);
            int localCount = (isStatic ? 0 : 1);
            for (Type at : argTypes) localCount += at.getSize();
            TaintValue[] seed = new TaintValue[localCount + 8];
            int idx = 0;
            int vidx = 0;
            if (!isStatic) { seed[idx++] = values.get(vidx++); }
            for (Type at : argTypes) {
                TaintValue arg = values.get(vidx++);
                seed[idx] = arg;
                idx += at.getSize();
            }
            return analyzeMethod(owner, m.name, m.desc, seed, ctx, depth + 1);
        }

        private static boolean isWrapperValueOfMethod(String owner, String name, String desc) {
            if (!name.equals("valueOf")) return false;
            if (!owner.startsWith("java/lang/")) return false;
            String simple = owner.substring(10);
            return (simple.equals("Float") && desc.equals("(F)Ljava/lang/Float;"))
                || (simple.equals("Double") && desc.equals("(D)Ljava/lang/Double;"))
                || (simple.equals("Integer") && desc.equals("(I)Ljava/lang/Integer;"))
                || (simple.equals("Long") && desc.equals("(J)Ljava/lang/Long;"))
                || (simple.equals("Short") && desc.equals("(S)Ljava/lang/Short;"))
                || (simple.equals("Byte") && desc.equals("(B)Ljava/lang/Byte;"))
                || (simple.equals("Boolean") && desc.equals("(Z)Ljava/lang/Boolean;"))
                || (simple.equals("Character") && desc.equals("(C)Ljava/lang/Character;"));
        }

        private static InvertibleCall.InvertibleKind detectInvertible(MethodInsnNode m) {
            if (m.getOpcode() != Opcodes.INVOKESTATIC) return null;
            if (m.owner.equals("java/lang/Long")
                && m.name.equals("reverse") && m.desc.equals("(J)J")) {
                return InvertibleCall.InvertibleKind.LONG_REVERSE;
            }
            if (m.owner.equals("java/lang/Float")
                && m.name.equals("intBitsToFloat") && m.desc.equals("(I)F")) {
                return InvertibleCall.InvertibleKind.INT_BITS_TO_FLOAT;
            }
            if (m.owner.equals("java/lang/Float")
                && (m.name.equals("floatToRawIntBits") || m.name.equals("floatToIntBits"))
                && m.desc.equals("(F)I")) {
                return InvertibleCall.InvertibleKind.FLOAT_TO_RAW_INT_BITS;
            }
            if (m.owner.equals("java/lang/Math")
                && m.name.equals("max") && m.desc.equals("(FF)F")) {
                return InvertibleCall.InvertibleKind.MATH_MAX_F;
            }
            return null;
        }

        private static boolean isNumberUnboxingMethod(String owner, String name, String desc) {
            if (!owner.startsWith("java/lang/")) return false;
            String simple = owner.substring(10);
            boolean wrapper = simple.equals("Float") || simple.equals("Double")
                || simple.equals("Integer") || simple.equals("Long")
                || simple.equals("Short") || simple.equals("Byte")
                || simple.equals("Boolean") || simple.equals("Character")
                || simple.equals("Number");
            if (!wrapper) return false;
            return (name.equals("floatValue") && desc.equals("()F"))
                || (name.equals("doubleValue") && desc.equals("()D"))
                || (name.equals("intValue") && desc.equals("()I"))
                || (name.equals("longValue") && desc.equals("()J"))
                || (name.equals("shortValue") && desc.equals("()S"))
                || (name.equals("byteValue") && desc.equals("()B"))
                || (name.equals("booleanValue") && desc.equals("()Z"))
                || (name.equals("charValue") && desc.equals("()C"));
        }

        private MapEntryLeaf.KeyKind detectKeyKind(Expr keyExpr) {
            if (keyExpr instanceof EntityParamMarker) return MapEntryLeaf.KeyKind.ENTITY;
            if (keyExpr instanceof UnresolvedCall uc && uc.args.size() >= 1 && uc.args.get(0) instanceof EntityParamMarker) {
                if (uc.name.equals("m_19879_") || uc.name.equals("getId")) return MapEntryLeaf.KeyKind.ENTITY_ID;
                if (uc.name.equals("m_20148_") || uc.name.equals("getUUID")) return MapEntryLeaf.KeyKind.ENTITY_UUID;
            }
            return MapEntryLeaf.KeyKind.UNKNOWN;
        }

        @Override public void returnOperation(AbstractInsnNode insn, TaintValue value, TaintValue expected) {}

        @Override public TaintValue merge(TaintValue v1, TaintValue v2) {
            if (v1.equals(v2)) return v1;
            List<Expr> alts = new ArrayList<>();
            addAlt(alts, v1.expr);
            addAlt(alts, v2.expr);
            if (alts.size() == 1) return new TaintValue(Math.max(v1.size, v2.size), alts.get(0));
            return new TaintValue(Math.max(v1.size, v2.size), new Choice(alts));
        }

        private static void addAlt(List<Expr> into, Expr e) {
            if (e instanceof Choice c) {
                for (Expr a : c.alternatives) if (!into.contains(a)) into.add(a);
            } else if (!into.contains(e)) into.add(e);
        }
    }

    // ==================== 反向求解 ====================

    //给定表达式 e、目标返回值 target、写入的 sink（表达式里的某个叶子）、以及读运行时值的函数，
    //解出 sink 应该写什么值。不可解时返回 null。target 和 readCurrent 用 Number 承载，让 long-domain（XOR/Long.reverse 等）也能解。
    public static Number solve(Expr e, Expr sink, Number target, BiFunction<Expr, Void, Number> readCurrent) {
        if (e == sink) return target;
        if (e instanceof Choice c) {
            for (Expr alt : c.alternatives) {
                if (!containsSink(alt, sink)) continue;
                Number v = solve(alt, sink, target, readCurrent);
                if (v != null) return v;
            }
            return null;
        }
        if (e instanceof BinaryOp b) {
            boolean leftContains = containsSink(b.left, sink);
            boolean rightContains = containsSink(b.right, sink);
            if (leftContains == rightContains) return null;
            if (leftContains) {
                Number rv = evaluate(b.right, readCurrent);
                if (rv == null) return null;
                Number lt = invertBinary(b.opcode, target, rv, true);
                if (lt == null) return null;
                return solve(b.left, sink, lt, readCurrent);
            } else {
                Number lv = evaluate(b.left, readCurrent);
                if (lv == null) return null;
                Number rt = invertBinary(b.opcode, target, lv, false);
                if (rt == null) return null;
                return solve(b.right, sink, rt, readCurrent);
            }
        }
        if (e instanceof UnaryOp u) {
            Number inner = invertUnary(u.opcode, target);
            if (inner == null) return null;
            return solve(u.operand, sink, inner, readCurrent);
        }
        if (e instanceof InvertibleCall ic) {
            Number newTarget = invertInvertibleCall(ic, target, sink, readCurrent);
            if (newTarget == null) return null;
            Expr argWithSink = null;
            for (Expr arg : ic.args) {
                if (containsSink(arg, sink)) { argWithSink = arg; break; }
            }
            if (argWithSink == null) return null;
            return solve(argWithSink, sink, newTarget, readCurrent);
        }
        return null;
    }

    private static Number invertBinary(int op, Number target, Number other, boolean solveLeft) {
        return switch (op) {
            case Opcodes.IADD -> Integer.valueOf(target.intValue() - other.intValue());
            case Opcodes.LADD -> Long.valueOf(target.longValue() - other.longValue());
            case Opcodes.FADD -> Float.valueOf(target.floatValue() - other.floatValue());
            case Opcodes.DADD -> Double.valueOf(target.doubleValue() - other.doubleValue());
            case Opcodes.ISUB -> Integer.valueOf(solveLeft ? target.intValue() + other.intValue() : other.intValue() - target.intValue());
            case Opcodes.LSUB -> Long.valueOf(solveLeft ? target.longValue() + other.longValue() : other.longValue() - target.longValue());
            case Opcodes.FSUB -> Float.valueOf(solveLeft ? target.floatValue() + other.floatValue() : other.floatValue() - target.floatValue());
            case Opcodes.DSUB -> Double.valueOf(solveLeft ? target.doubleValue() + other.doubleValue() : other.doubleValue() - target.doubleValue());
            case Opcodes.IMUL -> other.intValue() == 0 ? null : Integer.valueOf(target.intValue() / other.intValue());
            case Opcodes.LMUL -> other.longValue() == 0 ? null : Long.valueOf(target.longValue() / other.longValue());
            case Opcodes.FMUL -> other.floatValue() == 0 ? null : Float.valueOf(target.floatValue() / other.floatValue());
            case Opcodes.DMUL -> other.doubleValue() == 0 ? null : Double.valueOf(target.doubleValue() / other.doubleValue());
            case Opcodes.IDIV -> {
                if (solveLeft) yield Integer.valueOf(target.intValue() * other.intValue());
                if (target.intValue() == 0) yield null;
                yield Integer.valueOf(other.intValue() / target.intValue());
            }
            case Opcodes.LDIV -> {
                if (solveLeft) yield Long.valueOf(target.longValue() * other.longValue());
                if (target.longValue() == 0) yield null;
                yield Long.valueOf(other.longValue() / target.longValue());
            }
            case Opcodes.FDIV -> {
                if (solveLeft) yield Float.valueOf(target.floatValue() * other.floatValue());
                if (target.floatValue() == 0f) yield null;
                yield Float.valueOf(other.floatValue() / target.floatValue());
            }
            case Opcodes.DDIV -> {
                if (solveLeft) yield Double.valueOf(target.doubleValue() * other.doubleValue());
                if (target.doubleValue() == 0d) yield null;
                yield Double.valueOf(other.doubleValue() / target.doubleValue());
            }
            // XOR 自逆：x ^ o = t → x = t ^ o
            case Opcodes.IXOR -> Integer.valueOf((int)(target.longValue() ^ other.longValue()));
            case Opcodes.LXOR -> Long.valueOf(target.longValue() ^ other.longValue());
            default -> null;
        };
    }

    private static Number invertUnary(int op, Number target) {
        return switch (op) {
            case Opcodes.INEG -> Integer.valueOf(-target.intValue());
            case Opcodes.LNEG -> Long.valueOf(-target.longValue());
            case Opcodes.FNEG -> Float.valueOf(-target.floatValue());
            case Opcodes.DNEG -> Double.valueOf(-target.doubleValue());
            // L2I 截低 32：写时高 32 填 0（任意值都行，read 时同样会被截）
            case Opcodes.L2I -> Long.valueOf(target.longValue() & 0xFFFFFFFFL);
            case Opcodes.I2L -> Integer.valueOf(target.intValue());
            case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> Float.valueOf(target.floatValue());
            case Opcodes.F2I, Opcodes.D2I -> Integer.valueOf(target.intValue());
            case Opcodes.F2L, Opcodes.D2L -> Long.valueOf(target.longValue());
            case Opcodes.F2D, Opcodes.I2D, Opcodes.L2D -> Double.valueOf(target.doubleValue());
            case Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> target;
            default -> null;
        };
    }

    private static Number invertInvertibleCall(InvertibleCall ic, Number target, Expr sink,
                                               BiFunction<Expr, Void, Number> readCurrent) {
        return switch (ic.kind) {
            case LONG_REVERSE -> Long.valueOf(Long.reverse(target.longValue()));
            case INT_BITS_TO_FLOAT -> Integer.valueOf(Float.floatToRawIntBits(target.floatValue()));
            case FLOAT_TO_RAW_INT_BITS -> Float.valueOf(Float.intBitsToFloat(target.intValue()));
            case MATH_MAX_F -> {
                // max(a, b) = target；只解含 sink 的那个 arg，另一个 evaluate
                Expr argWithSink = null, other = null;
                for (Expr arg : ic.args) {
                    if (containsSink(arg, sink)) argWithSink = arg;
                    else other = arg;
                }
                if (argWithSink == null) yield null;
                Number otherVal = other != null ? evaluate(other, readCurrent) : Float.valueOf(0f);
                if (otherVal == null) yield null;
                float t = target.floatValue(), o = otherVal.floatValue();
                // target >= other 时 sink_arg = target；否则不可解
                if (t >= o) yield Float.valueOf(t);
                yield null;
            }
        };
    }

    private static boolean containsSink(Expr e, Expr sink) {
        if (e == sink) return true;
        if (e instanceof BinaryOp b) return containsSink(b.left, sink) || containsSink(b.right, sink);
        if (e instanceof UnaryOp u) return containsSink(u.operand, sink);
        if (e instanceof Choice c) {
            for (Expr a : c.alternatives) if (containsSink(a, sink)) return true;
            return false;
        }
        if (e instanceof InvertibleCall ic) {
            for (Expr a : ic.args) if (containsSink(a, sink)) return true;
            return false;
        }
        if (e instanceof UnresolvedCall uc) {
            for (Expr a : uc.args) if (containsSink(a, sink)) return true;
            return false;
        }
        return false;
    }

    public static Number evaluate(Expr e, BiFunction<Expr, Void, Number> readCurrent) {
        if (e instanceof Const c) return Float.valueOf(c.value);
        if (e instanceof FieldLeaf || e instanceof EntityDataLeaf || e instanceof MapEntryLeaf
            || e instanceof MapByStaticFieldLeaf || e instanceof ArrayIndexLeaf || e instanceof ChainedFieldLeaf) {
            return readCurrent.apply(e, null);
        }
        if (e instanceof Choice c) {
            for (Expr alt : c.alternatives) {
                Number v = evaluate(alt, readCurrent);
                if (v != null) return v;
            }
            return null;
        }
        if (e instanceof BinaryOp b) {
            Number l = evaluate(b.left, readCurrent);
            Number r = evaluate(b.right, readCurrent);
            if (l == null || r == null) return null;
            return computeBinary(b.opcode, l, r);
        }
        if (e instanceof UnaryOp u) {
            Number v = evaluate(u.operand, readCurrent);
            if (v == null) return null;
            return computeUnary(u.opcode, v);
        }
        if (e instanceof InvertibleCall ic) {
            List<Number> argVals = new ArrayList<>();
            for (Expr arg : ic.args) {
                Number v = evaluate(arg, readCurrent);
                if (v == null) return null;
                argVals.add(v);
            }
            return applyInvertibleCall(ic.kind, argVals);
        }
        return null;
    }

    private static Number computeBinary(int op, Number l, Number r) {
        return switch (op) {
            case Opcodes.IADD -> Integer.valueOf(l.intValue() + r.intValue());
            case Opcodes.LADD -> Long.valueOf(l.longValue() + r.longValue());
            case Opcodes.FADD -> Float.valueOf(l.floatValue() + r.floatValue());
            case Opcodes.DADD -> Double.valueOf(l.doubleValue() + r.doubleValue());
            case Opcodes.ISUB -> Integer.valueOf(l.intValue() - r.intValue());
            case Opcodes.LSUB -> Long.valueOf(l.longValue() - r.longValue());
            case Opcodes.FSUB -> Float.valueOf(l.floatValue() - r.floatValue());
            case Opcodes.DSUB -> Double.valueOf(l.doubleValue() - r.doubleValue());
            case Opcodes.IMUL -> Integer.valueOf(l.intValue() * r.intValue());
            case Opcodes.LMUL -> Long.valueOf(l.longValue() * r.longValue());
            case Opcodes.FMUL -> Float.valueOf(l.floatValue() * r.floatValue());
            case Opcodes.DMUL -> Double.valueOf(l.doubleValue() * r.doubleValue());
            case Opcodes.IDIV -> r.intValue() == 0 ? null : Integer.valueOf(l.intValue() / r.intValue());
            case Opcodes.LDIV -> r.longValue() == 0L ? null : Long.valueOf(l.longValue() / r.longValue());
            case Opcodes.FDIV -> r.floatValue() == 0f ? null : Float.valueOf(l.floatValue() / r.floatValue());
            case Opcodes.DDIV -> r.doubleValue() == 0d ? null : Double.valueOf(l.doubleValue() / r.doubleValue());
            case Opcodes.IXOR -> Integer.valueOf(l.intValue() ^ r.intValue());
            case Opcodes.LXOR -> Long.valueOf(l.longValue() ^ r.longValue());
            case Opcodes.IAND -> Integer.valueOf(l.intValue() & r.intValue());
            case Opcodes.LAND -> Long.valueOf(l.longValue() & r.longValue());
            case Opcodes.IOR -> Integer.valueOf(l.intValue() | r.intValue());
            case Opcodes.LOR -> Long.valueOf(l.longValue() | r.longValue());
            default -> null;
        };
    }

    private static Number computeUnary(int op, Number v) {
        return switch (op) {
            case Opcodes.INEG -> Integer.valueOf(-v.intValue());
            case Opcodes.LNEG -> Long.valueOf(-v.longValue());
            case Opcodes.FNEG -> Float.valueOf(-v.floatValue());
            case Opcodes.DNEG -> Double.valueOf(-v.doubleValue());
            case Opcodes.L2I -> Integer.valueOf((int) v.longValue());
            case Opcodes.I2L -> Long.valueOf(v.intValue());
            case Opcodes.F2I -> Integer.valueOf((int) v.floatValue());
            case Opcodes.I2F -> Float.valueOf(v.intValue());
            case Opcodes.D2F -> Float.valueOf((float) v.doubleValue());
            case Opcodes.F2D -> Double.valueOf(v.floatValue());
            case Opcodes.L2F -> Float.valueOf(v.longValue());
            case Opcodes.F2L -> Long.valueOf((long) v.floatValue());
            case Opcodes.D2L -> Long.valueOf((long) v.doubleValue());
            case Opcodes.L2D -> Double.valueOf(v.longValue());
            case Opcodes.D2I -> Integer.valueOf((int) v.doubleValue());
            case Opcodes.I2D -> Double.valueOf(v.intValue());
            case Opcodes.I2B -> Integer.valueOf((byte) v.intValue());
            case Opcodes.I2C -> Integer.valueOf((char) v.intValue());
            case Opcodes.I2S -> Integer.valueOf((short) v.intValue());
            default -> v;
        };
    }

    private static Number applyInvertibleCall(InvertibleCall.InvertibleKind kind, List<Number> args) {
        return switch (kind) {
            case LONG_REVERSE -> args.size() < 1 ? null : Long.valueOf(Long.reverse(args.get(0).longValue()));
            case INT_BITS_TO_FLOAT -> args.size() < 1 ? null : Float.valueOf(Float.intBitsToFloat(args.get(0).intValue()));
            case FLOAT_TO_RAW_INT_BITS -> args.size() < 1 ? null : Integer.valueOf(Float.floatToRawIntBits(args.get(0).floatValue()));
            case MATH_MAX_F -> args.size() < 2 ? null : Float.valueOf(Math.max(args.get(0).floatValue(), args.get(1).floatValue()));
        };
    }

}
