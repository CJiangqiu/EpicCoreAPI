package net.eca.util.health;

import net.eca.agent.EcaAgent;
import net.eca.agent.AgentLogWriter;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class HealthNumericInverter {

    private static final int MAX_TARGET_CLASSES = 48;
    private static final int MAX_NUMERIC_CELLS = 128;
    private static final Set<String> DYNAMIC_DUMPED = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Boolean> IN_PROGRESS = ThreadLocal.withInitial(() -> false);
    private static final String GET_HEALTH = "m_21223_";
    private static final String GET_HEALTH_ALT = "getHealth";
    private static final String GET_HEALTH_DESC = "()F";
    private static final String[] SKIP_PREFIXES = {
            "java/", "javax/", "jdk/", "sun/", "com/sun/",
            "net/minecraft/", "net/minecraftforge/", "net/eca/",
            "org/", "it/unimi/", "com/google/", "com/mojang/", "io/netty/", "com/electronwill/"
    };

    private HealthNumericInverter() {}

    public static EcaSetHealthManager.HealthPath resolvePath(LivingEntity entity, float target) {
        if (entity == null) return null;
        return new EcaSetHealthManager.HealthPath(EcaSetHealthManager.WriteMethod.NUMERIC_INVERSION,
                (currentEntity, currentTarget) -> trySetHealth(currentEntity, currentTarget));
    }

    private static boolean trySetHealth(LivingEntity entity, float target) {
        if (entity == null || IN_PROGRESS.get()) return false;
        if (sourceNumeric(entity, target)) return true;
        return dynamicResolve(entity, target);
    }

    private static boolean sourceNumeric(LivingEntity entity, float target) {
        try {
            HealthDataflowAnalyzer.AnalysisResult ar = HealthDataflowAnalyzer.analyze(entity.getClass());
            if (ar == null || ar.isEmpty()) return false;
            for (HealthDataflowAnalyzer.Source source : ar.sources) {
                if (solveSource(entity, source, target)) {
                    EcaLogger.info("[HealthNumericInverter] source numeric succeeded entity={} source={}",
                            entity.getClass().getName(), source.label);
                    return true;
                }
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[HealthNumericInverter] source numeric failed entity={} msg={}",
                    entity.getClass().getName(), t.toString());
        }
        return false;
    }

    private static boolean solveSource(LivingEntity entity, HealthDataflowAnalyzer.Source source, float target) {
        Object snapshot = source.read(entity);
        if (!(snapshot instanceof Number baseline)) return false;
        double current = baseline.doubleValue();
        boolean success = false;
        try {
            for (int pass = 0; pass < 16; pass++) {
                float health = EcaSetHealthManager.safeGetHealth(entity);
                if (!Float.isFinite(health)) break;
                if (EcaSetHealthManager.verify(entity, target)) {
                    success = true;
                    return true;
                }
                double slope = sourceSlope(entity, source, current, health);
                if (!Double.isFinite(slope) || slope == 0.0) break;
                double step = (target - health) / slope;
                double error = Math.abs(target - health);
                boolean improved = false;
                double fraction = 1.0;
                for (int i = 0; i < 16; i++) {
                    if (!source.write(entity, current + step * fraction)) break;
                    float next = EcaSetHealthManager.safeGetHealth(entity);
                    if (Float.isFinite(next) && Math.abs(target - next) < error) {
                        Object value = source.read(entity);
                        if (value instanceof Number number) current = number.doubleValue();
                        improved = true;
                        break;
                    }
                    fraction *= 0.5;
                }
                if (!improved) break;
            }
            success = EcaSetHealthManager.verify(entity, target);
            return success;
        } finally {
            if (!success) source.write(entity, snapshot);
        }
    }

    private static double sourceSlope(LivingEntity entity, HealthDataflowAnalyzer.Source source, double current, float health) {
        for (float step : new float[]{1.0f, 16.0f, 256.0f, 4096.0f}) {
            if (!source.write(entity, current + step)) continue;
            float changed = EcaSetHealthManager.safeGetHealth(entity);
            source.write(entity, current);
            if (Float.isFinite(changed) && Math.abs(changed - health) > 1.0e-3f) {
                return (changed - health) / step;
            }
        }
        return 0.0;
    }

    private static boolean dynamicResolve(LivingEntity entity, float target) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            EcaLogger.info("[HealthNumericInverter] dynamic unavailable: no Instrumentation");
            return false;
        }

        Set<String> targetNames = resolveTargetClasses(entity.getClass());
        if (targetNames.isEmpty()) return false;

        Class<?>[] targets = resolveLoadedModifiable(inst, targetNames);
        if (targets.length == 0) return false;

        ProbeTransformer.ensureRegistered();
        // JVM TI 原生通道：注册 transform 函数 + 原生 retransform
        if (net.eca.coremod.JvmTiChannel.isActive()) {
            ProbeTransformer.ensureJvmTiRegistered();
            net.eca.coremod.JvmTiChannel.retransformClasses(targets);
        }
        boolean verbose = DYNAMIC_DUMPED.add(entity.getClass().getName());
        TraceSnapshot baseline;
        TraceSnapshot perturbed = TraceSnapshot.EMPTY;
        TraceSnapshot restored = TraceSnapshot.EMPTY;
        IN_PROGRESS.set(true);
        try {
            ProbeTransformer.setTargets(targetNames);
            int injected = 0;
            for (Class<?> targetClass : targets) {
                try {
                    inst.retransformClasses(targetClass);
                    injected++;
                } catch (Throwable t) {
                    EcaLogger.info("[HealthNumericInverter] skipped instrumentation {}: {}",
                            targetClass.getName(), t.getClass().getSimpleName());
                }
            }
            if (injected == 0) return false;

            baseline = captureGetHealthTrace(entity);
            float shellBefore = readBasicShellHealth(entity);
            float current = EcaSetHealthManager.safeGetHealth(entity);
            if (Float.isFinite(shellBefore) && Float.isFinite(current)) {
                float shellProbe = shellProbeValue(current);
                if (Math.abs(shellProbe - shellBefore) > 1.0e-3f) {
                    try {
                        EntityUtil.setBasicHealth(entity, shellProbe);
                        perturbed = captureGetHealthTrace(entity);
                    } finally {
                        EntityUtil.setBasicHealth(entity, shellBefore);
                        restored = captureGetHealthTrace(entity);
                    }
                }
            }
        } catch (Throwable t) {
            Trace.finish();
            EcaLogger.warn("[HealthNumericInverter] trace capture failed entity={} msg={}",
                    entity.getClass().getName(), t.toString());
            return false;
        } finally {
            ProbeTransformer.restoreAll(inst);
            IN_PROGRESS.set(false);
        }

        List<Trace.Entry> reads = mergeEntries(baseline.reads, perturbed.reads, restored.reads);
        boolean ok = solveByTraceDecoder(entity, target, baseline.reads, baseline.methods);
        if (!ok) ok = solveByNumericTrace(entity, target, reads, verbose);
        if (!ok && verbose) {
            dumpTrace(entity, targetNames, reads,
                    mergeWriteEntries(baseline.writes, perturbed.writes, restored.writes),
                    mergeMethodEntries(baseline.methods, perturbed.methods, restored.methods),
                    mergeExitEntries(baseline.exits, perturbed.exits, restored.exits));
        }
        return ok;
    }

    private static TraceSnapshot captureGetHealthTrace(LivingEntity entity) {
        Trace.begin();
        try {
            try {
                entity.getHealth();
            } catch (Throwable ignored) {
            }
            return new TraceSnapshot(Trace.reads(), Trace.writes(), Trace.methods(), Trace.exits());
        } finally {
            Trace.finish();
        }
    }

    private static float readBasicShellHealth(LivingEntity entity) {
        try {
            return entity.getEntityData().get(LivingEntity.DATA_HEALTH_ID);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Float.NaN;
        }
    }

    private static float shellProbeValue(float current) {
        if (current > 2.0f) return Math.max(0.0f, current - Math.max(1.0f, Math.abs(current) * 0.05f));
        return current + 1.0f;
    }

    @SafeVarargs
    private static <T> List<T> mergeLists(List<T>... lists) {
        List<T> out = new ArrayList<>();
        for (List<T> list : lists) {
            if (list != null) out.addAll(list);
        }
        return out;
    }

    @SafeVarargs
    private static List<Trace.Entry> mergeEntries(List<Trace.Entry>... lists) {
        return mergeLists(lists);
    }

    @SafeVarargs
    private static List<Trace.WriteEntry> mergeWriteEntries(List<Trace.WriteEntry>... lists) {
        return mergeLists(lists);
    }

    @SafeVarargs
    private static List<Trace.MethodEntry> mergeMethodEntries(List<Trace.MethodEntry>... lists) {
        return mergeLists(lists);
    }

    @SafeVarargs
    private static List<Trace.ExitEntry> mergeExitEntries(List<Trace.ExitEntry>... lists) {
        return mergeLists(lists);
    }

    private record TraceSnapshot(List<Trace.Entry> reads, List<Trace.WriteEntry> writes,
                                 List<Trace.MethodEntry> methods, List<Trace.ExitEntry> exits) {
        private static final TraceSnapshot EMPTY = new TraceSnapshot(List.of(), List.of(), List.of(), List.of());
    }

    private static Set<String> resolveTargetClasses(Class<?> entityClass) {
        Set<String> result = new HashSet<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
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
        result.removeIf(HealthNumericInverter::isSkipped);
        return result;
    }

    private static boolean collectReferences(ClassLoader loader, String internal, Set<String> out) {
        ClassLoader classLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();
        try (InputStream is = classLoader.getResourceAsStream(internal + ".class")) {
            if (is == null) return false;
            ClassReader reader = new ClassReader(is);
            reader.accept(new RefCollector(out), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class RefCollector extends ClassVisitor {
        private final Set<String> out;

        private RefCollector(Set<String> out) {
            super(Opcodes.ASM9);
            this.out = out;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                    addType(out, owner);
                    addDesc(out, descriptor);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    addType(out, owner);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    addType(out, type);
                }
            };
        }
    }

    private static void addType(Set<String> out, String internalOrArray) {
        if (internalOrArray == null) return;
        String type = internalOrArray;
        while (type.startsWith("[")) type = type.substring(1);
        if (type.startsWith("L") && type.endsWith(";")) type = type.substring(1, type.length() - 1);
        if (!type.isEmpty() && type.indexOf('/') >= 0) out.add(type);
    }

    private static void addDesc(Set<String> out, String desc) {
        if (desc == null || desc.isEmpty()) return;
        char first = desc.charAt(0);
        if (first != 'L' && first != '[') return;
        Type type = Type.getType(desc);
        if (type.getSort() == Type.ARRAY) type = type.getElementType();
        if (type.getSort() == Type.OBJECT) out.add(type.getInternalName());
    }

    private static boolean isSkipped(String internal) {
        for (String prefix : SKIP_PREFIXES) {
            if (internal.startsWith(prefix)) return true;
        }
        return false;
    }

    private static Class<?>[] resolveLoadedModifiable(Instrumentation inst, Set<String> internalNames) {
        Map<String, Class<?>> loaded = new HashMap<>();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            String internal = clazz.getName().replace('.', '/');
            if (internalNames.contains(internal)) loaded.put(internal, clazz);
        }
        return loaded.values().stream()
                .filter(inst::isModifiableClass)
                .toArray(Class<?>[]::new);
    }

    private static boolean solveByTraceDecoder(LivingEntity entity, float target,
                                               List<Trace.Entry> reads, List<Trace.MethodEntry> methods) {
        try {
            HealthDataflowAnalyzer.EvalContext ctx = new EvalCtx(entity);
            Float requiredInner = solveOuterWrapper(entity, target, ctx);
            if (requiredInner == null) return false;

            Decoder decoder = pickDecoder(reads, methods);
            if (decoder == null) return false;
            HealthDataflowAnalyzer.Expr tree = buildDecoderTree(decoder);
            if (tree == null) return false;
            HealthDataflowAnalyzer.Source sink = findCellSink(tree, decoder, ctx);
            if (sink == null) return false;
            Object solved = HealthDataflowAnalyzer.solveFor(tree, sink, requiredInner, ctx);
            if (!(solved instanceof Number number)) return false;

            Class<?> component = decoder.cellArray.getClass().getComponentType();
            if (component == long.class) Array.setLong(decoder.cellArray, decoder.cellIndex, number.longValue());
            else if (component == int.class) Array.setInt(decoder.cellArray, decoder.cellIndex, number.intValue());
            else Array.set(decoder.cellArray, decoder.cellIndex, number);
            return EcaSetHealthManager.verify(entity, target);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[HealthNumericInverter] trace decoder failed entity={} msg={}",
                    entity.getClass().getName(), t.toString());
            return false;
        }
    }

    private static Float solveOuterWrapper(LivingEntity entity, float target, HealthDataflowAnalyzer.EvalContext ctx) {
        Class<?> owner = findGetHealthOwner(entity.getClass());
        if (owner == null) return null;
        String name = hasMethod(owner, GET_HEALTH) ? GET_HEALTH : GET_HEALTH_ALT;
        HealthDataflowAnalyzer.Expr tree = HealthDataflowAnalyzer.analyzeSeeded(owner, name, GET_HEALTH_DESC,
                new HealthDataflowAnalyzer.Expr[]{new HealthDataflowAnalyzer.Reference(entity, ownerInternal(owner))});
        if (tree == null) return null;

        HealthDataflowAnalyzer.Call opaque = findOpaqueNumericCall(tree);
        if (opaque == null) return null;
        HoleSink hole = new HoleSink();
        HealthDataflowAnalyzer.Expr substituted = substitute(tree, opaque, hole);
        Object result = HealthDataflowAnalyzer.solveFor(substituted, hole, target, ctx);
        return result instanceof Number number ? number.floatValue() : null;
    }

    private static final class Decoder {
        private Trace.MethodEntry method;
        private Object receiver;
        private HealthDataflowAnalyzer.Expr[] seedArgs;
        private Object cellArray;
        private int cellIndex;
    }

    private static Decoder pickDecoder(List<Trace.Entry> reads, List<Trace.MethodEntry> methods) {
        for (int mi = methods.size() - 1; mi >= 0; mi--) {
            Trace.MethodEntry method = methods.get(mi);
            String[] ownerAndName = splitSite(method.site);
            if (ownerAndName == null) continue;
            Class<?> owner = loadClass(ownerAndName[0]);
            if (owner == null) continue;
            String desc = findMethodDesc(owner, ownerAndName[1]);
            if (desc == null || !Type.getReturnType(desc).equals(Type.FLOAT_TYPE)) continue;

            Trace.Entry cell = null;
            for (Trace.Entry read : reads) {
                if (!read.site.startsWith(method.site + " ")) continue;
                if (read.index < 0 || read.container == null) continue;
                String className = read.container.getClass().getName();
                if (className.equals("[J") || className.equals("[I")) cell = read;
            }
            if (cell == null) continue;

            Decoder decoder = new Decoder();
            decoder.method = method;
            decoder.receiver = method.receiver;
            decoder.seedArgs = seedArgsOf(method.args, desc);
            decoder.cellArray = cell.container;
            decoder.cellIndex = (int) cell.index;
            return decoder;
        }
        return null;
    }

    private static HealthDataflowAnalyzer.Expr buildDecoderTree(Decoder decoder) {
        String[] ownerAndName = splitSite(decoder.method.site);
        if (ownerAndName == null) return null;
        Class<?> owner = loadClass(ownerAndName[0]);
        if (owner == null) return null;
        String desc = findMethodDesc(owner, ownerAndName[1]);
        if (desc == null) return null;

        List<HealthDataflowAnalyzer.Expr> seeds = new ArrayList<>();
        if (decoder.receiver != null) {
            seeds.add(new HealthDataflowAnalyzer.Reference(decoder.receiver, ownerAndName[0]));
        }
        for (HealthDataflowAnalyzer.Expr arg : decoder.seedArgs) seeds.add(arg);
        return HealthDataflowAnalyzer.analyzeSeeded(owner, ownerAndName[1], desc,
                seeds.toArray(new HealthDataflowAnalyzer.Expr[0]));
    }

    private static HealthDataflowAnalyzer.Source findCellSink(HealthDataflowAnalyzer.Expr tree, Decoder decoder,
                                                              HealthDataflowAnalyzer.EvalContext ctx) {
        for (HealthDataflowAnalyzer.Source source : HealthDataflowAnalyzer.collectSources(tree)) {
            if (!(source instanceof HealthDataflowAnalyzer.ArrayElementSource arraySource)) continue;
            try {
                Object array = HealthDataflowAnalyzer.evaluate(arraySource.arrayExpr, ctx);
                Object index = HealthDataflowAnalyzer.evaluate(arraySource.indexExpr, ctx);
                if (array == decoder.cellArray && index instanceof Number number
                        && number.intValue() == decoder.cellIndex) {
                    return source;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static HealthDataflowAnalyzer.Expr[] seedArgsOf(Object[] args, String desc) {
        Type[] params = Type.getArgumentTypes(desc);
        HealthDataflowAnalyzer.Expr[] out = new HealthDataflowAnalyzer.Expr[args.length];
        for (int i = 0; i < args.length && i < params.length; i++) {
            Object arg = args[i];
            char jvmType = switch (params[i].getSort()) {
                case Type.LONG -> 'J';
                case Type.INT, Type.SHORT, Type.BYTE, Type.CHAR, Type.BOOLEAN -> 'I';
                case Type.FLOAT -> 'F';
                case Type.DOUBLE -> 'D';
                default -> 0;
            };
            if (jvmType != 0 && arg instanceof Number number) {
                out[i] = new HealthDataflowAnalyzer.Primitive(number, jvmType);
            } else {
                out[i] = arg == null
                        ? new HealthDataflowAnalyzer.Reference(null, "java/lang/Object")
                        : new HealthDataflowAnalyzer.Reference(arg, params[i].getInternalName());
            }
        }
        return out;
    }

    private static final class HoleSink extends HealthDataflowAnalyzer.Source {
        private HoleSink() {
            super(float.class, "HOLE");
        }

        @Override
        public Object read(LivingEntity entity) {
            return null;
        }

        @Override
        public boolean write(LivingEntity entity, Object value) {
            return false;
        }

        @Override
        protected String canonicalKey() {
            return "HOLE@" + System.identityHashCode(this);
        }
    }

    private static HealthDataflowAnalyzer.Call findOpaqueNumericCall(HealthDataflowAnalyzer.Expr expr) {
        if (expr instanceof HealthDataflowAnalyzer.Call call) {
            Type returnType = Type.getReturnType(call.desc());
            boolean numeric = returnType.equals(Type.FLOAT_TYPE) || returnType.equals(Type.INT_TYPE)
                    || returnType.equals(Type.LONG_TYPE) || returnType.equals(Type.DOUBLE_TYPE);
            boolean hasInverse = HealthDataflowAnalyzer.TABLE.lookupCall(call.owner(), call.name(), call.desc()) != null;
            if (numeric && !hasInverse && !containsAnySource(call)) return call;
            for (HealthDataflowAnalyzer.Expr arg : call.args()) {
                HealthDataflowAnalyzer.Call found = findOpaqueNumericCall(arg);
                if (found != null) return found;
            }
        } else if (expr instanceof HealthDataflowAnalyzer.Op op) {
            for (HealthDataflowAnalyzer.Expr arg : op.args()) {
                HealthDataflowAnalyzer.Call found = findOpaqueNumericCall(arg);
                if (found != null) return found;
            }
        } else if (expr instanceof HealthDataflowAnalyzer.Choice choice) {
            for (HealthDataflowAnalyzer.Expr alt : choice.alternatives()) {
                HealthDataflowAnalyzer.Call found = findOpaqueNumericCall(alt);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsAnySource(HealthDataflowAnalyzer.Expr expr) {
        if (expr instanceof HealthDataflowAnalyzer.Source) return true;
        if (expr instanceof HealthDataflowAnalyzer.Call call) {
            for (HealthDataflowAnalyzer.Expr arg : call.args()) {
                if (containsAnySource(arg)) return true;
            }
        }
        if (expr instanceof HealthDataflowAnalyzer.Op op) {
            for (HealthDataflowAnalyzer.Expr arg : op.args()) {
                if (containsAnySource(arg)) return true;
            }
        }
        if (expr instanceof HealthDataflowAnalyzer.Choice choice) {
            for (HealthDataflowAnalyzer.Expr alt : choice.alternatives()) {
                if (containsAnySource(alt)) return true;
            }
        }
        return false;
    }

    private static HealthDataflowAnalyzer.Expr substitute(HealthDataflowAnalyzer.Expr expr,
                                                         HealthDataflowAnalyzer.Expr target,
                                                         HealthDataflowAnalyzer.Expr replacement) {
        if (expr == target) return replacement;
        if (expr instanceof HealthDataflowAnalyzer.Op op) {
            List<HealthDataflowAnalyzer.Expr> args = new ArrayList<>(op.args().size());
            for (HealthDataflowAnalyzer.Expr arg : op.args()) args.add(substitute(arg, target, replacement));
            return new HealthDataflowAnalyzer.Op(op.opcode(), args);
        }
        if (expr instanceof HealthDataflowAnalyzer.Call call) {
            List<HealthDataflowAnalyzer.Expr> args = new ArrayList<>(call.args().size());
            for (HealthDataflowAnalyzer.Expr arg : call.args()) args.add(substitute(arg, target, replacement));
            return new HealthDataflowAnalyzer.Call(call.owner(), call.name(), call.desc(), args);
        }
        if (expr instanceof HealthDataflowAnalyzer.Choice choice) {
            List<HealthDataflowAnalyzer.Expr> args = new ArrayList<>(choice.alternatives().size());
            for (HealthDataflowAnalyzer.Expr arg : choice.alternatives()) args.add(substitute(arg, target, replacement));
            return new HealthDataflowAnalyzer.Choice(args);
        }
        return expr;
    }

    private record EvalCtx(LivingEntity entity) implements HealthDataflowAnalyzer.EvalContext {
        @Override
        public Object eval(HealthDataflowAnalyzer.Expr expr) {
            return HealthDataflowAnalyzer.evaluate(expr, this);
        }
    }

    private static Class<?> findGetHealthOwner(Class<?> start) {
        for (Class<?> type = start; type != null && type != Object.class; type = type.getSuperclass()) {
            if (hasMethod(type, GET_HEALTH) || hasMethod(type, GET_HEALTH_ALT)) return type;
        }
        return null;
    }

    private static boolean hasMethod(Class<?> type, String name) {
        try {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0
                        && method.getReturnType() == float.class) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String ownerInternal(Class<?> type) {
        String name = type.getName().replace('.', '/');
        int hidden = name.indexOf("/0x");
        if (hidden < 0) return name;
        String stripped = name.substring(0, hidden);
        if (stripped.indexOf('/') < 0) {
            Class<?> parent = type.getSuperclass();
            if (parent != null) {
                String parentInternal = parent.getName().replace('.', '/');
                int slash = parentInternal.lastIndexOf('/');
                if (slash > 0) stripped = parentInternal.substring(0, slash + 1) + stripped;
            }
        }
        return stripped;
    }

    private static String[] splitSite(String site) {
        int hash = site.indexOf('#');
        if (hash < 0) return null;
        return new String[]{site.substring(0, hash), site.substring(hash + 1)};
    }

    private static Class<?> loadClass(String internal) {
        try {
            return Class.forName(internal.replace('/', '.'), false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String findMethodDesc(Class<?> owner, String name) {
        try {
            for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
                if (method.getName().equals(name)) return Type.getMethodDescriptor(method);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean solveByNumericTrace(LivingEntity entity, float target, List<Trace.Entry> reads, boolean verbose) {
        if (entity == null || reads == null || reads.isEmpty()) return false;
        List<Cell> cells = null;
        boolean success = false;
        try {
            float initial = EcaSetHealthManager.safeGetHealth(entity);
            if (!Float.isFinite(initial)) return false;
            if (EcaSetHealthManager.verify(entity, target)) return true;

            cells = collectCells(reads);
            if (verbose) {
                EcaLogger.warn("[HealthNumericInverter] h0={} target={} candidateLocations={}",
                        initial, target, cells.size());
            }
            if (cells.isEmpty()) return false;

            List<Cell> active = new ArrayList<>();
            for (Cell cell : cells) {
                cell.slope = localSlope(entity, cell);
                if (cell.slope != 0.0 && Double.isFinite(cell.slope)) active.add(cell);
            }
            if (verbose) dumpActive(active, cells.size());
            if (active.isEmpty()) return false;

            double epsilon = Math.max(1.0e-3, Math.abs(target) * 1.0e-5);
            for (int pass = 0; pass < 12; pass++) {
                float health = EcaSetHealthManager.safeGetHealth(entity);
                if (!Float.isFinite(health) || Math.abs(target - health) <= epsilon) break;

                for (Cell cell : active) cell.slope = localSlope(entity, cell);
                active.sort(Comparator.comparingDouble((Cell c) -> Math.abs(c.slope)).reversed());

                boolean improved = false;
                for (Cell cell : active) {
                    health = EcaSetHealthManager.safeGetHealth(entity);
                    if (!Float.isFinite(health)) break;
                    double residual = target - health;
                    if (Math.abs(residual) <= epsilon || cell.slope == 0.0) break;
                    double current = readCell(cell);
                    if (!Double.isFinite(current)) continue;

                    double predicted = residual / cell.slope;
                    double bestError = Math.abs(residual);
                    boolean accepted = false;
                    double fraction = 1.0;
                    for (int step = 0; step < 14; step++) {
                        writeCell(cell, current + predicted * fraction);
                        float next = EcaSetHealthManager.safeGetHealth(entity);
                        if (Float.isFinite(next) && Math.abs(target - next) < bestError) {
                            accepted = true;
                            improved = true;
                            break;
                        }
                        fraction *= 0.5;
                    }
                    if (!accepted) writeCell(cell, current);
                }
                if (!improved) break;
            }

            success = EcaSetHealthManager.verify(entity, target);
            if (verbose) {
                EcaLogger.warn("[HealthNumericInverter] result={} finalH={} target={}",
                        success, EcaSetHealthManager.safeGetHealth(entity), target);
            }
            return success;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.warn("[HealthNumericInverter] exception: {}", t.toString());
            return false;
        } finally {
            if (!success && cells != null) {
                for (Cell cell : cells) writeCell(cell, cell.base);
            }
        }
    }

    private static final class Cell {
        private final PhysicalLocation location;
        private final Class<?> type;
        private final double base;
        private double slope;

        private Cell(PhysicalLocation location, Class<?> type, double base) {
            this.location = location;
            this.type = type;
            this.base = base;
        }
    }

    private static List<Cell> collectCells(List<Trace.Entry> reads) {
        List<Cell> cells = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Trace.Entry read : reads) {
            if (cells.size() >= MAX_NUMERIC_CELLS) break;
            if (read.value instanceof Number) {
                PhysicalLocation location = physicalLocation(read.site, read.container, read.index);
                addCell(cells, seen, location, read.value.getClass());
            }
            addArrayNeighborhood(cells, seen, read.container, read.index);
            addNumericFields(cells, seen, read.container);
            addNumericFields(cells, seen, read.value);
        }
        if (cells.size() < MAX_NUMERIC_CELLS) {
            for (Trace.Entry read : reads) {
                if (cells.size() >= MAX_NUMERIC_CELLS) break;
                addArrayNeighborhood(cells, seen, read.container, read.index);
                addNumericFields(cells, seen, read.container);
                addNumericFields(cells, seen, read.value);
            }
        }
        return cells;
    }

    private static void addCell(List<Cell> cells, Set<String> seen, PhysicalLocation location, Class<?> runtimeType) {
        if (cells.size() >= MAX_NUMERIC_CELLS || location == null) return;
        String key = location.describe();
        if (seen.contains(key)) return;
        Object current = location.read();
        if (!(current instanceof Number number)) return;
        Class<?> type = numericType(location.valueType(), runtimeType != null ? runtimeType : current.getClass());
        if (type != null && seen.add(key)) cells.add(new Cell(location, type, number.doubleValue()));
    }

    private static void addArrayNeighborhood(List<Cell> cells, Set<String> seen, Object array, long index) {
        if (cells.size() >= MAX_NUMERIC_CELLS || array == null || !array.getClass().isArray()) return;
        Class<?> component = array.getClass().getComponentType();
        if (!isNumericType(component)) return;
        int length = Array.getLength(array);
        if (length <= 0) return;
        int center = index >= 0 && index < length ? (int) index : 0;
        int start = Math.max(0, center - 2);
        int end = Math.min(length - 1, center + 2);
        for (int i = start; i <= end && cells.size() < MAX_NUMERIC_CELLS; i++) {
            addCell(cells, seen, arrayElement(array, i), component);
        }
    }

    private static void addNumericFields(List<Cell> cells, Set<String> seen, Object owner) {
        if (cells.size() >= MAX_NUMERIC_CELLS || owner == null) return;
        if (owner instanceof LivingEntity || owner instanceof String || owner instanceof Number || owner instanceof Boolean) return;
        Class<?> type = owner.getClass();
        if (type.isArray() || type.isEnum() || isSkipped(type.getName().replace('.', '/'))) return;
        int added = 0;
        for (Class<?> current = type; current != null && current != Object.class && added < 16; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (cells.size() >= MAX_NUMERIC_CELLS || added >= 16) return;
                if (Modifier.isStatic(field.getModifiers()) || !isNumericType(field.getType())) continue;
                PhysicalLocation location = field(owner, field);
                addCell(cells, seen, location, field.getType());
                added++;
            }
        }
    }

    private static Class<?> numericType(Class<?> declared, Class<?> runtime) {
        if (isNumericType(declared)) return declared;
        return isNumericType(runtime) ? runtime : null;
    }

    private static double localSlope(LivingEntity entity, Cell cell) {
        double current = readCell(cell);
        if (!Double.isFinite(current)) return 0.0;
        float initial = EcaSetHealthManager.safeGetHealth(entity);
        if (!Float.isFinite(initial)) return 0.0;
        for (float step : new float[]{1.0f, 16.0f, 256.0f, 4096.0f}) {
            writeCell(cell, current + step);
            float health = EcaSetHealthManager.safeGetHealth(entity);
            writeCell(cell, current);
            if (Float.isFinite(health) && Math.abs(health - initial) > 1.0e-3f) {
                return (health - initial) / step;
            }
        }
        return 0.0;
    }

    private static double readCell(Cell cell) {
        try {
            Object value = cell.location.read();
            return value instanceof Number number ? number.doubleValue() : Double.NaN;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Double.NaN;
        }
    }

    private static void writeCell(Cell cell, double value) {
        try {
            cell.location.write(box(cell.type, value));
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
    }

    private static Object box(Class<?> type, double value) {
        if (type == int.class || type == Integer.class) return (int) Math.round(value);
        if (type == long.class || type == Long.class) return Math.round(value);
        if (type == short.class || type == Short.class) return (short) Math.round(value);
        if (type == byte.class || type == Byte.class) return (byte) Math.round(value);
        if (type == float.class || type == Float.class) return (float) value;
        if (type == double.class || type == Double.class) return value;
        return value;
    }

    private static boolean isNumericType(Class<?> type) {
        return type == int.class || type == long.class || type == short.class || type == byte.class
                || type == float.class || type == double.class || type == Integer.class || type == Long.class
                || type == Short.class || type == Byte.class || type == Float.class || type == Double.class;
    }

    private static void dumpActive(List<Cell> active, int total) {
        active.sort(Comparator.comparingDouble((Cell c) -> Math.abs(c.slope)).reversed());
        EcaLogger.warn("[HealthNumericInverter] responsiveLocations={}/{}", active.size(), total);
        for (int i = 0; i < Math.min(8, active.size()); i++) {
            Cell cell = active.get(i);
            EcaLogger.warn("[HealthNumericInverter]   active#{} {} slope={}",
                    i, cell.location.describe(), cell.slope);
        }
    }

    private interface PhysicalLocation {
        Object read();

        boolean write(Object value);

        Class<?> valueType();

        String describe();
    }

    private static PhysicalLocation physicalLocation(String site, Object container, long index) {
        if (index >= 0) return arrayElement(container, (int) index);
        Field field = resolveField(site, container);
        if (field == null) return null;
        return Modifier.isStatic(field.getModifiers()) ? staticField(field) : field(container, field);
    }

    private static PhysicalLocation field(Object owner, Field field) {
        if (field == null || Modifier.isStatic(field.getModifiers())) return null;
        try {
            field.setAccessible(true);
            return new FieldLocation(owner, field);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static PhysicalLocation staticField(Field field) {
        if (field == null || !Modifier.isStatic(field.getModifiers())) return null;
        try {
            field.setAccessible(true);
            return new StaticFieldLocation(field);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static PhysicalLocation arrayElement(Object array, int index) {
        if (array == null || !array.getClass().isArray()) return null;
        if (index < 0 || index >= Array.getLength(array)) return null;
        return new ArrayElementLocation(array, index);
    }

    private static Field resolveField(String site, Object container) {
        FieldRef ref = parseFieldRef(site);
        if (ref == null) return null;
        try {
            Class<?> owner = Class.forName(ref.owner.replace('/', '.'), false,
                    Thread.currentThread().getContextClassLoader());
            Field field = findFieldInHierarchy(owner, ref.name);
            if (field == null || !Modifier.isStatic(field.getModifiers()) && container == null) return null;
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static FieldRef parseFieldRef(String site) {
        if (site == null) return null;
        int space = site.lastIndexOf(' ');
        int colon = site.lastIndexOf(':');
        int dot = colon < 0 ? site.lastIndexOf('.') : site.lastIndexOf('.', colon);
        if (space < 0 || dot <= space) return null;
        String owner = site.substring(space + 1, dot);
        String name = site.substring(dot + 1, colon < 0 ? site.length() : colon);
        return owner.isEmpty() || name.isEmpty() ? null : new FieldRef(owner, name);
    }

    private record FieldRef(String owner, String name) {}

    private static Field findFieldInHierarchy(Class<?> owner, String name) {
        for (Class<?> current = owner; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static final class FieldLocation implements PhysicalLocation {
        private final Object owner;
        private final Field field;

        private FieldLocation(Object owner, Field field) {
            this.owner = owner;
            this.field = field;
        }

        @Override
        public Object read() {
            try {
                return field.get(owner);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return null;
            }
        }

        @Override
        public boolean write(Object value) {
            try {
                field.set(owner, coerceForType(value, field.getType()));
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return false;
            }
        }

        @Override
        public Class<?> valueType() {
            return field.getType();
        }

        @Override
        public String describe() {
            return owner.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(owner))
                    + "#" + field.getName();
        }
    }

    private static final class StaticFieldLocation implements PhysicalLocation {
        private final Field field;

        private StaticFieldLocation(Field field) {
            this.field = field;
        }

        @Override
        public Object read() {
            try {
                return field.get(null);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return null;
            }
        }

        @Override
        public boolean write(Object value) {
            try {
                field.set(null, coerceForType(value, field.getType()));
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return false;
            }
        }

        @Override
        public Class<?> valueType() {
            return field.getType();
        }

        @Override
        public String describe() {
            return field.getDeclaringClass().getName() + "#" + field.getName();
        }
    }

    private static final class ArrayElementLocation implements PhysicalLocation {
        private final Object array;
        private final int index;
        private final Class<?> valueType;

        private ArrayElementLocation(Object array, int index) {
            this.array = array;
            this.index = index;
            this.valueType = array.getClass().getComponentType();
        }

        @Override
        public Object read() {
            try {
                return Array.get(array, index);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return null;
            }
        }

        @Override
        public boolean write(Object value) {
            try {
                Array.set(array, index, coerceForType(value, valueType));
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return false;
            }
        }

        @Override
        public Class<?> valueType() {
            return valueType;
        }

        @Override
        public String describe() {
            return array.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(array))
                    + "[" + index + "]";
        }
    }

    private static Object coerceForType(Object value, Class<?> type) {
        if (!(value instanceof Number number)) return value;
        if (type == float.class || type == Float.class) return number.floatValue();
        if (type == double.class || type == Double.class) return number.doubleValue();
        if (type == int.class || type == Integer.class) return number.intValue();
        if (type == long.class || type == Long.class) return number.longValue();
        if (type == short.class || type == Short.class) return number.shortValue();
        if (type == byte.class || type == Byte.class) return number.byteValue();
        return value;
    }

    public static final class Trace {
        private Trace() {}

        public static final class Entry {
            public final String site;
            public final Object container;
            public final long index;
            public final Object value;

            private Entry(String site, Object container, long index, Object value) {
                this.site = site;
                this.container = container;
                this.index = index;
                this.value = value;
            }
        }

        public static final class MethodEntry {
            public final String site;
            public final Object receiver;
            public final Object[] args;

            private MethodEntry(String site, Object receiver, Object[] args) {
                this.site = site;
                this.receiver = receiver;
                this.args = args;
            }
        }

        public static final class WriteEntry {
            public final String site;
            public final Object container;
            public final long index;
            public final Object value;

            private WriteEntry(String site, Object container, long index, Object value) {
                this.site = site;
                this.container = container;
                this.index = index;
                this.value = value;
            }
        }

        public static final class ExitEntry {
            public final String site;
            public final Object value;

            private ExitEntry(String site, Object value) {
                this.site = site;
                this.value = value;
            }
        }

        private static final ThreadLocal<List<Entry>> READS = new ThreadLocal<>();
        private static final ThreadLocal<List<WriteEntry>> WRITES = new ThreadLocal<>();
        private static final ThreadLocal<List<MethodEntry>> METHODS = new ThreadLocal<>();
        private static final ThreadLocal<List<ExitEntry>> EXITS = new ThreadLocal<>();

        public static void begin() {
            READS.set(new ArrayList<>());
            WRITES.set(new ArrayList<>());
            METHODS.set(new ArrayList<>());
            EXITS.set(new ArrayList<>());
        }

        public static List<Entry> reads() {
            List<Entry> reads = READS.get();
            return reads == null ? new ArrayList<>() : reads;
        }

        public static List<WriteEntry> writes() {
            List<WriteEntry> writes = WRITES.get();
            return writes == null ? new ArrayList<>() : writes;
        }

        public static List<MethodEntry> methods() {
            List<MethodEntry> methods = METHODS.get();
            return methods == null ? new ArrayList<>() : methods;
        }

        public static List<ExitEntry> exits() {
            List<ExitEntry> exits = EXITS.get();
            return exits == null ? new ArrayList<>() : exits;
        }

        public static void finish() {
            READS.remove();
            WRITES.remove();
            METHODS.remove();
            EXITS.remove();
        }

        public static void enter(String site, Object receiver, Object[] args) {
            List<MethodEntry> methods = METHODS.get();
            if (methods != null) methods.add(new MethodEntry(site, receiver, args));
        }

        public static void exit(Object value, String site) {
            List<ExitEntry> exits = EXITS.get();
            if (exits != null) exits.add(new ExitEntry(site, value));
        }

        private static void add(String site, Object container, long index, Object value) {
            List<Entry> reads = READS.get();
            if (reads != null) reads.add(new Entry(site, container, index, value));
        }

        private static void addWrite(String site, Object container, long index, Object value) {
            List<WriteEntry> writes = WRITES.get();
            if (writes != null) writes.add(new WriteEntry(site, container, index, value));
        }

        public static void fieldO(Object c, Object v, String s) { add(s, c, -1, v); }
        public static void fieldI(Object c, int v, String s) { add(s, c, -1, v); }
        public static void fieldJ(Object c, long v, String s) { add(s, c, -1, v); }
        public static void fieldF(Object c, float v, String s) { add(s, c, -1, v); }
        public static void fieldD(Object c, double v, String s) { add(s, c, -1, v); }
        public static void staticO(Object v, String s) { add(s, null, -1, v); }
        public static void staticI(int v, String s) { add(s, null, -1, v); }
        public static void staticJ(long v, String s) { add(s, null, -1, v); }
        public static void staticF(float v, String s) { add(s, null, -1, v); }
        public static void staticD(double v, String s) { add(s, null, -1, v); }
        public static void arrO(Object a, int i, Object v, String s) { add(s, a, i, v); }
        public static void arrI(Object a, int i, int v, String s) { add(s, a, i, v); }
        public static void arrJ(Object a, int i, long v, String s) { add(s, a, i, v); }
        public static void arrF(Object a, int i, float v, String s) { add(s, a, i, v); }
        public static void arrD(Object a, int i, double v, String s) { add(s, a, i, v); }
        public static void writeFieldO(Object c, Object v, String s) { addWrite(s, c, -1, v); }
        public static void writeFieldI(Object c, int v, String s) { addWrite(s, c, -1, v); }
        public static void writeFieldJ(Object c, long v, String s) { addWrite(s, c, -1, v); }
        public static void writeFieldF(Object c, float v, String s) { addWrite(s, c, -1, v); }
        public static void writeFieldD(Object c, double v, String s) { addWrite(s, c, -1, v); }
        public static void writeStaticO(Object v, String s) { addWrite(s, null, -1, v); }
        public static void writeStaticI(int v, String s) { addWrite(s, null, -1, v); }
        public static void writeStaticJ(long v, String s) { addWrite(s, null, -1, v); }
        public static void writeStaticF(float v, String s) { addWrite(s, null, -1, v); }
        public static void writeStaticD(double v, String s) { addWrite(s, null, -1, v); }
        public static void writeArrO(Object a, int i, Object v, String s) { addWrite(s, a, i, v); }
        public static void writeArrI(Object a, int i, int v, String s) { addWrite(s, a, i, v); }
        public static void writeArrJ(Object a, int i, long v, String s) { addWrite(s, a, i, v); }
        public static void writeArrF(Object a, int i, float v, String s) { addWrite(s, a, i, v); }
        public static void writeArrD(Object a, int i, double v, String s) { addWrite(s, a, i, v); }
    }

    private static final class ProbeTransformer implements ClassFileTransformer {
        private static final String TRACE = "net/eca/util/health/HealthNumericInverter$Trace";
        private static final String TRACE_DOT = "net.eca.util.health.HealthNumericInverter$Trace";
        private static volatile boolean registered;
        private static final Set<String> TARGETS = ConcurrentHashMap.newKeySet();
        private static final Set<String> INSTRUMENTED = ConcurrentHashMap.newKeySet();
        private static final Set<String> RESTORING = ConcurrentHashMap.newKeySet();
        private static final Map<String, byte[]> ORIGINAL_BYTES = new ConcurrentHashMap<>();

        private static volatile boolean jvmTiRegistered;

        private static void ensureRegistered() {
            if (registered) return;
            Instrumentation inst = EcaAgent.getInstrumentation();
            if (inst == null) {
                AgentLogWriter.warn("[HealthNumericInverter] No Instrumentation, dynamic analysis unavailable");
                return;
            }
            inst.addTransformer(new ProbeTransformer(), true);
            registered = true;
            AgentLogWriter.info("[HealthNumericInverter] Registered numeric trace transformer");
        }

        /* 确保 ProbeTransformer 的变换逻辑已在 JVM TI 回调注册表中 */
        private static void ensureJvmTiRegistered() {
            if (jvmTiRegistered) return;
            jvmTiRegistered = true;
            net.eca.coremod.JvmTiChannel.addTransformFunction(ProbeTransformer::transformStatic);
        }

        /* 供 JVM TI 回调调用的静态版本——逻辑与 ProbeTransformer.transform 一致 */
        static byte[] transformStatic(String className, byte[] classfileBuffer) {
            if (RESTORING.contains(className)) {
                byte[] original = ORIGINAL_BYTES.get(className);
                return original != null ? original : classfileBuffer;
            }
            if (!TARGETS.contains(className)) return null;
            // JVM TI 回调上下文无 ClassLoader，跳过 canLoadTrace 检查
            try {
                ORIGINAL_BYTES.putIfAbsent(className, classfileBuffer.clone());
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                reader.accept(new ProbeClassVisitor(writer, className), ClassReader.EXPAND_FRAMES);
                INSTRUMENTED.add(className);
                AgentLogWriter.info("[HealthNumericInverter] JVMTI Instrumented: " + className);
                return writer.toByteArray();
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return null;
            }
        }

        private static void setTargets(Set<String> internalNames) {
            TARGETS.clear();
            TARGETS.addAll(internalNames);
        }

        private static void restoreAll(Instrumentation inst) {
            TARGETS.clear();
            if (INSTRUMENTED.isEmpty()) {
                ORIGINAL_BYTES.clear();
                RESTORING.clear();
                INSTRUMENTED.clear();
                return;
            }
            Set<String> pending = new HashSet<>(INSTRUMENTED);
            RESTORING.addAll(pending);
            // JVM TI 原生通道：恢复已插桩的类
            if (net.eca.coremod.JvmTiChannel.isActive()) {
                for (Class<?> clazz : inst.getAllLoadedClasses()) {
                    String internal = clazz.getName().replace('.', '/');
                    if (!pending.contains(internal)) continue;
                    net.eca.coremod.JvmTiChannel.retransformClasses(clazz);
                    INSTRUMENTED.remove(internal);
                    ORIGINAL_BYTES.remove(internal);
                }
            }
            // Instrumentation 常规通道
            if (inst != null) {
                for (Class<?> clazz : inst.getAllLoadedClasses()) {
                    String internal = clazz.getName().replace('.', '/');
                    if (!pending.contains(internal)) continue;
                    try {
                        if (inst.isModifiableClass(clazz)) inst.retransformClasses(clazz);
                        INSTRUMENTED.remove(internal);
                        ORIGINAL_BYTES.remove(internal);
                    } catch (Throwable t) {
                        AgentLogWriter.error("[HealthNumericInverter] Failed to restore: " + internal, t);
                    }
                }
            }
            RESTORING.removeAll(pending);
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className == null) return null;
            if (RESTORING.contains(className)) {
                byte[] original = ORIGINAL_BYTES.get(className);
                return original != null ? original : classfileBuffer;
            }
            if (!TARGETS.contains(className)) return null;
            if (!canLoadTrace(loader)) {
                AgentLogWriter.info("[HealthNumericInverter] skipped instrumentation: " + className
                        + " cannot load " + TRACE_DOT);
                return null;
            }
            try {
                ORIGINAL_BYTES.putIfAbsent(className, classfileBuffer.clone());
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                reader.accept(new ProbeClassVisitor(writer, className), ClassReader.EXPAND_FRAMES);
                INSTRUMENTED.add(className);
                AgentLogWriter.info("[HealthNumericInverter] Instrumented: " + className);
                return writer.toByteArray();
            } catch (Throwable t) {
                AgentLogWriter.error("[HealthNumericInverter] Failed to instrument: " + className, t);
                return null;
            }
        }

        private static boolean canLoadTrace(ClassLoader loader) {
            try {
                ClassLoader targetLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();
                Class.forName(TRACE_DOT, false, targetLoader);
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return false;
            }
        }

        private static final class ProbeClassVisitor extends ClassVisitor {
            private final String owner;

            private ProbeClassVisitor(ClassWriter writer, String owner) {
                super(Opcodes.ASM9, writer);
                this.owner = owner;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (visitor == null || name.equals("<clinit>") || name.equals("<init>")) return visitor;
                return new ProbeMethodVisitor(visitor, owner + "#" + name, access, descriptor);
            }
        }

        private static final class ProbeMethodVisitor extends LocalVariablesSorter {
            private final String where;
            private final int access;
            private final String methodDesc;

            private ProbeMethodVisitor(MethodVisitor visitor, String where, int access, String methodDesc) {
                super(Opcodes.ASM9, access, methodDesc, visitor);
                this.where = where;
                this.access = access;
                this.methodDesc = methodDesc;
            }

            @Override
            public void visitCode() {
                super.visitCode();
                Type[] params = Type.getArgumentTypes(methodDesc);
                boolean instance = (access & Opcodes.ACC_STATIC) == 0;
                super.visitLdcInsn(where);
                if (instance) super.visitVarInsn(Opcodes.ALOAD, 0);
                else super.visitInsn(Opcodes.ACONST_NULL);
                super.visitLdcInsn(params.length);
                super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
                int slot = instance ? 1 : 0;
                for (int i = 0; i < params.length; i++) {
                    super.visitInsn(Opcodes.DUP);
                    super.visitLdcInsn(i);
                    loadBoxed(params[i], slot);
                    super.visitInsn(Opcodes.AASTORE);
                    slot += params[i].getSize();
                }
                super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "enter",
                        "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)V", false);
            }

            private void loadBoxed(Type type, int slot) {
                switch (type.getSort()) {
                    case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                        super.visitVarInsn(Opcodes.ILOAD, slot);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                                "(I)Ljava/lang/Integer;", false);
                    }
                    case Type.LONG -> {
                        super.visitVarInsn(Opcodes.LLOAD, slot);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                                "(J)Ljava/lang/Long;", false);
                    }
                    case Type.FLOAT -> {
                        super.visitVarInsn(Opcodes.FLOAD, slot);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                                "(F)Ljava/lang/Float;", false);
                    }
                    case Type.DOUBLE -> {
                        super.visitVarInsn(Opcodes.DLOAD, slot);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                                "(D)Ljava/lang/Double;", false);
                    }
                    default -> super.visitVarInsn(Opcodes.ALOAD, slot);
                }
            }

            @Override
            public void visitFieldInsn(int opcode, String fieldOwner, String fieldName, String fieldDesc) {
                char valueType = valType(fieldDesc);
                boolean categoryTwo = valueType == 'J' || valueType == 'D';
                String site = where + " " + fieldOwner + "." + fieldName + ":" + fieldDesc;
                if (opcode == Opcodes.GETFIELD) {
                    super.visitInsn(Opcodes.DUP);
                    super.visitFieldInsn(opcode, fieldOwner, fieldName, fieldDesc);
                    super.visitInsn(categoryTwo ? Opcodes.DUP2_X1 : Opcodes.DUP_X1);
                    super.visitLdcInsn(site);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "field" + valueType, fieldDesc(valueType), false);
                    return;
                }
                if (opcode == Opcodes.GETSTATIC) {
                    super.visitFieldInsn(opcode, fieldOwner, fieldName, fieldDesc);
                    super.visitInsn(categoryTwo ? Opcodes.DUP2 : Opcodes.DUP);
                    super.visitLdcInsn(site);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "static" + valueType, staticDesc(valueType), false);
                    return;
                }
                Type type = Type.getType(fieldDesc);
                int valueLocal = newLocal(type);
                super.visitVarInsn(type.getOpcode(Opcodes.ISTORE), valueLocal);
                if (opcode == Opcodes.PUTFIELD) {
                    int ownerLocal = newLocal(Type.getType(Object.class));
                    super.visitVarInsn(Opcodes.ASTORE, ownerLocal);
                    super.visitVarInsn(Opcodes.ALOAD, ownerLocal);
                    super.visitVarInsn(type.getOpcode(Opcodes.ILOAD), valueLocal);
                    super.visitLdcInsn(site);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "writeField" + valueType, fieldDesc(valueType), false);
                    super.visitVarInsn(Opcodes.ALOAD, ownerLocal);
                } else {
                    super.visitVarInsn(type.getOpcode(Opcodes.ILOAD), valueLocal);
                    super.visitLdcInsn(site);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "writeStatic" + valueType, staticDesc(valueType), false);
                }
                super.visitVarInsn(type.getOpcode(Opcodes.ILOAD), valueLocal);
                super.visitFieldInsn(opcode, fieldOwner, fieldName, fieldDesc);
            }

            @Override
            public void visitInsn(int opcode) {
                if (isReturn(opcode)) {
                    traceReturn(opcode);
                    super.visitInsn(opcode);
                    return;
                }
                char valueType;
                switch (opcode) {
                    case Opcodes.IALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> valueType = 'I';
                    case Opcodes.FALOAD -> valueType = 'F';
                    case Opcodes.AALOAD -> valueType = 'O';
                    case Opcodes.LALOAD -> valueType = 'J';
                    case Opcodes.DALOAD -> valueType = 'D';
                    default -> {
                        if (isArrayStore(opcode)) {
                            traceArrayStore(opcode);
                            return;
                        }
                        super.visitInsn(opcode);
                        return;
                    }
                }
                boolean categoryTwo = valueType == 'J' || valueType == 'D';
                super.visitInsn(Opcodes.DUP2);
                super.visitInsn(opcode);
                super.visitInsn(categoryTwo ? Opcodes.DUP2_X2 : Opcodes.DUP_X2);
                super.visitLdcInsn(where + " []");
                super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "arr" + valueType, arrDesc(valueType), false);
            }

            private void traceArrayStore(int opcode) {
                Type valueType = arrayStoreType(opcode);
                char traceType = valType(valueType.getDescriptor());
                int valueLocal = newLocal(valueType);
                int indexLocal = newLocal(Type.INT_TYPE);
                int arrayLocal = newLocal(Type.getType(Object.class));
                super.visitVarInsn(valueType.getOpcode(Opcodes.ISTORE), valueLocal);
                super.visitVarInsn(Opcodes.ISTORE, indexLocal);
                super.visitVarInsn(Opcodes.ASTORE, arrayLocal);
                super.visitVarInsn(Opcodes.ALOAD, arrayLocal);
                super.visitVarInsn(Opcodes.ILOAD, indexLocal);
                super.visitVarInsn(valueType.getOpcode(Opcodes.ILOAD), valueLocal);
                super.visitLdcInsn(where + " []");
                super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "writeArr" + traceType, arrDesc(traceType), false);
                super.visitVarInsn(Opcodes.ALOAD, arrayLocal);
                super.visitVarInsn(Opcodes.ILOAD, indexLocal);
                super.visitVarInsn(valueType.getOpcode(Opcodes.ILOAD), valueLocal);
                super.visitInsn(opcode);
            }

            private static boolean isArrayStore(int opcode) {
                return opcode == Opcodes.IASTORE || opcode == Opcodes.LASTORE || opcode == Opcodes.FASTORE
                        || opcode == Opcodes.DASTORE || opcode == Opcodes.AASTORE || opcode == Opcodes.BASTORE
                        || opcode == Opcodes.CASTORE || opcode == Opcodes.SASTORE;
            }

            private static Type arrayStoreType(int opcode) {
                return switch (opcode) {
                    case Opcodes.LASTORE -> Type.LONG_TYPE;
                    case Opcodes.FASTORE -> Type.FLOAT_TYPE;
                    case Opcodes.DASTORE -> Type.DOUBLE_TYPE;
                    case Opcodes.AASTORE -> Type.getType(Object.class);
                    default -> Type.INT_TYPE;
                };
            }

            private void traceReturn(int opcode) {
                if (opcode == Opcodes.RETURN) {
                    super.visitInsn(Opcodes.ACONST_NULL);
                } else {
                    Type type = returnType(opcode);
                    super.visitInsn(type.getSize() == 2 ? Opcodes.DUP2 : Opcodes.DUP);
                    boxTop(type);
                }
                super.visitLdcInsn(where);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "exit",
                        "(Ljava/lang/Object;Ljava/lang/String;)V", false);
            }

            private void boxTop(Type type) {
                switch (type.getSort()) {
                    case Type.INT -> super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                            "(I)Ljava/lang/Integer;", false);
                    case Type.LONG -> super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                            "(J)Ljava/lang/Long;", false);
                    case Type.FLOAT -> super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                            "(F)Ljava/lang/Float;", false);
                    case Type.DOUBLE -> super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                            "(D)Ljava/lang/Double;", false);
                    default -> {
                    }
                }
            }

            private static boolean isReturn(int opcode) {
                return opcode == Opcodes.RETURN || opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
                        || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN;
            }

            private static Type returnType(int opcode) {
                return switch (opcode) {
                    case Opcodes.IRETURN -> Type.INT_TYPE;
                    case Opcodes.LRETURN -> Type.LONG_TYPE;
                    case Opcodes.FRETURN -> Type.FLOAT_TYPE;
                    case Opcodes.DRETURN -> Type.DOUBLE_TYPE;
                    default -> Type.getType(Object.class);
                };
            }
        }

        private static char valType(String desc) {
            char c = desc.charAt(0);
            if (c == 'L' || c == '[') return 'O';
            if (c == 'J') return 'J';
            if (c == 'F') return 'F';
            if (c == 'D') return 'D';
            return 'I';
        }

        private static String fieldDesc(char valueType) {
            return switch (valueType) {
                case 'O' -> "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V";
                case 'J' -> "(Ljava/lang/Object;JLjava/lang/String;)V";
                case 'F' -> "(Ljava/lang/Object;FLjava/lang/String;)V";
                case 'D' -> "(Ljava/lang/Object;DLjava/lang/String;)V";
                default -> "(Ljava/lang/Object;ILjava/lang/String;)V";
            };
        }

        private static String staticDesc(char valueType) {
            return switch (valueType) {
                case 'O' -> "(Ljava/lang/Object;Ljava/lang/String;)V";
                case 'J' -> "(JLjava/lang/String;)V";
                case 'F' -> "(FLjava/lang/String;)V";
                case 'D' -> "(DLjava/lang/String;)V";
                default -> "(ILjava/lang/String;)V";
            };
        }

        private static String arrDesc(char valueType) {
            return switch (valueType) {
                case 'O' -> "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/String;)V";
                case 'J' -> "(Ljava/lang/Object;IJLjava/lang/String;)V";
                case 'F' -> "(Ljava/lang/Object;IFLjava/lang/String;)V";
                case 'D' -> "(Ljava/lang/Object;IDLjava/lang/String;)V";
                default -> "(Ljava/lang/Object;IILjava/lang/String;)V";
            };
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }

    private static void dumpTrace(LivingEntity entity, Set<String> targets, List<Trace.Entry> reads,
                                  List<Trace.WriteEntry> writes, List<Trace.MethodEntry> methods,
                                  List<Trace.ExitEntry> exits) {
        EcaLogger.warn("[HealthNumericInverter] entity={} instrumentedClasses={} methodEntries={} exits={} reads={} writes={}:",
                entity.getClass().getName(), targets.size(), methods.size(), exits.size(), reads.size(), writes.size());
        int cap = 40;
        EcaLogger.warn("[HealthNumericInverter] === Method entries ===");
        int index = 0;
        for (Trace.MethodEntry entry : methods) {
            if (index >= cap) {
                EcaLogger.warn("  ... ({} more)", methods.size() - cap);
                break;
            }
            EcaLogger.warn("  M#{} {} | recv={} | args={}", index++, entry.site, brief(entry.receiver), briefArgs(entry.args));
        }
        EcaLogger.warn("[HealthNumericInverter] === Method exits ===");
        index = 0;
        for (Trace.ExitEntry entry : exits) {
            if (index >= cap) {
                EcaLogger.warn("  ... ({} more)", exits.size() - cap);
                break;
            }
            EcaLogger.warn("  X#{} {} | value={}", index++, entry.site, String.valueOf(entry.value));
        }
        EcaLogger.warn("[HealthNumericInverter] === Field/array reads ===");
        index = 0;
        for (Trace.Entry entry : reads) {
            if (index >= cap) {
                EcaLogger.warn("  ... ({} more)", reads.size() - cap);
                break;
            }
            String idx = entry.index >= 0 ? "[" + entry.index + "]" : "";
            EcaLogger.warn("  #{} {} | container={}{} | value={}",
                    index++, entry.site, brief(entry.container), idx, String.valueOf(entry.value));
        }
        EcaLogger.warn("[HealthNumericInverter] === Field/array writes ===");
        index = 0;
        for (Trace.WriteEntry entry : writes) {
            if (index >= cap) {
                EcaLogger.warn("  ... ({} more)", writes.size() - cap);
                break;
            }
            String idx = entry.index >= 0 ? "[" + entry.index + "]" : "";
            EcaLogger.warn("  #{} {} | container={}{} | value={}",
                    index++, entry.site, brief(entry.container), idx, String.valueOf(entry.value));
        }
    }

    private static String brief(Object object) {
        if (object == null) return "static/null";
        return object.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(object));
    }

    private static String briefArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) builder.append(", ");
            Object arg = args[i];
            builder.append(arg == null ? "null"
                    : arg instanceof Number || arg instanceof Boolean ? String.valueOf(arg) : brief(arg));
        }
        return builder.append("]").toString();
    }
}
