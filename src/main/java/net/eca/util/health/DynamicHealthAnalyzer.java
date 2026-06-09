package net.eca.util.health;

import net.eca.agent.EcaAgent;
import net.eca.coremod.AccessProbeTransformer;
import net.eca.coremod.AccessTrace;
import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * 动态数据流分析(P1：取证阶段)。当静态 HealthAnalyzer 对某实体返回 EMPTY/Unknown 时(getHealth被 MethodHandle/不透明容器/反射过滤藏住),
 * 用 Agent 给相关 mod 类插读取探针，真实执行一次getHealth，把它实际读取的 (容器对象, 槽位, 值) 收集打印——为后续(P2)重建+求解+写入做准备。
 */
public final class DynamicHealthAnalyzer {

    private DynamicHealthAnalyzer() {}

    private static final int MAX_TARGET_CLASSES = 48;

    //防重入：探针窗口内的 getHealth 不应再触发动态分析
    private static final ThreadLocal<Boolean> IN_PROGRESS = ThreadLocal.withInitial(() -> false);

    //基础设施类前缀，扫描时跳过(只插 mod 自有类)
    private static final String[] SKIP_PREFIXES = {
        "java/", "javax/", "jdk/", "sun/", "com/sun/",
        "net/minecraft/", "net/minecraftforge/", "net/eca/",
        "org/", "it/unimi/", "com/google/", "com/mojang/", "io/netty/", "com/electronwill/"
    };

    //入口：先零开销静态种子求解；不行再运行期插桩取证 + 轨迹反演。返回是否成功改血
    public static boolean probeAndResolve(LivingEntity entity, float target, boolean verbose) {
        if (entity == null || IN_PROGRESS.get()) return false;

        //路径1：纯静态种子分析(不插桩、不 retransform)。存储静态可见的实体到此即可解决
        if (DynamicHealthSolver.solveBySeededTree(entity, target)) return true;

        //路径2：静态够不着(MethodHandle/不透明容器遮挡) → 运行期插桩取证后反演
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) return false;

        Set<String> targetNames = resolveTargetClasses(entity.getClass());
        if (targetNames.isEmpty()) return false;

        Class<?>[] targets = resolveLoadedModifiable(inst, targetNames);
        if (targets.length == 0) return false;

        AccessProbeTransformer.ensureRegistered();
        List<AccessTrace.Entry> reads;
        List<AccessTrace.MethodEntry> methods;
        IN_PROGRESS.set(true);
        try {
            AccessProbeTransformer.setTargets(targetNames);
            //逐类注入：某些类(复杂库字节码等)插桩后可能 VerifyError，单类失败跳过即可，不影响其余类
            int injected = 0;
            for (Class<?> c : targets) {
                try {
                    inst.retransformClasses(c);
                    injected++;
                } catch (Throwable t) {
                    EcaLogger.info("[DynamicAnalysis] Skipped class that failed instrumentation {}: {}", c.getName(), t.getClass().getSimpleName());
                }
            }
            if (injected == 0) {
                EcaLogger.warn("[DynamicAnalysis] All target classes failed instrumentation, skipping entity={}", entity.getClass().getName());
                return false;
            }
            AccessTrace.begin();
            try {
                entity.getHealth();                           // 真实执行一次
            } catch (Throwable ignored) {}
            reads = AccessTrace.reads();
            methods = AccessTrace.methods();
            AccessTrace.finish();
        } catch (Throwable t) {
            AccessTrace.finish();
            EcaLogger.warn("[DynamicAnalysis] Trace capture failed entity={} msg={}", entity.getClass().getName(), t.toString());
            return false;
        } finally {
            AccessProbeTransformer.restoreAll(inst);   // 健壮撤桩：逐类还原，杜绝残桩
            IN_PROGRESS.set(false);
        }

        //轨迹反演(路径1 已在插桩前尝试过，这里只走轨迹解码路径)
        boolean ok = DynamicHealthSolver.solveByTraceDecoder(entity, target, reads, methods);

        //数据流逆向够不着时(自定义大数/装箱链表/不可符号化解码)，复用同一次取证的 reads 数值反演兜底，零额外插桩
        if (!ok) ok = NumericInversion.solve(entity, target, reads, verbose);

        //仅当全部失败、且该类首次失败时，打印一次轨迹用于诊断(成功路径完全静默)
        if (!ok && verbose) dump(entity, targetNames, reads, methods);
        return ok;
    }

    private static void dump(LivingEntity entity, Set<String> targets,
                             List<AccessTrace.Entry> reads, List<AccessTrace.MethodEntry> methods) {
        EcaLogger.warn("[DynamicAnalysis] entity={} instrumentedClasses={} methodEntries={} reads={}:",
            entity.getClass().getName(), targets.size(), methods.size(), reads.size());

        final int CAP = 40;   //限量，避免宽存储实体(数百字段)刷屏
        EcaLogger.warn("[DynamicAnalysis] === Method entries (receiver + args) ===");
        int m = 0;
        for (AccessTrace.MethodEntry e : methods) {
            if (m >= CAP) { EcaLogger.warn("  ... ({} more)", methods.size() - CAP); break; }
            EcaLogger.warn("  M#{} {} | recv={} | args={}", m++, e.site, brief(e.receiver), briefArgs(e.args));
        }

        EcaLogger.warn("[DynamicAnalysis] === Field/array reads ===");
        int i = 0;
        for (AccessTrace.Entry e : reads) {
            if (i >= CAP) { EcaLogger.warn("  ... ({} more)", reads.size() - CAP); break; }
            String idx = e.index >= 0 ? ("[" + e.index + "]") : "";
            EcaLogger.warn("  #{} {} | container={}{} | value={}", i++, e.site, brief(e.container), idx, String.valueOf(e.value));
        }
    }

    private static String brief(Object o) {
        if (o == null) return "static/null";
        return o.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }

    private static String briefArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object a = args[i];
            sb.append(a == null ? "null"
                : (a instanceof Number || a instanceof Boolean ? String.valueOf(a) : brief(a)));
        }
        return sb.append("]").toString();
    }

    // ==================== 插桩范围解析(静态引用 BFS) ====================

    //从 getHealth 所在类出发，BFS 收集它(及被引用 mod 类)引用到的 mod 类内部名
    private static Set<String> resolveTargetClasses(Class<?> entityClass) {
        Set<String> result = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();

        String seed = entityClass.getName().replace('.', '/');
        queue.add(seed);
        visited.add(seed);

        ClassLoader loader = entityClass.getClassLoader();

        while (!queue.isEmpty() && result.size() < MAX_TARGET_CLASSES) {
            String internal = queue.poll();
            result.add(internal);

            Set<String> refs = new HashSet<>();
            if (!collectReferences(loader, internal, refs)) continue;

            for (String ref : refs) {
                if (visited.contains(ref) || isSkipped(ref)) continue;
                visited.add(ref);
                queue.add(ref);
            }
        }
        result.removeIf(DynamicHealthAnalyzer::isSkipped);
        return result;
    }

    //读取类字节码，收集它引用的所有类内部名(方法/字段 owner + 类型引用)
    private static boolean collectReferences(ClassLoader loader, String internal, Set<String> out) {
        ClassLoader cl = loader != null ? loader : ClassLoader.getSystemClassLoader();
        try (InputStream is = cl.getResourceAsStream(internal + ".class")) {
            if (is == null) return false;
            ClassReader cr = new ClassReader(is);
            cr.accept(new RefCollector(out), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class RefCollector extends ClassVisitor {
        private final Set<String> out;
        RefCollector(Set<String> out) { super(Opcodes.ASM9); this.out = out; }

        @Override
        public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
                    addType(out, owner);
                    addDesc(out, desc);
                }
                @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                    addType(out, owner);
                }
                @Override public void visitTypeInsn(int op, String type) {
                    addType(out, type);
                }
            };
        }
    }

    private static void addType(Set<String> out, String internalOrArray) {
        if (internalOrArray == null) return;
        String t = internalOrArray;
        while (t.startsWith("[")) t = t.substring(1);            // 剥数组维度
        if (t.startsWith("L") && t.endsWith(";")) t = t.substring(1, t.length() - 1);
        if (!t.isEmpty() && t.indexOf('/') >= 0) out.add(t);
    }

    private static void addDesc(Set<String> out, String desc) {
        if (desc == null || desc.isEmpty()) return;
        char c = desc.charAt(0);
        if (c == 'L' || c == '[') {
            Type type = Type.getType(desc);
            if (type.getSort() == Type.ARRAY) type = type.getElementType();
            if (type.getSort() == Type.OBJECT) out.add(type.getInternalName());
        }
    }

    private static boolean isSkipped(String internal) {
        for (String p : SKIP_PREFIXES) {
            if (internal.startsWith(p)) return true;
        }
        return false;
    }

    private static Class<?>[] resolveLoadedModifiable(Instrumentation inst, Set<String> internalNames) {
        Map<String, Class<?>> loaded = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String in = c.getName().replace('.', '/');
            if (internalNames.contains(in)) loaded.put(in, c);
        }
        return loaded.values().stream()
            .filter(inst::isModifiableClass)
            .toArray(Class<?>[]::new);
    }
}
