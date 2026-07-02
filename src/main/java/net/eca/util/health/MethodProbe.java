package net.eca.util.health;

import net.eca.agent.EcaAgent;
import net.eca.util.EcaLogger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 方法探针模块：不碰存储，借实体自身的合法血量写方法改血——数据流/外部扫描直写存储都打不穿时的兜底。
 * 两种策略：
 *  DirectCall：静态枚举 1 参数数值 setter/函数式字段候选，运行期行为探测(写探测值→观察 getHealth→回滚)选出真 writer。
 *  HeadBridge：扫 void(float) 方法体识别 token(entity):long + writer(entity,float,long):void 授权写模式，
 *              warmup 在方法 HEAD 注入授权调用(惰性)，运行期借实体自身可信帧发起、绕过后续栈守护/门控。
 * 发现只读字节码/反射签名；注入/retransform/激活态/反射调用等副作用亦收拢于本类，模块自成一体。
 * 字节码经注入式 provider 取(与 HealthDataflowAnalyzer 同源运行期字节码)。
 */
public final class MethodProbe {

    private MethodProbe() {}

    // ==================== 注入式字节码源 ====================

    public interface ClassBytesProvider {
        byte[] get(Class<?> clazz);
    }
    private static volatile ClassBytesProvider bytesProvider = clazz -> null;
    public static void setClassBytesProvider(ClassBytesProvider provider) {
        if (provider != null) bytesProvider = provider;
    }

    private static final String RUNTIME = "net/eca/util/health/MethodProbe";
    private static final String ENTITY_INTERNAL = Type.getInternalName(Entity.class);

    // ==================== 模型 ====================

    /* 静态方法调用坐标：owner 内部名/方法名/描述符，供 HEAD 注入原样发起 token/writer 调用。 */
    public record StaticCall(String owner, String name, String desc) {}

    /* HeadBridge 注入规格：在 ownerInternal 的某 void(float) 方法 HEAD 注入
       token(this):long → writer(this,value,token):void。value 取该方法的 float 入参。 */
    public record BridgeSpec(String ownerInternal, String methodName, String methodDesc,
                             StaticCall token, StaticCall writer) {}

    public enum WriterKind { METHOD, FUNCTIONAL_FIELD }

    /* DirectCall 候选：METHOD=实体自身 1 参数数值方法；FUNCTIONAL_FIELD=持单数值 SAM 的函数式字段。
       仅静态签名信息，真正命中由运行期行为探测判定。 */
    public record DirectCandidate(WriterKind kind, String declaringInternal, String memberName, String inputDesc) {}

    /* 命中的直调 writer：绑定到某方法或函数式字段，可跨同类实例复用。 */
    public interface DirectWriter {
        boolean write(LivingEntity entity, float value);
        float representable(float value);
        String describe();
    }

    // ==================== HeadBridge 发现 ====================

    /* 扫实体类的 void(float) 方法，返回首个匹配 token+writer 授权写模式的 BridgeSpec；无则 null。 */
    public static BridgeSpec findBridgeSpec(Class<?> entityClass) {
        if (entityClass == null) return null;
        byte[] bytes = bytesProvider.get(entityClass);
        if (bytes == null) return null;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
            for (MethodNode method : node.methods) {
                if (!isBridgeCandidateMethod(method)) continue;
                BridgeSpec spec = scanBridgeSpec(node.name, method);
                if (spec != null) return spec;
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
        return null;
    }

    // 目标方法：非静态、void 返回、单 float 入参(注入时用其入参作为写入值)
    private static boolean isBridgeCandidateMethod(MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) != 0) return false;
        if (!method.desc.equals("(F)V")) return false;
        return method.instructions != null && method.instructions.size() > 0;
    }

    // 方法体内先出现 token 调用(INVOKESTATIC (entity):long)，其后出现 writer 调用(INVOKESTATIC (entity,float,long):void)
    private static BridgeSpec scanBridgeSpec(String ownerInternal, MethodNode method) {
        StaticCall token = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
            if (isTokenCall(call)) {
                token = new StaticCall(call.owner, call.name, call.desc);
                continue;
            }
            if (token != null && isWriterCall(call)) {
                return new BridgeSpec(ownerInternal, method.name, method.desc,
                        token, new StaticCall(call.owner, call.name, call.desc));
            }
        }
        return null;
    }

    // token: (LEntity;)J —— 取授权令牌
    private static boolean isTokenCall(MethodInsnNode call) {
        Type[] args = Type.getArgumentTypes(call.desc);
        return Type.getReturnType(call.desc).getSort() == Type.LONG
                && args.length == 1
                && args[0].getSort() == Type.OBJECT
                && ENTITY_INTERNAL.equals(args[0].getInternalName());
    }

    // writer: (LEntity;FJ)V —— 携令牌写血量
    private static boolean isWriterCall(MethodInsnNode call) {
        Type[] args = Type.getArgumentTypes(call.desc);
        return Type.getReturnType(call.desc).getSort() == Type.VOID
                && args.length == 3
                && args[0].getSort() == Type.OBJECT
                && ENTITY_INTERNAL.equals(args[0].getInternalName())
                && args[1].getSort() == Type.FLOAT
                && args[2].getSort() == Type.LONG;
    }

    // ==================== DirectCall 候选发现 ====================

    /* 沿继承链(至 LivingEntity 之前)静态枚举可能的写方法/函数式字段候选；去重，不判定命中。 */
    public static List<DirectCandidate> findDirectCandidates(Class<?> entityClass) {
        List<DirectCandidate> out = new ArrayList<>();
        if (entityClass == null) return out;
        Set<String> seen = new HashSet<>();
        for (Class<?> c = entityClass; c != null && c != LivingEntity.class && c != Object.class; c = c.getSuperclass()) {
            String ownerInternal = Type.getInternalName(c);
            for (Method method : c.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) continue;
                Class<?> input = method.getParameterTypes()[0];
                if (!isMethodInput(input)) continue;
                if (!seen.add("M:" + ownerInternal + ":" + method.getName() + ":" + input.getName())) continue;
                out.add(new DirectCandidate(WriterKind.METHOD, ownerInternal, method.getName(), Type.getDescriptor(input)));
            }
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> samInput = singleNumericSamInput(field);
                if (samInput == null) continue;
                if (!seen.add("F:" + ownerInternal + ":" + field.getName())) continue;
                out.add(new DirectCandidate(WriterKind.FUNCTIONAL_FIELD, ownerInternal, field.getName(), Type.getDescriptor(samInput)));
            }
        }
        return out;
    }

    // 字段类型是函数式接口且其唯一抽象方法接受单个数值入参时，返回该入参类型；否则 null
    private static Class<?> singleNumericSamInput(Field field) {
        Class<?> type = field.getType();
        if (type == null || !type.isInterface()) return null;
        Method sam = null;
        for (Method method : type.getMethods()) {
            int mods = method.getModifiers();
            if (!Modifier.isAbstract(mods) || Modifier.isStatic(mods) || method.getParameterCount() != 1) continue;
            if (sam != null && !sameSignature(sam, method)) return null;
            sam = method;
        }
        if (sam == null) return null;
        Class<?> input = sam.getParameterTypes()[0];
        if (!isNumericInput(input)) input = genericNumericInput(field);
        return isNumericInput(input) ? input : null;
    }

    private static Class<?> genericNumericInput(Field field) {
        java.lang.reflect.Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType parameterized)) return null;
        for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
            if (argument instanceof Class<?> clazz && isNumericInput(clazz)) return clazz;
        }
        return null;
    }

    private static boolean sameSignature(Method a, Method b) {
        return a.getName().equals(b.getName())
                && a.getReturnType() == b.getReturnType()
                && a.getParameterTypes()[0] == b.getParameterTypes()[0];
    }

    private static boolean isNumericInput(Class<?> type) {
        return type == float.class || type == double.class || type == int.class || type == long.class
                || type == short.class || type == byte.class || type == Float.class || type == Double.class
                || type == Integer.class || type == Long.class || type == Short.class || type == Byte.class
                || type == Number.class;
    }

    // 直调方法入参放宽到浮点系(整型 setter 交由数据流处理)
    private static boolean isMethodInput(Class<?> type) {
        return type == float.class || type == double.class || type == Float.class
                || type == Double.class || type == Number.class;
    }

    // ==================== HeadBridge 注入 + 安装 ====================

    private static final Map<String, BridgeSpec> SPECS = new ConcurrentHashMap<>();

    public static void registerSite(BridgeSpec spec) {
        if (spec == null || spec.ownerInternal() == null) return;
        SPECS.putIfAbsent(spec.ownerInternal(), spec);
    }

    public static BridgeSpec getSpec(String classInternal) {
        return classInternal == null ? null : SPECS.get(classInternal);
    }

    /* 对登记类字节码注入 HEAD 桥；无 spec 返回 null。由 EcaClassTransformer.doTransform 链尾调用。 */
    public static byte[] transform(String classInternal, byte[] bytes) {
        BridgeSpec spec = SPECS.get(classInternal);
        if (spec == null || bytes == null) return null;
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.EXPAND_FRAMES);
            MethodNode mn = null;
            for (MethodNode m : cn.methods) {
                if (m.name.equals(spec.methodName()) && m.desc.equals(spec.methodDesc())) { mn = m; break; }
            }
            if (mn == null || (mn.access & Opcodes.ACC_STATIC) != 0) return null;

            InsnList prefix = new InsnList();
            LabelNode skip = new LabelNode();
            prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "isBridgeActive", "(Ljava/lang/Object;)Z", false));
            prefix.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));                              // writer arg0: entity
            prefix.add(new TypeInsnNode(Opcodes.CHECKCAST, ENTITY_INTERNAL));
            prefix.add(new VarInsnNode(Opcodes.FLOAD, 1));                              // writer arg1: 目标值(方法入参)
            prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));                              // token arg0: entity
            prefix.add(new TypeInsnNode(Opcodes.CHECKCAST, ENTITY_INTERNAL));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, spec.token().owner(),
                    spec.token().name(), spec.token().desc(), false));                 // → writer arg2: token
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, spec.writer().owner(),
                    spec.writer().name(), spec.writer().desc(), false));
            prefix.add(new InsnNode(Opcodes.RETURN));
            prefix.add(skip);
            mn.instructions.insert(prefix);

            ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    /* 发现实体类的授权写模式并登记 + retransform 烤入 HEAD 桥；无 spec 或无 Instrumentation 则无操作。
       无条件安装——运行期是否借桥由配置双门控 + 激活态决定，未激活时桥惰性、方法照常。 */
    public static void installBridge(Class<?> entityClass) {
        if (entityClass == null) return;
        BridgeSpec spec = findBridgeSpec(entityClass);
        if (spec == null) return;
        registerSite(spec);
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            EcaLogger.info("[MethodProbe] bridge install skipped: Instrumentation unavailable");
            return;
        }
        Class<?> owner = HealthDataflowAnalyzer.loadClass(spec.ownerInternal());
        if (owner == null) return;
        try {
            if (inst.isModifiableClass(owner)) inst.retransformClasses(owner);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[MethodProbe] bridge retransform failed owner={} msg={}", owner.getName(), t.toString());
        }
    }

    /* getCommonSuperClass 回退 Object，避免 COMPUTE_FRAMES 时加载未就绪的 token/writer 属主类。 */
    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader cr, int flags) { super(cr, flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }

    // ==================== 运行期激活态 ====================

    private static final ThreadLocal<Object> ACTIVE_ENTITY = new ThreadLocal<>();

    // 由注入的 HEAD 字节码调用，签名稳定勿改：判断本实体当前是否处于桥激活态
    public static boolean isBridgeActive(Object entity) {
        return entity != null && entity == ACTIVE_ENTITY.get();
    }

    // ==================== DirectCall：行为探测 ====================

    /* 逐候选行为探测，返回首个真正控血的 writer；无则 null。探测会临时改动活体血量并回滚。 */
    public static DirectWriter resolveDirect(LivingEntity entity, List<DirectCandidate> candidates, float target) {
        float baseline = EcaSetHealthManager.safeGetHealth(entity);
        if (!Float.isFinite(baseline)) return null;
        float probeA = probeValue(baseline, target, 0.5f);
        float probeB = probeValue(baseline, target, 0.25f);
        if (tooClose(probeA, probeB) || tooClose(probeA, baseline) || tooClose(probeB, baseline)) return null;
        for (DirectCandidate candidate : candidates) {
            DirectWriter writer = bind(candidate);
            if (writer == null) continue;
            if (testWriter(entity, writer, baseline, probeA, probeB, target)) {
                EcaLogger.info("[MethodProbe] direct writer hit entity={} writer={}",
                        entity.getClass().getName(), writer.describe());
                return writer;
            }
        }
        return null;
    }

    // 两个探测值须都能被写入并被 getHealth 读回，再复原 baseline 并验证，最后命中 target 才算真 writer
    private static boolean testWriter(LivingEntity entity, DirectWriter writer, float baseline,
                                      float probeA, float probeB, float target) {
        try {
            float a = writer.representable(probeA);
            float b = writer.representable(probeB);
            if (tooClose(a, b) || tooClose(a, baseline) || tooClose(b, baseline)) return false;

            if (!writer.write(entity, a) || !matches(EcaSetHealthManager.safeGetHealth(entity), a)) {
                restore(entity, writer, baseline);
                return false;
            }
            if (!writer.write(entity, b) || !matches(EcaSetHealthManager.safeGetHealth(entity), b)) {
                restore(entity, writer, baseline);
                return false;
            }
            writer.write(entity, baseline);
            if (!EcaSetHealthManager.verify(entity, baseline)) {
                restore(entity, writer, baseline);
                return false;
            }
            if (writer.write(entity, target) && EcaSetHealthManager.verify(entity, target)) return true;
            restore(entity, writer, baseline);
            return false;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            restore(entity, writer, baseline);
            return false;
        }
    }

    private static void restore(LivingEntity entity, DirectWriter writer, float baseline) {
        try {
            writer.write(entity, baseline);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
    }

    private static DirectWriter bind(DirectCandidate candidate) {
        Class<?> owner = HealthDataflowAnalyzer.loadClass(candidate.declaringInternal());
        if (owner == null) return null;
        Class<?> inputType = HealthDataflowAnalyzer.descriptorToClass(candidate.inputDesc());
        if (inputType == null) return null;
        try {
            if (candidate.kind() == WriterKind.METHOD) {
                Method method = findMethod(owner, candidate.memberName(), inputType);
                if (method == null) return null;
                method.setAccessible(true);
                return new MethodWriter(method, inputType);
            }
            Field field = HealthDataflowAnalyzer.findFieldInHierarchy(owner, candidate.memberName());
            if (field == null) return null;
            Method sam = singleAbstract(field.getType());
            if (sam == null) return null;
            field.setAccessible(true);
            sam.setAccessible(true);
            return new FunctionalWriter(field, sam, inputType);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String name, Class<?> inputType) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1) continue;
                if (m.getName().equals(name) && m.getParameterTypes()[0] == inputType) return m;
            }
        }
        return null;
    }

    private static Method singleAbstract(Class<?> type) {
        if (type == null || !type.isInterface()) return null;
        Method found = null;
        for (Method m : type.getMethods()) {
            int mods = m.getModifiers();
            if (!Modifier.isAbstract(mods) || Modifier.isStatic(mods) || m.getParameterCount() != 1) continue;
            if (found != null) return null;
            found = m;
        }
        return found;
    }

    // ==================== HeadBridge：借实体自身可信帧发起 ====================

    /* 为实体激活桥并调其被注入的 void(float) 方法，让 HEAD 桥直发 token+writer；验证后清激活态。 */
    public static boolean invokeBridge(LivingEntity entity, BridgeSpec spec, float target) {
        Method method = resolveBridgeMethod(entity.getClass(), spec);
        if (method == null) return false;
        try {
            ACTIVE_ENTITY.set(entity);
            method.invoke(entity, target);
            boolean ok = EcaSetHealthManager.verify(entity, target);
            if (ok) EcaLogger.info("[MethodProbe] head bridge hit entity={} method={}",
                    entity.getClass().getName(), spec.methodName());
            return ok;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        } finally {
            ACTIVE_ENTITY.remove();
        }
    }

    private static Method resolveBridgeMethod(Class<?> entityClass, BridgeSpec spec) {
        for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!c.getName().replace('.', '/').equals(spec.ownerInternal())) continue;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(spec.methodName()) && Type.getMethodDescriptor(m).equals(spec.methodDesc())) {
                    try {
                        m.setAccessible(true);
                        return m;
                    } catch (Throwable t) {
                        if (t instanceof VirtualMachineError e) throw e;
                        return null;
                    }
                }
            }
        }
        return null;
    }

    // ==================== Writer 实现 + 数值工具 ====================

    private static final class MethodWriter implements DirectWriter {
        private final Method method;
        private final Class<?> inputType;

        private MethodWriter(Method method, Class<?> inputType) {
            this.method = method;
            this.inputType = inputType;
        }

        @Override public boolean write(LivingEntity entity, float value) {
            try {
                method.invoke(entity, coerce(value, inputType));
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
        }

        @Override public float representable(float value) { return representableFor(value, inputType); }

        @Override public String describe() { return method.getDeclaringClass().getName() + "#" + method.getName(); }
    }

    private static final class FunctionalWriter implements DirectWriter {
        private final Field field;
        private final Method sam;
        private final Class<?> inputType;

        private FunctionalWriter(Field field, Method sam, Class<?> inputType) {
            this.field = field;
            this.sam = sam;
            this.inputType = inputType;
        }

        @Override public boolean write(LivingEntity entity, float value) {
            try {
                Object function = field.get(entity);
                if (function == null) return false;
                sam.invoke(function, coerce(value, inputType));
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
        }

        @Override public float representable(float value) { return representableFor(value, inputType); }

        @Override public String describe() {
            return field.getDeclaringClass().getName() + "#" + field.getName() + "::" + sam.getName();
        }
    }

    private static Object coerce(float value, Class<?> type) {
        Object coerced = HealthDataflowAnalyzer.coerceForType(Float.valueOf(value), type);
        return coerced != null ? coerced : Float.valueOf(value);
    }

    private static float representableFor(float value, Class<?> type) {
        Object coerced = HealthDataflowAnalyzer.coerceForType(Float.valueOf(value), type);
        return coerced instanceof Number number ? number.floatValue() : value;
    }

    private static float probeValue(float baseline, float target, float factor) {
        float value = baseline * factor;
        if (tooClose(value, baseline) || tooClose(value, target)) {
            value = baseline + Math.max(2.0f, Math.abs(baseline) * factor);
        }
        return value;
    }

    private static boolean matches(float actual, float expected) {
        if (!Float.isFinite(actual)) return false;
        float tolerance = Math.max(0.5f, Math.abs(expected) * 0.02f);
        return Math.abs(actual - expected) <= tolerance;
    }

    private static boolean tooClose(float a, float b) {
        return Math.abs(a - b) < 1.0f;
    }
}
