package net.eca.util.health;

import net.eca.coremod.AccessTrace;
import net.eca.util.EcaLogger;
import net.eca.util.health.HealthAnalyzer.ArrayElementSource;
import net.eca.util.health.HealthAnalyzer.Call;
import net.eca.util.health.HealthAnalyzer.Choice;
import net.eca.util.health.HealthAnalyzer.EvalContext;
import net.eca.util.health.HealthAnalyzer.Expr;
import net.eca.util.health.HealthAnalyzer.Op;
import net.eca.util.health.HealthAnalyzer.Primitive;
import net.eca.util.health.HealthAnalyzer.Reference;
import net.eca.util.health.HealthAnalyzer.Source;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Type;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/*
 * Dynamic 阶段的求解器（消费 DynamicHealthTracer 的取证轨迹）。两条路径，优先级从通用到特化：
 *   路径1 直接种子分析：以真实 entity 为 this 种子分析整条 getHealth，若能解出叶子为具体 Source 的表达式树，
 *         逐个 solveFor + Source.write 写回。适用于存储静态可见的情况(字段链 / Map / 数组 / SynchedData)。
 *   路径2 轨迹解码反演：getHealth 经 MethodHandle/不透明容器遮挡导致路径1 无 Source 时，借取证阶段的运行期轨迹
 *         (A)反演外层包装得到内部应返回值，(B)以轨迹捕获的真实 receiver/实参为种子分析"解码方法"、
 *         定位与捕获 cell 匹配的 sink，solveFor 反演，(C)写回捕获的真实数组 cell。
 * 不认识任何 mod：只依赖"getHealth = f(可写存储)"且 f 由可逆运算构成。
 */
public final class DynamicHealthSolver {

    private DynamicHealthSolver() {}

    private static final String GETHEALTH = "m_21223_";
    private static final String GETHEALTH_ALT = "getHealth";

    // ==================== 路径1：直接种子分析整条 getHealth(零插桩) ====================

    static boolean solveBySeededTree(LivingEntity entity, float target) {
        try {
        Class<?> owner = findGetHealthOwner(entity.getClass());
        if (owner == null) return false;
        String name = hasMethod(owner, GETHEALTH) ? GETHEALTH : GETHEALTH_ALT;

        //以 this 占位符（而非 Reference(entity)）做种子：让 super.getHealth()→DATA_HEALTH_ID、this 字段链、SynchedData、Map-key 等
        //依赖 this 的识别全部生效；leaves 仍可 concrete 求值（evaluate 把占位符解析回真实 entity）
        Expr tree = HealthAnalyzer.analyzeSeeded(owner, name, "()F",
            new Expr[]{ HealthAnalyzer.thisMarker() });
        if (tree == null) return false;

        Set<Source> sources = HealthAnalyzer.collectSources(tree);
        if (sources.isEmpty()) return false;

        EvalContext ctx = new Ctx(entity);
        boolean anyWrote = false;
        for (Source sink : sources) {
            Object value = HealthAnalyzer.solveFor(tree, sink, Float.valueOf(target), ctx);
            if (value == null) continue;
            if (sink.write(entity, value)) {
                anyWrote = true;
            }
        }
        if (!anyWrote) return false;
        return Math.abs(entity.getHealth() - target) < 1.0f;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            EcaLogger.warn("[DynamicSolve] (seeded) exception: {}", t.toString());
            return false;
        }
    }

    // ==================== 路径2：轨迹解码反演 ====================

    static boolean solveByTraceDecoder(LivingEntity entity, float target,
                                       List<AccessTrace.Entry> reads, List<AccessTrace.MethodEntry> methods) {
      try {
        EvalContext ctx = new Ctx(entity);

        // (A) 反演外层 getHealth 包装，得到"内部读取需返回的值"
        Float requiredInner = solveOuterWrapper(entity, target, ctx);
        if (requiredInner == null) {
            EcaLogger.warn("[DynamicSolve] (A) outer getHealth inversion failed");
            return false;
        }

        // (B) 选解码方法 + 定位可写 cell + 反演
        Decoder dec = pickDecoder(reads, methods);
        if (dec == null) {
            EcaLogger.warn("[DynamicSolve] (B) could not identify decoder method + writable cell from trace");
            return false;
        }
        Expr tree = buildDecoderTree(dec);
        if (tree == null) {
            EcaLogger.warn("[DynamicSolve] (B) static analysis of decoder method failed");
            return false;
        }
        Source sink = findCellSink(tree, dec, ctx);
        if (sink == null) {
            EcaLogger.warn("[DynamicSolve] (B) no sink matched the captured cell");
            return false;
        }
        Object solved = HealthAnalyzer.solveFor(tree, sink, Float.valueOf(requiredInner), ctx);
        if (solved == null) {
            EcaLogger.warn("[DynamicSolve] (B) solveFor returned null");
            return false;
        }

        // (C) 写回捕获的真实数组 cell（按组件类型）
        Number num = (Number) solved;
        Class<?> comp = dec.cellArray.getClass().getComponentType();
        if (comp == long.class) Array.setLong(dec.cellArray, dec.cellIndex, num.longValue());
        else if (comp == int.class) Array.setInt(dec.cellArray, dec.cellIndex, num.intValue());
        else Array.set(dec.cellArray, dec.cellIndex, num);

        float after = entity.getHealth();
        boolean ok = Math.abs(after - target) < 1.0f;
        if (!ok) EcaLogger.warn("[DynamicSolve] (trace) wrote cell but getHealth={} != target={}", after, target);
        return ok;
      } catch (Throwable t) {
        if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
        EcaLogger.warn("[DynamicSolve] (trace) exception: {}", t.toString());
        return false;
      }
    }

    //分析 getHealth 自身字节码，把内部不可解析的数值 Call 当 sink 反推出它应返回的值
    private static Float solveOuterWrapper(LivingEntity entity, float target, EvalContext ctx) {
        Class<?> owner = findGetHealthOwner(entity.getClass());
        if (owner == null) return null;
        String name = hasMethod(owner, GETHEALTH) ? GETHEALTH : GETHEALTH_ALT;

        Expr tree = HealthAnalyzer.analyzeSeeded(owner, name, "()F",
            new Expr[]{ new Reference(entity, ownerInternal(owner)) });
        if (tree == null) return null;

        Call opaque = findOpaqueNumericCall(tree);
        if (opaque == null) return null;
        HoleSink hole = new HoleSink();
        Expr substituted = substitute(tree, opaque, hole);
        Object r = HealthAnalyzer.solveFor(substituted, hole, Float.valueOf(target), ctx);
        return r instanceof Number n ? n.floatValue() : null;
    }

    private static final class Decoder {
        AccessTrace.MethodEntry method;
        Object receiver;
        Expr[] seedArgs;
        Object cellArray;
        int cellIndex;
    }

    //选解码方法：返回 float、其执行期读了某个 long[]/int[] cell。cell 取窗口内最后一个
    private static Decoder pickDecoder(List<AccessTrace.Entry> reads, List<AccessTrace.MethodEntry> methods) {
        for (int mi = methods.size() - 1; mi >= 0; mi--) {
            AccessTrace.MethodEntry m = methods.get(mi);
            String[] on = splitSite(m.site);
            if (on == null) continue;
            Class<?> owner = loadClass(on[0]);
            if (owner == null) continue;
            String desc = findMethodDesc(owner, on[1]);
            if (desc == null || !Type.getReturnType(desc).equals(Type.FLOAT_TYPE)) continue;

            AccessTrace.Entry cell = null;
            for (AccessTrace.Entry e : reads) {
                if (!e.site.startsWith(m.site + " ")) continue;
                if (e.index < 0 || e.container == null) continue;
                String cn = e.container.getClass().getName();
                if (cn.equals("[J") || cn.equals("[I")) cell = e;
            }
            if (cell == null) continue;

            Decoder d = new Decoder();
            d.method = m;
            d.receiver = m.receiver;
            d.seedArgs = seedArgsOf(m.args, desc);
            d.cellArray = cell.container;
            d.cellIndex = (int) cell.index;
            return d;
        }
        return null;
    }

    private static Expr buildDecoderTree(Decoder dec) {
        String[] on = splitSite(dec.method.site);
        Class<?> owner = loadClass(on[0]);
        if (owner == null) return null;
        String desc = findMethodDesc(owner, on[1]);
        if (desc == null) return null;

        List<Expr> seeds = new ArrayList<>();
        if (dec.receiver != null) seeds.add(new Reference(dec.receiver, on[0]));
        for (Expr a : dec.seedArgs) seeds.add(a);
        return HealthAnalyzer.analyzeSeeded(owner, on[1], desc, seeds.toArray(new Expr[0]));
    }

    private static Source findCellSink(Expr tree, Decoder dec, EvalContext ctx) {
        for (Source s : HealthAnalyzer.collectSources(tree)) {
            if (!(s instanceof ArrayElementSource arr)) continue;
            try {
                Object a = HealthAnalyzer.evaluate(arr.arrayExpr, ctx);
                Object i = HealthAnalyzer.evaluate(arr.indexExpr, ctx);
                if (a == dec.cellArray && i instanceof Number n && n.intValue() == dec.cellIndex) return s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Expr[] seedArgsOf(Object[] args, String desc) {
        Type[] pts = Type.getArgumentTypes(desc);
        Expr[] out = new Expr[args.length];
        for (int i = 0; i < args.length && i < pts.length; i++) {
            Object a = args[i];
            char jt = switch (pts[i].getSort()) {
                case Type.LONG -> 'J';
                case Type.INT, Type.SHORT, Type.BYTE, Type.CHAR, Type.BOOLEAN -> 'I';
                case Type.FLOAT -> 'F';
                case Type.DOUBLE -> 'D';
                default -> 0;
            };
            if (jt != 0 && a instanceof Number n) out[i] = new Primitive(n, jt);
            else out[i] = a == null ? new Reference(null, "java/lang/Object")
                                    : new Reference(a, pts[i].getInternalName());
        }
        return out;
    }

    // ==================== 表达式工具 ====================

    private static final class HoleSink extends Source {
        HoleSink() { super(float.class, "HOLE"); }
        @Override public Object read(LivingEntity e) { return null; }
        @Override public boolean write(LivingEntity e, Object v) { return false; }
        @Override protected String canonicalKey() { return "HOLE@" + System.identityHashCode(this); }
    }

    private static Call findOpaqueNumericCall(Expr e) {
        if (e instanceof Call c) {
            Type rt = Type.getReturnType(c.desc());
            boolean numeric = rt.equals(Type.FLOAT_TYPE) || rt.equals(Type.INT_TYPE)
                || rt.equals(Type.LONG_TYPE) || rt.equals(Type.DOUBLE_TYPE);
            boolean hasInv = HealthAnalyzer.TABLE.lookupCall(c.owner(), c.name(), c.desc()) != null;
            if (numeric && !hasInv && !containsAnySource(c)) return c;
            for (Expr a : c.args()) { Call r = findOpaqueNumericCall(a); if (r != null) return r; }
        } else if (e instanceof Op op) {
            for (Expr a : op.args()) { Call r = findOpaqueNumericCall(a); if (r != null) return r; }
        } else if (e instanceof Choice ch) {
            for (Expr a : ch.alternatives()) { Call r = findOpaqueNumericCall(a); if (r != null) return r; }
        }
        return null;
    }

    private static boolean containsAnySource(Expr e) {
        if (e instanceof Source) return true;
        if (e instanceof Call c) { for (Expr a : c.args()) if (containsAnySource(a)) return true; }
        if (e instanceof Op op) { for (Expr a : op.args()) if (containsAnySource(a)) return true; }
        if (e instanceof Choice ch) { for (Expr a : ch.alternatives()) if (containsAnySource(a)) return true; }
        return false;
    }

    private static Expr substitute(Expr e, Expr target, Expr replacement) {
        if (e == target) return replacement;
        if (e instanceof Op op) {
            List<Expr> na = new ArrayList<>(op.args().size());
            for (Expr a : op.args()) na.add(substitute(a, target, replacement));
            return new Op(op.opcode(), na);
        }
        if (e instanceof Call c) {
            List<Expr> na = new ArrayList<>(c.args().size());
            for (Expr a : c.args()) na.add(substitute(a, target, replacement));
            return new Call(c.owner(), c.name(), c.desc(), na);
        }
        if (e instanceof Choice ch) {
            List<Expr> na = new ArrayList<>(ch.alternatives().size());
            for (Expr a : ch.alternatives()) na.add(substitute(a, target, replacement));
            return new Choice(na);
        }
        return e;
    }

    // ==================== 通用工具 ====================

    private record Ctx(LivingEntity entity) implements EvalContext {
        @Override public Object eval(Expr e) { return HealthAnalyzer.evaluate(e, this); }
    }

    private static Class<?> findGetHealthOwner(Class<?> start) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            if (hasMethod(c, GETHEALTH) || hasMethod(c, GETHEALTH_ALT)) return c;
        }
        return null;
    }

    private static boolean hasMethod(Class<?> c, String srgOrName) {
        try {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(srgOrName) && m.getParameterCount() == 0
                    && m.getReturnType() == float.class) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    //隐藏类 Foo/0x... → 模板内部名；getName() 丢包路径时用超类包补全
    private static String ownerInternal(Class<?> clazz) {
        String n = clazz.getName().replace('.', '/');
        int hidden = n.indexOf("/0x");
        if (hidden < 0) return n;
        String stripped = n.substring(0, hidden);
        if (stripped.indexOf('/') < 0) {
            Class<?> sup = clazz.getSuperclass();
            if (sup != null) {
                String supInternal = sup.getName().replace('.', '/');
                int lastSlash = supInternal.lastIndexOf('/');
                if (lastSlash > 0) stripped = supInternal.substring(0, lastSlash + 1) + stripped;
            }
        }
        return stripped;
    }

    private static String[] splitSite(String site) {
        int h = site.indexOf('#');
        if (h < 0) return null;
        return new String[]{ site.substring(0, h), site.substring(h + 1) };
    }

    private static Class<?> loadClass(String internal) {
        try {
            return Class.forName(internal.replace('/', '.'), false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable t) { return null; }
    }

    private static String findMethodDesc(Class<?> owner, String name) {
        try {
            for (java.lang.reflect.Method m : owner.getDeclaredMethods()) {
                if (m.getName().equals(name)) return Type.getMethodDescriptor(m);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String brief(Object o) {
        if (o == null) return "null";
        return o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }
}
