package net.eca.util.health.internal;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.eca.coremod.EcaTransformerManager;
import net.eca.coremod.LivingEntityHook;
import net.eca.util.EcaLogger;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.FieldNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 方法探针模块：静态读写切片无法直接写入存储时，调用实体自身的候选写方法。
 * 两种策略：
 *  DirectCall：静态枚举单参数数值 setter 和函数式字段，通过写入、读取与回滚确定有效 writer。
 *  HeadBridge：扫 void(float) 方法体识别 token(entity):long + writer(entity,float,long):void 授权写模式，
 *              warmup 在方法 HEAD 注入授权调用(惰性)，运行期借实体自身可信帧发起、绕过后续栈守护/门控。
 * 发现只读字节码/反射签名；注入/retransform/激活态/反射调用等副作用亦收拢于本类，模块自成一体。
 * 字节码经注入式 provider 取(与 ProtocolDataflowAnalyzer 同源运行期字节码)。
 */
public final class ProtocolMethodProbe {

    private ProtocolMethodProbe() {}

    // ==================== 注入式字节码源 ====================

    public interface ClassBytesProvider {
        byte[] get(Class<?> clazz);
    }
    private static volatile ClassBytesProvider bytesProvider = clazz -> null;
    public static void setClassBytesProvider(ClassBytesProvider provider) {
        if (provider != null) bytesProvider = provider;
    }

    private static final String RUNTIME = "net/eca/util/health/internal/ProtocolMethodProbe";
    private static final String ENTITY_INTERNAL = Type.getInternalName(Entity.class);

    // ==================== 模型 ====================

    /* 静态方法调用坐标：owner 内部名/方法名/描述符，供 HEAD 注入原样发起 token/writer 调用。 */
    public record StaticCall(String owner, String name, String desc) {}

    /* HeadBridge 注入规格：在 ownerInternal 的某 void(float) 方法 HEAD 注入
       token(this):long → writer(this,value,token):void。value 取该方法的 float 入参。 */
    public record BridgeSpec(String ownerInternal, String methodName, String methodDesc,
                             StaticCall token, StaticCall writer) {}

    private record TrustedBridge(MethodHandle apply, String className) {}

    public enum WriterKind { METHOD, FUNCTIONAL_FIELD, METHOD_HANDLE_FIELD, FIELD_COMMIT }

    /* DirectCall 候选：METHOD=实体自身 1 参数数值方法；FUNCTIONAL_FIELD=持单数值 SAM 的函数式字段。
       此处仅记录静态签名，是否有效由运行期行为探测判定。 */
    public record DirectCandidate(WriterKind kind, String declaringInternal, String memberName, String inputDesc,
                                  String fieldDesc, boolean fieldStatic) {}

    /* 已验证的直调 writer，绑定到方法或函数式字段，可供同类实例复用。 */
    public interface DirectWriter {
        boolean write(LivingEntity entity, float value);
        float representable(float value);
        String describe();
        default boolean writeAssociated(LivingEntity entity, float value) { return false; }
        default boolean hasAssociatedWrites() { return false; }
        default void preferAssociatedWrites() {}
    }

    // ==================== HeadBridge 发现 ====================

    /* 扫实体类的 void(float) 方法，返回首个匹配 token+writer 授权写模式的 BridgeSpec；无则 null。 */
    public static BridgeSpec findBridgeSpec(Class<?> entityClass) {
        if (entityClass == null) return null;
        for (Class<?> c = entityClass; c != null && c != LivingEntity.class && c != Object.class; c = c.getSuperclass()) {
            BridgeSpec spec = findBridgeSpecInClass(c);
            if (spec != null) return spec;
        }
        return null;
    }

    private static BridgeSpec findBridgeSpecInClass(Class<?> entityClass) {
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
                out.add(new DirectCandidate(WriterKind.METHOD, ownerInternal, method.getName(), Type.getDescriptor(input), null, false));
            }
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() == MethodHandle.class) {
                    if (seen.add("H:" + ownerInternal + ":" + field.getName())) {
                        out.add(new DirectCandidate(WriterKind.METHOD_HANDLE_FIELD, ownerInternal, field.getName(), "", Type.getDescriptor(field.getType()), Modifier.isStatic(field.getModifiers())));
                    }
                    continue;
                }
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> samInput = singleNumericSamInput(field);
                if (samInput == null) continue;
                if (!seen.add("F:" + ownerInternal + ":" + field.getName())) continue;
                out.add(new DirectCandidate(WriterKind.FUNCTIONAL_FIELD, ownerInternal, field.getName(), Type.getDescriptor(samInput), Type.getDescriptor(field.getType()), false));
            }
            findBytecodeFieldCandidates(c, ownerInternal, out, seen);
            findFieldCommitCandidates(c, ownerInternal, out, seen);
        }
        /* 排序决定探测顺序：反射 setter 和函数式字段优先，MethodHandle 字段与 FIELD_COMMIT 后置。
           FIELD_COMMIT 可能触发不可回滚的目标状态，因此只能在 HeadBridge 之后执行。 */
        out.sort(Comparator.comparingInt(candidate -> switch (candidate.kind()) {
            case METHOD -> 0;
            case FUNCTIONAL_FIELD -> 1;
            case METHOD_HANDLE_FIELD -> 2;
            case FIELD_COMMIT -> 3;
        }));
        return out;
    }

    /* 反射缓存可能被目标主动清空；字段元数据仍在 classfile 中，作为无反射后备。 */
    private static void findBytecodeFieldCandidates(Class<?> owner, String ownerInternal,
                                                     List<DirectCandidate> out, Set<String> seen) {
        byte[] bytes = bytesProvider.get(owner);
        if (bytes == null) return;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
            for (FieldNode field : node.fields) {
                boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
                if (Type.getDescriptor(MethodHandle.class).equals(field.desc)) {
                    if (seen.add("H:" + ownerInternal + ":" + field.name))
                        out.add(new DirectCandidate(WriterKind.METHOD_HANDLE_FIELD, ownerInternal, field.name, "", field.desc, isStatic));
                    continue;
                }
                if (isStatic) continue;
                Class<?> fieldType = ProtocolDataflowAnalyzer.descriptorToClass(field.desc);
                Class<?> input = singleNumericSamInput(fieldType);
                if (input == null || !seen.add("F:" + ownerInternal + ":" + field.name)) continue;
                out.add(new DirectCandidate(WriterKind.FUNCTIONAL_FIELD, ownerInternal, field.name,
                        Type.getDescriptor(input), field.desc, false));
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
    }

    /* 暂存字段+无参提交：void() 方法体读取本类 float/double 字段后经调用提交(加密写入/委托等)。
           此处只做静态签名筛选，运行期行为探测负责验证写入效果。 */
    private static void findFieldCommitCandidates(Class<?> owner, String ownerInternal,
                                                  List<DirectCandidate> out, Set<String> seen) {
        byte[] bytes = bytesProvider.get(owner);
        if (bytes == null) return;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
            for (MethodNode method : node.methods) {
                if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
                if (!method.desc.equals("()V")) continue;
                if (method.name.startsWith("<")) continue;
                if (method.instructions == null || method.instructions.size() == 0) continue;
                String stagingField = findStagingFloatField(method, ownerInternal);
                if (stagingField == null) continue;
                if (!hasSideEffectingCall(method)) continue;
                if (!seen.add("FC:" + ownerInternal + ":" + method.name + ":" + stagingField)) continue;
                out.add(new DirectCandidate(WriterKind.FIELD_COMMIT, ownerInternal, method.name,
                        "F", stagingField, false));
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
    }

    /* 在 void() 方法体中定位首个本类(或继承链内) float/double 实例字段的 GETFIELD，返回字段名 */
    private static String findStagingFloatField(MethodNode method, String ownerInternal) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FieldInsnNode fieldInsn)) continue;
            if (fieldInsn.getOpcode() != Opcodes.GETFIELD) continue;
            if (!fieldInsn.desc.equals("F") && !fieldInsn.desc.equals("D")) continue;
            if (fieldInsn.owner.equals(ownerInternal) || isAncestorInternal(fieldInsn.owner, ownerInternal)) {
                return fieldInsn.name;
            }
        }
        return null;
    }

    private static boolean isAncestorInternal(String candidateAncestor, String childInternal) {
        Class<?> child = ProtocolDataflowAnalyzer.loadClass(childInternal);
        if (child == null) return false;
        String ancestorBinary = candidateAncestor.replace('/', '.');
        for (Class<?> c = child.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals(ancestorBinary)) return true;
        }
        return false;
    }

    /* 方法体含至少一个产生副作用的调用(排除纯 getter/toString/hashCode 等无副作用方法) */
    private static boolean hasSideEffectingCall(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            int opcode = call.getOpcode();
            if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKESTATIC
                    && opcode != Opcodes.INVOKEINTERFACE && opcode != Opcodes.INVOKESPECIAL) continue;
            if (call.owner.equals("java/lang/Object")) continue;
            if (isPureGetterName(call.name, call.desc)) continue;
            return true;
        }
        return false;
    }

    private static boolean isPureGetterName(String name, String desc) {
        if (Type.getArgumentTypes(desc).length > 0) return false;
        return name.startsWith("get") || name.startsWith("is") || name.equals("toString")
                || name.equals("hashCode") || name.equals("ordinal") || name.equals("name");
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
        // 变长 SAM(Object...)：读写共用一个入口，靠实参个数分派，行为探测足以判定是否为 writer
        if (input == Object[].class) return input;
        if (!isNumericInput(input)) input = genericNumericInput(field);
        // 擦除为 Object 的 Consumer<Object> 仍可能是动态控血 writer；行为探测会以两次写入和回滚确认。
        return isNumericInput(input) || input == Object.class ? input : null;
    }

    private static Class<?> singleNumericSamInput(Class<?> type) {
        if (type == null || !type.isInterface()) return null;
        Method sam = singleAbstract(type);
        if (sam == null) return null;
        Class<?> input = sam.getParameterTypes()[0];
        if (input == Object[].class) return input;
        return isNumericInput(input) || input == Object.class ? input : null;
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
    private static final Map<String, TrustedBridge> TRUSTED_BRIDGES = new ConcurrentHashMap<>();
    private static final Set<String> TRUSTED_BRIDGE_FAILED = ConcurrentHashMap.newKeySet();
    private static final Set<String> METHOD_HANDLE_DIAGNOSTICS = ConcurrentHashMap.newKeySet();
    private static final int TRUSTED_BRIDGE_DEPTH = 8;

    public static void registerSite(BridgeSpec spec) {
        if (spec == null || spec.ownerInternal() == null) return;
        SPECS.putIfAbsent(spec.ownerInternal(), spec);
    }

    private static void registerSite(BridgeSpec spec, String lookupInternal) {
        registerSite(spec);
        if (spec == null || lookupInternal == null) return;
        SPECS.putIfAbsent(lookupInternal, spec);
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

    /* Register the bridge site and bake it through the active transform backend. */
    public static void installBridge(Class<?> entityClass) {
        if (entityClass == null) return;
        BridgeSpec spec = findBridgeSpec(entityClass);
        if (spec == null) return;
        registerSite(spec, Type.getInternalName(entityClass));
        Class<?> owner = ProtocolDataflowAnalyzer.loadClass(spec.ownerInternal());
        if (owner == null) return;
        try {
            if (!EcaTransformerManager.retransformClass(owner)) {
                if (!LifeProtocolManager.isWarmupDiagnosticsSuppressed())
                    EcaLogger.info("[ProtocolMethodProbe] bridge retransform unavailable owner={}", owner.getName());
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            if (!LifeProtocolManager.isWarmupDiagnosticsSuppressed())
                EcaLogger.info("[ProtocolMethodProbe] bridge retransform failed owner={} msg={}", owner.getName(), t.toString());
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

    /* 依次探测候选并返回首个通过血量校验的 writer；没有匹配项时返回 null。探测结束后恢复临时改动。 */
    public static DirectWriter resolveDirect(LivingEntity entity, List<DirectCandidate> candidates,
                                             float target, List<Object> rollbackRoots) {
        float baseline = LifeProtocolManager.readHealthAnchor(entity);
        if (!Float.isFinite(baseline)) return null;
        float[] probes = selectProbeValues(baseline, target, safeGetMaxHealth(entity));
        if (probes == null) return null;
        float probeA = probes[0];
        float probeB = probes[1];
        for (DirectCandidate candidate : candidates) {
            String diagnosticKey = entity.getClass().getName() + "|" + candidate.declaringInternal()
                    + "#" + candidate.memberName();
            boolean diagnostic = candidate.kind() == WriterKind.METHOD_HANDLE_FIELD
                    && METHOD_HANDLE_DIAGNOSTICS.add(diagnosticKey);
            if (diagnostic) EcaLogger.info("[ProtocolMethodProbe] MethodHandle candidate entity={} field={}#{} static={}",
                    entity.getClass().getName(), candidate.declaringInternal(), candidate.memberName(), candidate.fieldStatic());
            DirectWriter writer = bind(candidate, entity);
            if (writer == null) {
                if (diagnostic) EcaLogger.info("[ProtocolMethodProbe] MethodHandle bind rejected field={}#{}",
                        candidate.declaringInternal(), candidate.memberName());
                continue;
            }
            if (diagnostic) EcaLogger.info("[ProtocolMethodProbe] MethodHandle bound writer={} baseline={} probeA={} probeB={} target={}",
                    writer.describe(), baseline, probeA, probeB, target);
            if (writer.hasAssociatedWrites()) {
                ProtocolStateSnapshot snapshot = ProtocolStateSnapshot.captureProbe(entity, rollbackRoots);
                if (testAssociatedWriter(entity, writer, baseline, probeA, probeB, target, diagnostic)) {
                    snapshot.restore();
                    writer.preferAssociatedWrites();
                    EcaLogger.info("[ProtocolMethodProbe] associated writer hit entity={} writer={}",
                            entity.getClass().getName(), writer.describe());
                    return writer;
                }
                snapshot.restore();
            }
            ProtocolStateSnapshot snapshot = ProtocolStateSnapshot.captureProbe(entity, rollbackRoots);
            if (testWriter(entity, writer, baseline, probeA, probeB, target, diagnostic)) {
                snapshot.restore();
                EcaLogger.info("[ProtocolMethodProbe] direct writer hit entity={} writer={}",
                        entity.getClass().getName(), writer.describe());
                return writer;
            }
            snapshot.restore();
        }
        return null;
    }

    private static boolean testAssociatedWriter(LivingEntity entity, DirectWriter writer, float baseline,
                                                float probeA, float probeB, float target, boolean diagnostic) {
        try {
            float a = writer.representable(probeA);
            float b = writer.representable(probeB);
            if (tooClose(a, b) || tooClose(a, baseline) || tooClose(b, baseline)) return false;
            boolean wroteA = writer.writeAssociated(entity, a);
            float actualA = LifeProtocolManager.readHealthAnchor(entity);
            if (diagnostic) EcaLogger.info("[ProtocolMethodProbe] associated probeA wrote={} expected={} actual={}",
                    wroteA, a, actualA);
            if (!wroteA || !matches(actualA, a)) return false;
            boolean wroteB = writer.writeAssociated(entity, b);
            float actualB = LifeProtocolManager.readHealthAnchor(entity);
            if (diagnostic) EcaLogger.info("[ProtocolMethodProbe] associated probeB wrote={} expected={} actual={}",
                    wroteB, b, actualB);
            if (!wroteB || !matches(actualB, b)) return false;
            // 与 testWriter 同理：两个不同写入分别被锚点读回，已证明锚点反映真实存储
            LifeProtocolManager.promoteAnchorTrust(entity.getClass());
            if (!writer.writeAssociated(entity, baseline) || !LifeProtocolManager.verify(entity, baseline)) return false;
            boolean wroteTarget = writer.writeAssociated(entity, target);
            float actualTarget = LifeProtocolManager.readHealthAnchor(entity);
            if (diagnostic) EcaLogger.info("[ProtocolMethodProbe] associated target wrote={} expected={} actual={}",
                    wroteTarget, target, actualTarget);
            return wroteTarget && matchesTarget(actualTarget, target);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    // 两个探测值须都能被写入并被 getHealth 读回，再复原 baseline 并验证，最后命中 target 才算真 writer
    private static boolean testWriter(LivingEntity entity, DirectWriter writer, float baseline,
                                      float probeA, float probeB, float target, boolean diagnostic) {
        try {
            float a = writer.representable(probeA);
            float b = writer.representable(probeB);
            if (tooClose(a, b) || tooClose(a, baseline) || tooClose(b, baseline)) return false;

            SynchedDataState beforeA = diagnostic ? SynchedDataState.capture(entity) : null;
            boolean wroteA = writer.write(entity, a);
            SynchedDataState stateA = diagnostic ? SynchedDataState.capture(entity) : null;
            float actualA = LifeProtocolManager.readHealthAnchor(entity);
            if (diagnostic) {
                SynchedDataState afterReadA = SynchedDataState.capture(entity);
                EcaLogger.info("[ProtocolMethodProbe] MethodHandle probeA wrote={} expected={} actual={} writeState={} readState={}",
                        wroteA, a, actualA, stateA.diffFrom(beforeA), afterReadA.diffFrom(stateA));
            }
            if (!wroteA || !matches(actualA, a)) {
                restore(entity, writer, baseline);
                return false;
            }
            boolean wroteB = writer.write(entity, b);
            float actualB = LifeProtocolManager.readHealthAnchor(entity);
            if (diagnostic) EcaLogger.info("[ProtocolMethodProbe] MethodHandle probeB wrote={} expected={} actual={}", wroteB, b, actualB);
            if (!wroteB || !matches(actualB, b)) {
                restore(entity, writer, baseline);
                return false;
            }
            /* 锚点分别读回了两个不同的写入值，已直接证明它反映真实存储。
               读自定义存储的 getHealth 不跟随原版字段，弱取证会误判其不可信，在此补正。 */
            LifeProtocolManager.promoteAnchorTrust(entity.getClass());
            writer.write(entity, baseline);
            if (!LifeProtocolManager.verify(entity, baseline)) {
                restore(entity, writer, baseline);
                return false;
            }
            boolean wroteTarget = writer.write(entity, target);
            float actualTarget = LifeProtocolManager.readHealthAnchor(entity);
            if (diagnostic) EcaLogger.info("[ProtocolMethodProbe] MethodHandle target wrote={} expected={} actual={}",
                    wroteTarget, target, actualTarget);
            if (wroteTarget && matchesTarget(actualTarget, target)) return true;
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

    private static DirectWriter bind(DirectCandidate candidate, LivingEntity entity) {
        Class<?> owner = ProtocolDataflowAnalyzer.loadClass(candidate.declaringInternal());
        if (owner == null) return null;
        try {
            if (candidate.kind() == WriterKind.FIELD_COMMIT) {
                return bindFieldCommit(candidate, owner);
            }
            if (candidate.kind() == WriterKind.METHOD_HANDLE_FIELD) {
                Field field = ProtocolDataflowAnalyzer.findFieldInHierarchy(owner, candidate.memberName());
                MethodHandle handle;
                if (field != null && field.getType() == MethodHandle.class) {
                    field.setAccessible(true);
                    handle = (MethodHandle) field.get(Modifier.isStatic(field.getModifiers()) ? null : entity);
                    if (handle == null || handle.type().parameterCount() != 2 || handle.type().returnType() != void.class) return null;
                    Class<?> input = handle.type().parameterType(1);
                    HandleTarget target = revealHandleTarget(handle, owner);
                    return isMethodInput(input) ? new MethodHandleWriter(field, input, target) : null;
                }
                VarHandle handleField = findVarHandle(owner, candidate, MethodHandle.class);
                if (handleField == null) return null;
                handle = (MethodHandle) (candidate.fieldStatic() ? handleField.get() : handleField.get(entity));
                if (handle == null || handle.type().parameterCount() != 2 || handle.type().returnType() != void.class) return null;
                Class<?> input = handle.type().parameterType(1);
                HandleTarget target = revealHandleTarget(handle, owner);
                return isMethodInput(input)
                        ? new VarHandleMethodHandleWriter(handleField, candidate.fieldStatic(), input, target)
                        : null;
            }
            Class<?> inputType = ProtocolDataflowAnalyzer.descriptorToClass(candidate.inputDesc());
            if (inputType == null) return null;
            if (candidate.kind() == WriterKind.METHOD) {
                Method method = findMethod(owner, candidate.memberName(), inputType);
                if (method == null) return null;
                method.setAccessible(true);
                return new MethodWriter(method, inputType);
            }
            Field field = ProtocolDataflowAnalyzer.findFieldInHierarchy(owner, candidate.memberName());
            if (field == null) {
                Class<?> fieldType = ProtocolDataflowAnalyzer.descriptorToClass(candidate.fieldDesc());
                VarHandle handle = findVarHandle(owner, candidate, fieldType);
                Method sam = singleAbstract(fieldType);
                return handle == null || sam == null ? null : new VarHandleFunctionalWriter(handle, candidate.fieldStatic(), sam, inputType);
            }
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

    private static VarHandle findVarHandle(Class<?> owner, DirectCandidate candidate, Class<?> fieldType) {
        if (fieldType == null) return null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            return candidate.fieldStatic() ? lookup.findStaticVarHandle(owner, candidate.memberName(), fieldType)
                    : lookup.findVarHandle(owner, candidate.memberName(), fieldType);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    /* 暂存字段+无参提交绑定：定位提交方法与暂存字段，缺一则放弃 */
    private static DirectWriter bindFieldCommit(DirectCandidate candidate, Class<?> owner) {
        Method commitMethod = findNoArgVoidMethod(owner, candidate.memberName());
        if (commitMethod == null) return null;
        Field stagingField = ProtocolDataflowAnalyzer.findFieldInHierarchy(owner, candidate.fieldDesc());
        if (stagingField == null) return null;
        if (stagingField.getType() != float.class && stagingField.getType() != double.class) return null;
        commitMethod.setAccessible(true);
        stagingField.setAccessible(true);
        return new FieldCommitWriter(stagingField, commitMethod);
    }

    private static Method findNoArgVoidMethod(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 0 || m.getReturnType() != void.class) continue;
                if (m.getName().equals(name)) return m;
            }
        }
        return null;
    }

    private record HandleTarget(String description, ProtocolDataflowAnalyzer.AnalysisResult writes) {}

    private static HandleTarget revealHandleTarget(MethodHandle handle, Class<?> owner) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            MethodHandleInfo info = lookup.revealDirect(handle);
            boolean isStatic = info.getReferenceKind() == MethodHandleInfo.REF_invokeStatic;
            String desc = info.getMethodType().toMethodDescriptorString();
            ProtocolDataflowAnalyzer.AnalysisResult writes = ProtocolDataflowAnalyzer.analyzeWriterMethod(
                    info.getDeclaringClass(), info.getName(), desc, isStatic);
            return new HandleTarget(info.getDeclaringClass().getName() + "#" + info.getName()
                    + info.getMethodType(), writes);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return new HandleTarget("unresolved", null);
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
    public static boolean invokeBridge(LivingEntity entity, BridgeSpec spec, float target, List<Object> rollbackRoots) {
        Method method = resolveBridgeMethod(entity.getClass(), spec);
        if (method == null) return false;
        ProtocolStateSnapshot snapshot = ProtocolStateSnapshot.captureProbe(entity, rollbackRoots);
        float baseline = LifeProtocolManager.readHealthAnchor(entity);
        try {
            ACTIVE_ENTITY.set(entity);
            method.invoke(entity, target);
            LifeProtocolManager.noteAnchorResponse(entity, baseline, target);
            boolean ok = LifeProtocolManager.verify(entity, target);
            if (ok) EcaLogger.info("[ProtocolMethodProbe] head bridge hit entity={} method={}",
                    entity.getClass().getName(), spec.methodName());
            if (!ok) snapshot.restore();
            return ok;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            snapshot.restore();
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

    public static boolean invokeTrustedBridge(LivingEntity entity, BridgeSpec spec, float target) {
        if (entity == null || spec == null) return false;
        TrustedBridge bridge = trustedBridge(spec);
        if (bridge == null) return false;
        float baseline = LifeProtocolManager.readHealthAnchor(entity);
        try {
            bridge.apply().invokeExact((Entity) entity, target);
            // 桥没有两点探测，改用写入前后的锚点位移取证，否则读自定义存储的 getHealth 会被弱取证永久判死
            LifeProtocolManager.noteAnchorResponse(entity, baseline, target);
            if (LifeProtocolManager.verify(entity, target)) {
                EcaLogger.info("[ProtocolMethodProbe] trusted bridge hit entity={} bridge={}",
                        entity.getClass().getName(), bridge.className());
                return true;
            }
            restoreTrustedBridge(bridge, entity, baseline);
            return false;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            restoreTrustedBridge(bridge, entity, baseline);
            return false;
        }
    }

    private static void restoreTrustedBridge(TrustedBridge bridge, LivingEntity entity, float baseline) {
        if (!Float.isFinite(baseline)) return;
        try {
            bridge.apply().invokeExact((Entity) entity, baseline);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
    }

    private static TrustedBridge trustedBridge(BridgeSpec spec) {
        String key = bridgeKey(spec);
        TrustedBridge cached = TRUSTED_BRIDGES.get(key);
        if (cached != null) return cached;
        if (TRUSTED_BRIDGE_FAILED.contains(key)) return null;
        try {
            TrustedBridge created = createTrustedBridge(spec, key);
            if (created == null) {
                TRUSTED_BRIDGE_FAILED.add(key);
                return null;
            }
            TrustedBridge existing = TRUSTED_BRIDGES.putIfAbsent(key, created);
            return existing != null ? existing : created;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            if (TRUSTED_BRIDGE_FAILED.add(key)) {
                EcaLogger.info("[ProtocolMethodProbe] trusted bridge unavailable owner={} msg={}",
                        spec.ownerInternal(), t.toString());
            }
            return null;
        }
    }

    private static TrustedBridge createTrustedBridge(BridgeSpec spec, String key) throws Throwable {
        Class<?> owner = ProtocolDataflowAnalyzer.loadClass(spec.ownerInternal());
        if (owner == null) return null;
        String helperInternal = helperInternalName(spec.ownerInternal(), key);
        String helperBinary = helperInternal.replace('/', '.');
        Class<?> helper = findLoadedHelper(owner, helperBinary);
        if (helper == null) {
            byte[] bytes = buildTrustedBridgeClass(helperInternal, spec);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            helper = lookup.defineClass(bytes);
        }
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(helper, MethodHandles.lookup());
        MethodHandle apply = lookup.findStatic(helper, "apply",
                MethodType.methodType(void.class, Entity.class, float.class));
        return new TrustedBridge(apply, helperBinary);
    }

    private static Class<?> findLoadedHelper(Class<?> owner, String binaryName) {
        try {
            return Class.forName(binaryName, false, owner.getClassLoader());
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static String bridgeKey(BridgeSpec spec) {
        return spec.ownerInternal() + "|" + spec.methodName() + spec.methodDesc()
                + "|" + spec.token().owner() + "." + spec.token().name() + spec.token().desc()
                + "|" + spec.writer().owner() + "." + spec.writer().name() + spec.writer().desc();
    }

    private static String helperInternalName(String ownerInternal, String key) {
        int slash = ownerInternal.lastIndexOf('/');
        String pkg = slash >= 0 ? ownerInternal.substring(0, slash + 1) : "";
        return pkg + "EcaHealthBridge$" + Integer.toUnsignedString(key.hashCode(), 16);
    }

    private static byte[] buildTrustedBridgeClass(String helperInternal, BridgeSpec spec) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                helperInternal, null, "java/lang/Object", null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        writeBridgeForwarder(cw, helperInternal, "apply", "step0", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        for (int i = 0; i < TRUSTED_BRIDGE_DEPTH - 1; i++) {
            writeBridgeForwarder(cw, helperInternal, "step" + i, "step" + (i + 1), Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC);
        }
        writeBridgeWriter(cw, spec, "step" + (TRUSTED_BRIDGE_DEPTH - 1));

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeBridgeForwarder(ClassWriter cw, String helperInternal, String name, String next, int access) {
        MethodVisitor mv = cw.visitMethod(access, name, "(Lnet/minecraft/world/entity/Entity;F)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.FLOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, helperInternal, next,
                "(Lnet/minecraft/world/entity/Entity;F)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void writeBridgeWriter(ClassWriter cw, BridgeSpec spec, String name) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name,
                "(Lnet/minecraft/world/entity/Entity;F)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.FLOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, spec.token().owner(), spec.token().name(), spec.token().desc(), false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, spec.writer().owner(), spec.writer().name(), spec.writer().desc(), false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
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
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
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
                sam.invoke(function, samArgument(value, inputType));
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
        }

        @Override public float representable(float value) { return representableFor(value, inputType); }

        @Override public String describe() {
            return field.getDeclaringClass().getName() + "#" + field.getName() + "::" + sam.getName();
        }
    }

    private static final class VarHandleFunctionalWriter implements DirectWriter {
        private final VarHandle field;
        private final boolean isStatic;
        private final Method sam;
        private final Class<?> inputType;

        private VarHandleFunctionalWriter(VarHandle field, boolean isStatic, Method sam, Class<?> inputType) {
            this.field = field;
            this.isStatic = isStatic;
            this.sam = sam;
            this.inputType = inputType;
        }

        @Override public boolean write(LivingEntity entity, float value) {
            try {
                Object function = isStatic ? field.get() : field.get(entity);
                if (function == null) return false;
                sam.invoke(function, samArgument(value, inputType));
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
        }

        @Override public float representable(float value) { return representableFor(value, inputType); }

        @Override public String describe() { return "VarHandle functional writer"; }
    }

    /* MethodHandle 字段常被用作可替换的合法 writer；每次写入重读字段，避免缓存失效句柄。 */
    private static final class MethodHandleWriter implements DirectWriter {
        private final Field field;
        private final Class<?> inputType;
        private final HandleTarget target;
        private boolean associated;

        private MethodHandleWriter(Field field, Class<?> inputType, HandleTarget target) {
            this.field = field;
            this.inputType = inputType;
            this.target = target;
        }

        @Override public boolean write(LivingEntity entity, float value) {
            if (associated) return writeAssociated(entity, value);
            try {
                Object current = field.get(Modifier.isStatic(field.getModifiers()) ? null : entity);
                if (!(current instanceof MethodHandle handle)) return false;
                MethodType type = handle.type();
                if (type.parameterCount() != 2 || type.returnType() != void.class
                        || !type.parameterType(0).isAssignableFrom(entity.getClass())) return false;
                LivingEntityHook.beginProvisionalHealthWrite(entity, value);
                try {
                    handle.invokeWithArguments(entity, coerce(value, type.parameterType(1)));
                } finally {
                    LivingEntityHook.endProvisionalHealthWrite();
                }
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
        }

        @Override public float representable(float value) { return representableFor(value, inputType); }

        @Override public boolean writeAssociated(LivingEntity entity, float value) {
            return target != null && target.writes() != null
                    && ProtocolDataFlowEngine.writeAssociated(target.writes(), entity, value);
        }

        @Override public boolean hasAssociatedWrites() {
            return target != null && target.writes() != null;
        }

        @Override public void preferAssociatedWrites() {
            associated = true;
        }

        @Override public String describe() {
            String description = target == null ? "unresolved" : target.description();
            int sources = target == null || target.writes() == null ? 0 : target.writes().sources.size();
            return field.getDeclaringClass().getName() + "#" + field.getName()
                    + "(MethodHandle -> " + description + ", associatedSources=" + sources + ")";
        }
    }

    private record SynchedDataState(Map<Integer, Object> values) {
        @SuppressWarnings("rawtypes")
        private static SynchedDataState capture(LivingEntity entity) {
            Map<Integer, Object> values = new LinkedHashMap<>();
            Int2ObjectMap<?> items = (Int2ObjectMap<?>) entity.getEntityData().itemsById;
            for (Int2ObjectMap.Entry<?> entry : items.int2ObjectEntrySet()) {
                SynchedEntityData.DataItem item = (SynchedEntityData.DataItem) entry.getValue();
                if (item != null) values.put(entry.getIntKey(), item.value);
            }
            return new SynchedDataState(values);
        }

        private String diffFrom(SynchedDataState before) {
            if (before == null) return "unavailable";
            List<String> changes = new ArrayList<>();
            for (Map.Entry<Integer, Object> entry : values.entrySet()) {
                Object old = before.values.get(entry.getKey());
                if (!Objects.equals(old, entry.getValue())) {
                    changes.add(entry.getKey() + ":" + old + "->" + entry.getValue());
                }
            }
            return changes.isEmpty() ? "none" : changes.toString();
        }
    }

    private static final class VarHandleMethodHandleWriter implements DirectWriter {
        private final VarHandle field;
        private final boolean isStatic;
        private final Class<?> inputType;
        private final HandleTarget target;
        private boolean associated;

        private VarHandleMethodHandleWriter(VarHandle field, boolean isStatic, Class<?> inputType,
                                            HandleTarget target) {
            this.field = field;
            this.isStatic = isStatic;
            this.inputType = inputType;
            this.target = target;
        }

        @Override public boolean write(LivingEntity entity, float value) {
            if (associated) return writeAssociated(entity, value);
            try {
                Object current = isStatic ? field.get() : field.get(entity);
                if (!(current instanceof MethodHandle handle)) return false;
                MethodType type = handle.type();
                if (type.parameterCount() != 2 || type.returnType() != void.class
                        || !type.parameterType(0).isAssignableFrom(entity.getClass())) return false;
                LivingEntityHook.beginProvisionalHealthWrite(entity, value);
                try {
                    handle.invokeWithArguments(entity, coerce(value, type.parameterType(1)));
                } finally {
                    LivingEntityHook.endProvisionalHealthWrite();
                }
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
        }

        @Override public float representable(float value) { return representableFor(value, inputType); }

        @Override public boolean writeAssociated(LivingEntity entity, float value) {
            return target != null && target.writes() != null
                    && ProtocolDataFlowEngine.writeAssociated(target.writes(), entity, value);
        }

        @Override public boolean hasAssociatedWrites() {
            return target != null && target.writes() != null;
        }

        @Override public void preferAssociatedWrites() {
            associated = true;
        }

        @Override public String describe() {
            String description = target == null ? "unresolved" : target.description();
            int sources = target == null || target.writes() == null ? 0 : target.writes().sources.size();
            return "VarHandle MethodHandle writer -> " + description + ", associatedSources=" + sources;
        }
    }

    /* 暂存字段+无参提交：先写暂存字段，再调提交方法令实体自身完成加密/同步写入 */
    private static final class FieldCommitWriter implements DirectWriter {
        private final Field stagingField;
        private final Method commitMethod;

        private FieldCommitWriter(Field stagingField, Method commitMethod) {
            this.stagingField = stagingField;
            this.commitMethod = commitMethod;
        }

        @Override public boolean write(LivingEntity entity, float value) {
            try {
                if (stagingField.getType() == float.class) stagingField.setFloat(entity, value);
                else stagingField.setDouble(entity, value);
                commitMethod.invoke(entity);
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; ProtocolDiagnostics.reflectionFailure("write", t); return false; }
        }

        @Override public float representable(float value) {
            return representableFor(value, stagingField.getType());
        }

        @Override public String describe() {
            return stagingField.getDeclaringClass().getName() + "#" + stagingField.getName()
                    + " + " + commitMethod.getDeclaringClass().getSimpleName() + "#" + commitMethod.getName() + "()";
        }
    }

    private static Object coerce(float value, Class<?> type) {
        Object coerced = ProtocolDataflowAnalyzer.coerceForType(Float.valueOf(value), type);
        return coerced != null ? coerced : Float.valueOf(value);
    }

    /* 变长 SAM 的实参必须自行装成数组：其形参本身就是 Object[]，直接传数值会按零参调用命中读取分支。
       返回类型保持 Object，确保 Method.invoke 把它当作单个形参而非实参列表展开。 */
    private static Object samArgument(float value, Class<?> inputType) {
        if (inputType == Object[].class) return new Object[]{Float.valueOf(value)};
        return coerce(value, inputType);
    }

    private static float representableFor(float value, Class<?> type) {
        Object coerced = ProtocolDataflowAnalyzer.coerceForType(Float.valueOf(value), type);
        return coerced instanceof Number number ? number.floatValue() : value;
    }

    static float[] selectProbeValues(float baseline, float target, float maxHealth) {
        if (!Float.isFinite(baseline) || !Float.isFinite(target)) return null;
        boolean bounded = Float.isFinite(maxHealth) && maxHealth > 0.0f
                && baseline >= 0.0f && target >= 0.0f
                && baseline <= maxHealth && target <= maxHealth;
        float[] candidates = {
                baseline * 0.5f,
                baseline * 0.25f,
                (baseline + target) * 0.5f,
                baseline * 0.75f,
                target * 0.5f,
                bounded ? maxHealth * 0.75f : Float.NaN,
                bounded ? maxHealth * 0.25f : Float.NaN,
                baseline - Math.max(2.0f, Math.abs(baseline) * 0.5f),
                baseline + Math.max(2.0f, Math.abs(baseline) * 0.5f)
        };
        float[] selected = new float[2];
        int count = 0;
        for (float candidate : candidates) {
            if (!Float.isFinite(candidate)) continue;
            if (bounded && (candidate < 0.0f || candidate > maxHealth)) continue;
            if (tooClose(candidate, baseline) || tooClose(candidate, target)) continue;
            if (count > 0 && tooClose(candidate, selected[0])) continue;
            selected[count++] = candidate;
            if (count == selected.length) return selected;
        }
        return null;
    }

    private static float safeGetMaxHealth(LivingEntity entity) {
        try {
            float maxHealth = entity.getMaxHealth();
            return Float.isFinite(maxHealth) ? maxHealth : Float.NaN;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Float.NaN;
        }
    }

    private static boolean matches(float actual, float expected) {
        if (!Float.isFinite(actual)) return false;
        return ProtocolValueSemantics.matches(actual, expected);
    }

    /* 目标步专用校验(带死亡语义)：target≤0 是斩杀意图，writer 会把血量 clamp 到≥0(实际写成 0)，
       故实读≤0 即命中，不能拿负 target 做容差匹配；正值目标走普通容差。 */
    private static boolean matchesTarget(float actual, float expected) {
        if (!Float.isFinite(actual)) return false;
        if (expected <= 0.0f) return actual <= 0.0f;
        return matches(actual, expected);
    }

    private static boolean tooClose(float a, float b) {
        return Math.abs(a - b) < 1.0f;
    }
}



