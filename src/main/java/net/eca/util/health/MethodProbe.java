package net.eca.util.health;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.eca.coremod.EcaTransformerManager;
import net.eca.coremod.LivingEntityHook;
import net.eca.util.EcaLogger;
import net.eca.util.reflect.UnsafeUtil;
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
import org.objectweb.asm.tree.LdcInsnNode;
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
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 方法探针模块：数据流和外部扫描无法直接写入存储时，调用实体自身的血量写方法。
 * 三种策略：
 *  DirectCall：静态枚举单参数数值 setter 和函数式字段，通过写入、读取与回滚确定有效 writer。
 *  HeadBridge：扫 void(float) 方法体识别 token(entity):long + writer(entity,float,long):void 授权写模式，
 *              warmup 在方法 HEAD 注入授权调用(惰性)，运行期借实体自身可信帧发起、绕过后续栈守护/门控。
 *  ProtocolBridge：识别数值编码、命令生成与命令提交的连续事务，在原始方法帧内重放完整写入协议。
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
    private static final int MAX_FUNCTIONAL_ARGUMENT_PLANS = 64;
    private static final int MAX_PROTOCOL_COMBINATIONS = 64;
    private static final int MAX_EXTERNAL_PROTOCOL_CLASSES = 4096;
    private static final int MAX_EXTERNAL_PROTOCOL_SPECS = 128;

    // ==================== 模型 ====================

    /* 静态方法调用坐标：owner 内部名/方法名/描述符，供 HEAD 注入原样发起 token/writer 调用。 */
    public record StaticCall(String owner, String name, String desc) {}

    /* HeadBridge 注入规格：在 ownerInternal 的某 void(float) 方法 HEAD 注入
       token(this):long → writer(this,value,token):void。value 取该方法的 float 入参。 */
    public record BridgeSpec(String ownerInternal, String methodName, String methodDesc,
                             StaticCall token, StaticCall writer) {}

    public record StaticField(String owner, String name, String desc) {}

    public enum ProtocolInput { TARGET_FLOAT, CONTROL_ZERO }

    public record ProtocolBridgeSpec(String ownerInternal, String methodName, String methodDesc,
                                     boolean methodStatic, StaticCall encoder, StaticField marker,
                                     StaticCall producer, StaticCall consumer, ProtocolInput input) {}

    private record TrustedBridge(MethodHandle apply, String className) {}

    private static final class ProtocolActivation {
        private final Entity entity;
        private final float target;
        private boolean guardObserved;

        private ProtocolActivation(Entity entity, float target) {
            this.entity = entity;
            this.target = target;
        }

        private Entity entity() {
            return entity;
        }

        private float target() {
            return target;
        }
    }

    private static final class BridgeActivation {
        private final Entity entity;
        private boolean guardObserved;

        private BridgeActivation(Entity entity) {
            this.entity = entity;
        }
    }

    private record ProtocolInvocationResult(boolean success, String stage, Throwable failure) {}

    public enum WriterKind { METHOD, FUNCTIONAL_FIELD, METHOD_HANDLE_FIELD, FIELD_COMMIT }

    public enum AuxiliaryKind { FIELD_VALUE, ARRAY_LENGTH, COLLECTION_SIZE, MAP_SIZE, TEXT_LENGTH }

    public record AuxiliaryArgument(String declaringInternal, String fieldName, String fieldDesc,
                                    AuxiliaryKind kind) {}

    /* DirectCall 候选：METHOD=实体自身 1 参数数值方法；FUNCTIONAL_FIELD=持数值或变长 SAM 的函数式字段。
       此处仅记录静态签名，是否有效由运行期行为探测判定。 */
    public record DirectCandidate(WriterKind kind, String declaringInternal, String memberName, String inputDesc,
                                  String fieldDesc, boolean fieldStatic, AuxiliaryArgument auxiliary) {
        public DirectCandidate(WriterKind kind, String declaringInternal, String memberName, String inputDesc,
                               String fieldDesc, boolean fieldStatic) {
            this(kind, declaringInternal, memberName, inputDesc, fieldDesc, fieldStatic, null);
        }
    }

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

    static List<ProtocolBridgeSpec> findProtocolBridgeSpecs(Class<?> entityClass) {
        List<ProtocolBridgeSpec> out = new ArrayList<>();
        if (entityClass == null) return out;
        for (Class<?> c = entityClass; c != null && c != LivingEntity.class && c != Object.class; c = c.getSuperclass()) {
            out.addAll(findProtocolBridgeSpecsInClass(c));
        }
        return out;
    }

    private static List<ProtocolBridgeSpec> findProtocolBridgeSpecsInClass(Class<?> owner) {
        byte[] bytes = bytesProvider.get(owner);
        if (bytes == null) return List.of();
        List<ProtocolBridgeSpec> out = new ArrayList<>();
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
            for (MethodNode method : node.methods) {
                if (method.name.startsWith("<") || method.instructions == null) continue;
                ProtocolBridgeSpec spec = scanProtocolBridgeSpec(node.name, method);
                if (spec != null) out.add(spec);
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
        return out;
    }

    /* 同来源限制复用目标协议自身的信任边界，协议坐标只作为反向查找种子。 */
    private static List<ProtocolBridgeSpec> findExternalProtocolBridgeSpecs(
            Class<?> entityClass, List<ProtocolBridgeSpec> seeds) {
        if (entityClass == null || seeds == null || seeds.isEmpty()) return List.of();
        CodeSource source = codeSource(entityClass);
        if (source == null || source.getLocation() == null) return List.of();
        String cacheKey = externalProtocolCacheKey(entityClass, source, seeds);
        List<ProtocolBridgeSpec> cached = EXTERNAL_PROTOCOL_CACHE.get(cacheKey);
        if (cached != null) return cached;
        List<ProtocolBridgeSpec> scanned = scanExternalProtocolBridgeSpecs(entityClass, source, seeds);
        if (scanned.isEmpty()) return scanned;
        List<ProtocolBridgeSpec> raced = EXTERNAL_PROTOCOL_CACHE.putIfAbsent(cacheKey, scanned);
        return raced == null ? scanned : raced;
    }

    private static List<ProtocolBridgeSpec> scanExternalProtocolBridgeSpecs(
            Class<?> entityClass, CodeSource source, List<ProtocolBridgeSpec> seeds) {
        List<ProtocolBridgeSpec> matches = new ArrayList<>();
        int[] scanned = {0};
        EcaTransformerManager.forEachLoadedClass(owner -> {
            if (matches.size() >= MAX_EXTERNAL_PROTOCOL_SPECS
                    || scanned[0] >= MAX_EXTERNAL_PROTOCOL_CLASSES) return;
            if (owner == null || owner.isArray() || owner.isPrimitive()
                    || owner.getClassLoader() != entityClass.getClassLoader()
                    || !sameCodeSource(source, codeSource(owner))) return;
            scanned[0]++;
            for (ProtocolBridgeSpec candidate : findProtocolBridgeSpecsInClass(owner)) {
                if (matchesProtocolFamily(candidate, seeds) && !matches.contains(candidate)) {
                    matches.add(candidate);
                    if (matches.size() >= MAX_EXTERNAL_PROTOCOL_SPECS) break;
                }
            }
        });
        if (!EcaSetHealthManager.isWarmupDiagnosticsSuppressed()) {
            EcaLogger.info("[MethodProbe] external protocol scan classes={} candidates={}",
                    scanned[0], matches.size());
        }
        return List.copyOf(matches);
    }

    private static boolean matchesProtocolFamily(ProtocolBridgeSpec candidate,
                                                 List<ProtocolBridgeSpec> seeds) {
        for (ProtocolBridgeSpec seed : seeds) {
            if (candidate.producer().equals(seed.producer())
                    && candidate.consumer().equals(seed.consumer())) return true;
        }
        return false;
    }

    private static String externalProtocolCacheKey(Class<?> entityClass, CodeSource source,
                                                   List<ProtocolBridgeSpec> seeds) {
        List<String> families = new ArrayList<>();
        for (ProtocolBridgeSpec seed : seeds) {
            String family = seed.producer().owner() + "#" + seed.producer().name() + seed.producer().desc()
                    + "->" + seed.consumer().owner() + "#" + seed.consumer().name() + seed.consumer().desc();
            if (!families.contains(family)) families.add(family);
        }
        families.sort(String::compareTo);
        return loaderIdentity(entityClass.getClassLoader()) + "|" + source.getLocation() + "|"
                + String.join(";", families);
    }

    private static CodeSource codeSource(Class<?> type) {
        try {
            return type == null || type.getProtectionDomain() == null
                    ? null : type.getProtectionDomain().getCodeSource();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static boolean sameCodeSource(CodeSource first, CodeSource second) {
        return first != null && second != null
                && Objects.equals(first.getLocation(), second.getLocation());
    }

    private static ProtocolBridgeSpec scanProtocolBridgeSpec(String ownerInternal, MethodNode method) {
        ProtocolBridgeSpec control = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode consumer) || !isProtocolConsumer(consumer)) continue;
            AbstractInsnNode producerInsn = previousMeaningful(consumer);
            if (!(producerInsn instanceof MethodInsnNode producer) || !isProtocolProducer(producer)) continue;
            AbstractInsnNode markerInsn = previousMeaningful(producer);
            if (!(markerInsn instanceof FieldInsnNode marker) || marker.getOpcode() != Opcodes.GETSTATIC) continue;
            AbstractInsnNode encoderInsn = previousMeaningful(marker);
            StaticField markerField = new StaticField(marker.owner, marker.name, marker.desc);
            StaticCall producerCall = new StaticCall(producer.owner, producer.name, producer.desc);
            StaticCall consumerCall = new StaticCall(consumer.owner, consumer.name, consumer.desc);
            if (encoderInsn instanceof MethodInsnNode encoder && isProtocolEncoder(encoder)) {
                return new ProtocolBridgeSpec(ownerInternal, method.name, method.desc,
                        (method.access & Opcodes.ACC_STATIC) != 0,
                        new StaticCall(encoder.owner, encoder.name, encoder.desc), markerField,
                        producerCall, consumerCall, ProtocolInput.TARGET_FLOAT);
            }
            if (control == null) {
                control = new ProtocolBridgeSpec(ownerInternal, method.name, method.desc,
                        (method.access & Opcodes.ACC_STATIC) != 0, null, markerField,
                        producerCall, consumerCall, ProtocolInput.CONTROL_ZERO);
            }
        }
        return control;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode insn) {
        AbstractInsnNode current = insn == null ? null : insn.getPrevious();
        while (current != null && (current.getType() == AbstractInsnNode.LABEL
                || current.getType() == AbstractInsnNode.LINE || current.getType() == AbstractInsnNode.FRAME)) {
            current = current.getPrevious();
        }
        return current;
    }

    private static boolean isProtocolEncoder(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESTATIC) return false;
        Type[] args = Type.getArgumentTypes(call.desc);
        return args.length == 1 && args[0].getSort() == Type.FLOAT
                && Type.getReturnType(call.desc).getSort() == Type.INT;
    }

    private static boolean isProtocolProducer(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESTATIC) return false;
        Type[] args = Type.getArgumentTypes(call.desc);
        return args.length == 2 && args[0].getSort() == Type.INT && isReference(args[1])
                && isReference(Type.getReturnType(call.desc));
    }

    private static boolean isProtocolConsumer(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESTATIC) return false;
        Type[] args = Type.getArgumentTypes(call.desc);
        return Type.getReturnType(call.desc).getSort() == Type.VOID
                && args.length == 2 && args[0].getSort() == Type.OBJECT && isReference(args[1]);
    }

    private static boolean isReference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
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
                if (overridesUnsafeMinecraftMutation(method)) continue;
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
                addFunctionalCandidates(entityClass, ownerInternal, field, samInput, out, seen);
            }
            findBytecodeFieldCandidates(entityClass, c, ownerInternal, out, seen);
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

    /* 变长入口常以额外实例状态作为写入凭据；只枚举浅层、可重复求值的结构来源，最终仍由双点回读裁决。 */
    private static void addFunctionalCandidates(Class<?> entityClass, String ownerInternal, Field functionalField,
                                                Class<?> samInput, List<DirectCandidate> out, Set<String> seen) {
        String baseKey = "F:" + ownerInternal + ":" + functionalField.getName();
        if (seen.add(baseKey)) {
            out.add(new DirectCandidate(WriterKind.FUNCTIONAL_FIELD, ownerInternal, functionalField.getName(),
                    Type.getDescriptor(samInput), Type.getDescriptor(functionalField.getType()), false));
        }
        if (samInput != Object[].class) return;

        int plans = 0;
        List<Class<?>> sourceClasses = new ArrayList<>();
        sourceClasses.add(functionalField.getDeclaringClass());
        for (Class<?> c = entityClass; c != null && c != LivingEntity.class && c != Object.class; c = c.getSuperclass()) {
            if (!sourceClasses.contains(c)) sourceClasses.add(c);
        }
        for (Class<?> c : sourceClasses) {
            String sourceOwner = Type.getInternalName(c);
            for (Field source : c.getDeclaredFields()) {
                if (Modifier.isStatic(source.getModifiers()) || source.isSynthetic()) continue;
                AuxiliaryKind kind = auxiliaryKind(source.getType());
                if (kind == null) continue;
                String key = baseKey + ":" + sourceOwner + ":" + source.getName() + ":" + kind;
                if (!seen.add(key)) continue;
                AuxiliaryArgument auxiliary = new AuxiliaryArgument(sourceOwner, source.getName(),
                        Type.getDescriptor(source.getType()), kind);
                out.add(new DirectCandidate(WriterKind.FUNCTIONAL_FIELD, ownerInternal, functionalField.getName(),
                        Type.getDescriptor(samInput), Type.getDescriptor(functionalField.getType()), false, auxiliary));
                if (++plans >= MAX_FUNCTIONAL_ARGUMENT_PLANS) return;
            }
        }
    }

    private static AuxiliaryKind auxiliaryKind(Class<?> type) {
        if (type == null) return null;
        if (type.isArray()) return AuxiliaryKind.ARRAY_LENGTH;
        if (Collection.class.isAssignableFrom(type)) return AuxiliaryKind.COLLECTION_SIZE;
        if (Map.class.isAssignableFrom(type)) return AuxiliaryKind.MAP_SIZE;
        if (CharSequence.class.isAssignableFrom(type)) return AuxiliaryKind.TEXT_LENGTH;
        if (type.isPrimitive() || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class || type.isEnum()) {
            return AuxiliaryKind.FIELD_VALUE;
        }
        return null;
    }

    private static AuxiliaryKind auxiliaryKind(String descriptor) {
        Type type = Type.getType(descriptor);
        if (type.getSort() == Type.ARRAY) return AuxiliaryKind.ARRAY_LENGTH;
        if (type.getSort() >= Type.BOOLEAN && type.getSort() <= Type.DOUBLE) return AuxiliaryKind.FIELD_VALUE;
        if (type.getSort() != Type.OBJECT) return null;
        return auxiliaryKind(HealthDataflowAnalyzer.descriptorToClass(descriptor));
    }

    /* 基类数值 setter 操作的是实体身份、姿态或运行状态，不能用血量探针试写。
       setHealth 是唯一允许的基类覆写；其余自定义 writer 仍由运行期两点回读裁决。 */
    private static boolean overridesUnsafeMinecraftMutation(Method method) {
        if (isSetHealthMethod(method)) return false;
        Class<?>[] parameters = method.getParameterTypes();
        for (Class<?> c = LivingEntity.class; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(method.getName(), parameters);
                return true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return false;
    }

    private static boolean isSetHealthMethod(Method method) {
        if (method.getReturnType() != void.class || method.getParameterTypes()[0] != float.class) return false;
        return method.getName().equals("setHealth") || method.getName().equals("m_21153_");
    }

    /* 反射缓存可能被目标主动清空；字段元数据仍在 classfile 中，作为无反射后备。 */
    private static void findBytecodeFieldCandidates(Class<?> entityClass, Class<?> owner, String ownerInternal,
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
                Class<?> fieldType = HealthDataflowAnalyzer.descriptorToClass(field.desc);
                Class<?> input = singleNumericSamInput(fieldType);
                if (input == null || !seen.add("F:" + ownerInternal + ":" + field.name)) continue;
                out.add(new DirectCandidate(WriterKind.FUNCTIONAL_FIELD, ownerInternal, field.name,
                        Type.getDescriptor(input), field.desc, false));
                if (input == Object[].class) {
                    addBytecodeAuxiliaryCandidates(entityClass, owner, ownerInternal, field, input, out, seen);
                }
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
    }

    private static void addBytecodeAuxiliaryCandidates(Class<?> entityClass, Class<?> owner, String ownerInternal,
                                                       FieldNode functionalField, Class<?> samInput,
                                                       List<DirectCandidate> out, Set<String> seen) {
        String baseKey = "F:" + ownerInternal + ":" + functionalField.name;
        int plans = 0;
        for (AuxiliaryArgument auxiliary : findBytecodeAuxiliaryArguments(entityClass, owner)) {
            String key = baseKey + ":" + auxiliary.declaringInternal() + ":" + auxiliary.fieldName()
                    + ":" + auxiliary.kind();
            if (!seen.add(key)) continue;
            out.add(new DirectCandidate(WriterKind.FUNCTIONAL_FIELD, ownerInternal, functionalField.name,
                    Type.getDescriptor(samInput), functionalField.desc, false, auxiliary));
            if (++plans >= MAX_FUNCTIONAL_ARGUMENT_PLANS) return;
        }
    }

    static List<AuxiliaryArgument> findBytecodeAuxiliaryArguments(Class<?> entityClass, Class<?> preferredOwner) {
        List<AuxiliaryArgument> out = new ArrayList<>();
        List<Class<?>> sourceClasses = new ArrayList<>();
        sourceClasses.add(preferredOwner);
        for (Class<?> c = entityClass; c != null && c != LivingEntity.class && c != Object.class; c = c.getSuperclass()) {
            if (!sourceClasses.contains(c)) sourceClasses.add(c);
        }
        for (Class<?> c : sourceClasses) {
            byte[] bytes = bytesProvider.get(c);
            if (bytes == null) continue;
            try {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
                for (FieldNode field : node.fields) {
                    if ((field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) != 0) continue;
                    AuxiliaryKind kind = auxiliaryKind(field.desc);
                    if (kind == null) continue;
                    out.add(new AuxiliaryArgument(node.name, field.name, field.desc, kind));
                    if (out.size() >= MAX_FUNCTIONAL_ARGUMENT_PLANS) return out;
                }
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
            }
        }
        return out;
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
        Class<?> child = HealthDataflowAnalyzer.loadClass(childInternal);
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
    private static final Map<String, Set<ProtocolBridgeSpec>> PROTOCOL_SPECS = new ConcurrentHashMap<>();
    private static final Set<ProtocolBridgeSpec> READY_PROTOCOL_SPECS = ConcurrentHashMap.newKeySet();
    private static final Set<ProtocolBridgeSpec> TRANSFORMED_PROTOCOL_SPECS = ConcurrentHashMap.newKeySet();
    private static final Map<String, TrustedBridge> TRUSTED_BRIDGES = new ConcurrentHashMap<>();
    private static final Set<String> TRUSTED_BRIDGE_FAILED = ConcurrentHashMap.newKeySet();
    private static final Set<String> METHOD_HANDLE_DIAGNOSTICS = ConcurrentHashMap.newKeySet();
    private static final Set<String> PROTOCOL_BRIDGE_DIAGNOSTICS = ConcurrentHashMap.newKeySet();
    private static final Set<String> AGENT_RUNTIME_FAILED_OWNERS = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<ProtocolBridgeSpec>> EXTERNAL_PROTOCOL_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Object> EXTERNAL_PROTOCOL_RECEIVERS = new ConcurrentHashMap<>();
    private static final Set<Class<?>> EXTERNAL_PROTOCOL_RECEIVER_FAILED = ConcurrentHashMap.newKeySet();
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

    static void registerProtocolSite(ProtocolBridgeSpec spec, String lookupInternal) {
        if (spec == null || spec.ownerInternal() == null) return;
        PROTOCOL_SPECS.computeIfAbsent(spec.ownerInternal(), ignored -> ConcurrentHashMap.newKeySet()).add(spec);
        if (lookupInternal != null) {
            PROTOCOL_SPECS.computeIfAbsent(lookupInternal, ignored -> ConcurrentHashMap.newKeySet()).add(spec);
        }
    }

    public static List<ProtocolBridgeSpec> getProtocolSpecs(String classInternal) {
        if (classInternal == null) return List.of();
        Set<ProtocolBridgeSpec> specs = PROTOCOL_SPECS.get(classInternal);
        if (specs == null) return List.of();
        List<ProtocolBridgeSpec> ready = new ArrayList<>();
        for (ProtocolBridgeSpec spec : specs) {
            if (READY_PROTOCOL_SPECS.contains(spec)) ready.add(spec);
        }
        return ready;
    }

    public static boolean hasTransformSpecs(String classInternal) {
        if (classInternal == null) return false;
        BridgeSpec bridge = SPECS.get(classInternal);
        if (bridge != null && classInternal.equals(bridge.ownerInternal())) return true;
        Set<ProtocolBridgeSpec> protocols = PROTOCOL_SPECS.get(classInternal);
        if (protocols == null) return false;
        for (ProtocolBridgeSpec protocol : protocols) {
            if (classInternal.equals(protocol.ownerInternal())) return true;
        }
        return false;
    }

    public static boolean verifyTransform(String classInternal, byte[] bytes) {
        if (!hasTransformSpecs(classInternal) || bytes == null) return false;
        try {
            ClassNode owner = new ClassNode();
            new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
            BridgeSpec bridge = SPECS.get(classInternal);
            if (bridge != null && classInternal.equals(bridge.ownerInternal())) {
                MethodNode method = findMethodNode(owner, bridge.methodName(), bridge.methodDesc());
                if (!hasRuntimeCall(method, "isBridgeActive")) return false;
            }
            Set<ProtocolBridgeSpec> protocols = PROTOCOL_SPECS.get(classInternal);
            if (protocols != null) {
                for (ProtocolBridgeSpec protocol : protocols) {
                    if (!classInternal.equals(protocol.ownerInternal())) continue;
                    MethodNode method = findMethodNode(owner, protocol.methodName(), protocol.methodDesc());
                    if (!hasRuntimeCall(method, "protocolBridgeGuard")) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    /* 对登记类字节码注入 HEAD 桥；无规格返回 null。由 EcaClassTransformer.doTransform 链尾调用。 */
    public static byte[] transform(String classInternal, byte[] bytes) {
        BridgeSpec spec = SPECS.get(classInternal);
        Set<ProtocolBridgeSpec> protocolSpecs = PROTOCOL_SPECS.get(classInternal);
        if ((spec == null && (protocolSpecs == null || protocolSpecs.isEmpty())) || bytes == null) return null;
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.EXPAND_FRAMES);
            boolean changed = spec != null && injectLegacyBridge(cn, spec);
            List<ProtocolBridgeSpec> transformedProtocolSpecs = new ArrayList<>();
            if (protocolSpecs != null) {
                for (ProtocolBridgeSpec protocolSpec : protocolSpecs) {
                    if (!cn.name.equals(protocolSpec.ownerInternal())) continue;
                    boolean protocolChanged = injectProtocolBridge(cn, protocolSpec);
                    changed |= protocolChanged;
                    if (protocolChanged) transformedProtocolSpecs.add(protocolSpec);
                }
            }
            if (!changed) return null;

            ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            byte[] transformed = cw.toByteArray();
            TRANSFORMED_PROTOCOL_SPECS.addAll(transformedProtocolSpecs);
            return transformed;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    static boolean isProtocolSiteTransformed(ProtocolBridgeSpec spec) {
        return spec != null && TRANSFORMED_PROTOCOL_SPECS.contains(spec);
    }

    private static boolean injectLegacyBridge(ClassNode owner, BridgeSpec spec) {
        MethodNode method = findMethodNode(owner, spec.methodName(), spec.methodDesc());
        if (method == null || (method.access & Opcodes.ACC_STATIC) != 0
                || hasRuntimeCall(method, "isBridgeActive")) return false;
        InsnList prefix = new InsnList();
        LabelNode skip = new LabelNode();
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "isBridgeActive", "(Ljava/lang/Object;)Z", false));
        prefix.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new TypeInsnNode(Opcodes.CHECKCAST, ENTITY_INTERNAL));
        prefix.add(new VarInsnNode(Opcodes.FLOAD, 1));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new TypeInsnNode(Opcodes.CHECKCAST, ENTITY_INTERNAL));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, spec.token().owner(),
                spec.token().name(), spec.token().desc(), false));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, spec.writer().owner(),
                spec.writer().name(), spec.writer().desc(), false));
        prefix.add(new InsnNode(Opcodes.RETURN));
        prefix.add(skip);
        method.instructions.insert(prefix);
        return true;
    }

    private static boolean injectProtocolBridge(ClassNode owner, ProtocolBridgeSpec spec) {
        MethodNode method = findMethodNode(owner, spec.methodName(), spec.methodDesc());
        if (method == null || hasRuntimeCall(method, "protocolBridgeGuard")) return false;
        InsnList prefix = new InsnList();
        LabelNode skip = new LabelNode();
        prefix.add(new InsnNode(Opcodes.ACONST_NULL));
        prefix.add(new LdcInsnNode(spec.ownerInternal()));
        prefix.add(new LdcInsnNode(spec.methodName()));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "protocolBridgeGuard",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Z", false));
        prefix.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "protocolBridgeEntity",
                "()Lnet/minecraft/world/entity/Entity;", false));
        Type consumerEntity = Type.getArgumentTypes(spec.consumer().desc())[0];
        if (consumerEntity.getSort() == Type.OBJECT && !consumerEntity.getInternalName().equals(ENTITY_INTERNAL)) {
            prefix.add(new TypeInsnNode(Opcodes.CHECKCAST, consumerEntity.getInternalName()));
        }
        if (spec.input() == ProtocolInput.TARGET_FLOAT) {
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "protocolBridgeTarget", "()F", false));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, spec.encoder().owner(),
                    spec.encoder().name(), spec.encoder().desc(), false));
        } else {
            prefix.add(new InsnNode(Opcodes.ICONST_0));
        }
        prefix.add(new FieldInsnNode(Opcodes.GETSTATIC, spec.marker().owner(),
                spec.marker().name(), spec.marker().desc()));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, spec.producer().owner(),
                spec.producer().name(), spec.producer().desc(), false));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, spec.consumer().owner(),
                spec.consumer().name(), spec.consumer().desc(), false));
        appendDefaultReturn(prefix, Type.getReturnType(spec.methodDesc()));
        prefix.add(skip);
        method.instructions.insert(prefix);
        return true;
    }

    private static MethodNode findMethodNode(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static boolean hasRuntimeCall(MethodNode method, String name) {
        if (method == null || method.instructions == null) return false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                    && RUNTIME.equals(call.owner) && name.equals(call.name)) return true;
        }
        return false;
    }

    private static void appendDefaultReturn(InsnList instructions, Type returnType) {
        switch (returnType.getSort()) {
            case Type.VOID -> instructions.add(new InsnNode(Opcodes.RETURN));
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                instructions.add(new InsnNode(Opcodes.ICONST_0));
                instructions.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.FLOAT -> {
                instructions.add(new InsnNode(Opcodes.FCONST_0));
                instructions.add(new InsnNode(Opcodes.FRETURN));
            }
            case Type.LONG -> {
                instructions.add(new InsnNode(Opcodes.LCONST_0));
                instructions.add(new InsnNode(Opcodes.LRETURN));
            }
            case Type.DOUBLE -> {
                instructions.add(new InsnNode(Opcodes.DCONST_0));
                instructions.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                instructions.add(new InsnNode(Opcodes.ARETURN));
            }
        }
    }

    /* Register the bridge site and bake it through the active transform backend. */
    public static void installBridge(Class<?> entityClass) {
        if (entityClass == null) return;
        BridgeSpec spec = findBridgeSpec(entityClass);
        String lookupInternal = Type.getInternalName(entityClass);
        Set<Class<?>> owners = new HashSet<>();
        Map<Class<?>, List<ProtocolBridgeSpec>> protocolOwners = new LinkedHashMap<>();
        if (spec != null) {
            registerSite(spec, lookupInternal);
            Class<?> owner = HealthDataflowAnalyzer.loadClass(spec.ownerInternal());
            if (owner != null) owners.add(owner);
        }
        List<ProtocolBridgeSpec> protocolSpecs = findProtocolBridgeSpecs(entityClass);
        List<ProtocolBridgeSpec> allProtocolSpecs = new ArrayList<>(protocolSpecs);
        for (ProtocolBridgeSpec external : findExternalProtocolBridgeSpecs(entityClass, protocolSpecs)) {
            if (!allProtocolSpecs.contains(external)) allProtocolSpecs.add(external);
        }
        for (ProtocolBridgeSpec protocolSpec : allProtocolSpecs) {
            registerProtocolSite(protocolSpec, lookupInternal);
            Class<?> owner = HealthDataflowAnalyzer.loadClass(protocolSpec.ownerInternal());
            if (owner != null) {
                owners.add(owner);
                protocolOwners.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(protocolSpec);
            }
        }
        for (Class<?> owner : owners) {
            List<ProtocolBridgeSpec> requested = protocolOwners.get(owner);
            if (requested != null) {
                READY_PROTOCOL_SPECS.removeAll(requested);
                TRANSFORMED_PROTOCOL_SPECS.removeAll(requested);
            }
            try {
                EcaTransformerManager.HealthTransformResult result =
                        EcaTransformerManager.retransformHealthClass(owner, false);
                if (result.confirmed()) {
                    List<ProtocolBridgeSpec> installed = new ArrayList<>();
                    if (requested != null) {
                        installed.addAll(requested);
                    }
                    if (!installed.isEmpty()) {
                        READY_PROTOCOL_SPECS.addAll(installed);
                        if (!EcaSetHealthManager.isWarmupDiagnosticsSuppressed()) {
                            EcaLogger.info("[MethodProbe] protocol bridges installed owner={} candidates={}",
                                    owner.getName(), installed.size());
                        }
                    }
                } else {
                    String diagnosticKey = owner.getName() + "|transform-confirmation";
                    if (PROTOCOL_BRIDGE_DIAGNOSTICS.add(diagnosticKey)) {
                        EcaLogger.info("[MethodProbe] bridge transform not confirmed owner={} backend={}",
                                owner.getName(), result.backend());
                    }
                }
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                if (!EcaSetHealthManager.isWarmupDiagnosticsSuppressed())
                    EcaLogger.info("[MethodProbe] bridge retransform failed owner={} msg={}", owner.getName(), t.toString());
            }
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

    private static final ThreadLocal<BridgeActivation> ACTIVE_ENTITY = new ThreadLocal<>();
    private static final ThreadLocal<ProtocolActivation> ACTIVE_PROTOCOL = new ThreadLocal<>();

    // 由注入的 HEAD 字节码调用，签名稳定勿改：判断本实体当前是否处于桥激活态
    public static boolean isBridgeActive(Object entity) {
        BridgeActivation activation = ACTIVE_ENTITY.get();
        boolean active = entity != null && activation != null && entity == activation.entity;
        if (active) activation.guardObserved = true;
        return active;
    }

    public static boolean isProtocolBridgeActive() {
        return ACTIVE_PROTOCOL.get() != null;
    }

    public static boolean isProtocolBridgeActiveFor(Object entity) {
        ProtocolActivation activation = ACTIVE_PROTOCOL.get();
        return activation != null && entity == activation.entity();
    }

    public static boolean protocolBridgeGuard(Object entity, String owner, String method) {
        ProtocolActivation activation = ACTIVE_PROTOCOL.get();
        boolean active = activation != null;
        boolean sameEntity = active && (entity == null || entity == activation.entity());
        if (sameEntity) activation.guardObserved = true;
        String diagnosticKey = String.valueOf(owner) + "#" + method + "|guard|" + active + "|" + sameEntity;
        if (PROTOCOL_BRIDGE_DIAGNOSTICS.add(diagnosticKey)) {
            EcaLogger.info("[MethodProbe] protocol bridge guard owner={} method={} active={} sameEntity={} runtimeLoader={} entityLoader={} targetLoader={}",
                    owner, method, active, sameEntity, loaderIdentity(MethodProbe.class.getClassLoader()),
                    entity == null ? "unavailable" : loaderIdentity(entity.getClass().getClassLoader()),
                    active ? loaderIdentity(activation.entity().getClass().getClassLoader()) : "unavailable");
        }
        return sameEntity;
    }

    public static Entity protocolBridgeEntity() {
        ProtocolActivation activation = ACTIVE_PROTOCOL.get();
        return activation == null ? null : activation.entity();
    }

    public static float protocolBridgeTarget() {
        ProtocolActivation activation = ACTIVE_PROTOCOL.get();
        return activation == null ? Float.NaN : activation.target();
    }

    // ==================== DirectCall：行为探测 ====================

    /* 依次探测候选并返回首个通过血量校验的 writer；没有匹配项时返回 null。探测结束后恢复临时改动。 */
    public static DirectWriter resolveDirect(LivingEntity entity, List<DirectCandidate> candidates,
                                             float target, List<Object> rollbackRoots) {
        float baseline = EcaSetHealthManager.readHealthAnchor(entity);
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
            if (diagnostic) EcaLogger.info("[MethodProbe] MethodHandle candidate entity={} field={}#{} static={}",
                    entity.getClass().getName(), candidate.declaringInternal(), candidate.memberName(), candidate.fieldStatic());
            DirectWriter writer = bind(candidate, entity);
            if (writer == null) {
                if (diagnostic) EcaLogger.info("[MethodProbe] MethodHandle bind rejected field={}#{}",
                        candidate.declaringInternal(), candidate.memberName());
                continue;
            }
            if (diagnostic) EcaLogger.info("[MethodProbe] MethodHandle bound writer={} baseline={} probeA={} probeB={} target={}",
                    writer.describe(), baseline, probeA, probeB, target);
            if (writer.hasAssociatedWrites()) {
                ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.captureProbe(entity, rollbackRoots);
                if (testAssociatedWriter(entity, writer, baseline, probeA, probeB, target, diagnostic)) {
                    snapshot.restore();
                    writer.preferAssociatedWrites();
                    EcaLogger.info("[MethodProbe] associated writer hit entity={} writer={}",
                            entity.getClass().getName(), writer.describe());
                    return writer;
                }
                snapshot.restore();
            }
            ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.captureProbe(entity, rollbackRoots);
            if (testWriter(entity, writer, baseline, probeA, probeB, target, diagnostic)) {
                snapshot.restore();
                EcaLogger.info("[MethodProbe] direct writer hit entity={} writer={}",
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
            float actualA = EcaSetHealthManager.readHealthAnchor(entity);
            if (diagnostic) EcaLogger.info("[MethodProbe] associated probeA wrote={} expected={} actual={}",
                    wroteA, a, actualA);
            if (!wroteA || !matches(actualA, a)) return false;
            boolean wroteB = writer.writeAssociated(entity, b);
            float actualB = EcaSetHealthManager.readHealthAnchor(entity);
            if (diagnostic) EcaLogger.info("[MethodProbe] associated probeB wrote={} expected={} actual={}",
                    wroteB, b, actualB);
            if (!wroteB || !matches(actualB, b)) return false;
            // 与 testWriter 同理：两个不同写入分别被锚点读回，已证明锚点反映真实存储
            EcaSetHealthManager.promoteAnchorTrust(entity.getClass());
            if (!writer.writeAssociated(entity, baseline) || !EcaSetHealthManager.verify(entity, baseline)) return false;
            boolean wroteTarget = writer.writeAssociated(entity, target);
            float actualTarget = EcaSetHealthManager.readHealthAnchor(entity);
            if (diagnostic) EcaLogger.info("[MethodProbe] associated target wrote={} expected={} actual={}",
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
            float actualA = EcaSetHealthManager.readHealthAnchor(entity);
            if (diagnostic) {
                SynchedDataState afterReadA = SynchedDataState.capture(entity);
                EcaLogger.info("[MethodProbe] MethodHandle probeA wrote={} expected={} actual={} writeState={} readState={}",
                        wroteA, a, actualA, stateA.diffFrom(beforeA), afterReadA.diffFrom(stateA));
            }
            if (!wroteA || !matches(actualA, a)) {
                return false;
            }
            boolean wroteB = writer.write(entity, b);
            float actualB = EcaSetHealthManager.readHealthAnchor(entity);
            if (diagnostic) EcaLogger.info("[MethodProbe] MethodHandle probeB wrote={} expected={} actual={}", wroteB, b, actualB);
            if (!wroteB || !matches(actualB, b)) {
                return false;
            }
            /* 锚点分别读回了两个不同的写入值，已直接证明它反映真实存储。
               读自定义存储的 getHealth 不跟随原版字段，弱取证会误判其不可信，在此补正。 */
            EcaSetHealthManager.promoteAnchorTrust(entity.getClass());
            writer.write(entity, baseline);
            if (!EcaSetHealthManager.verify(entity, baseline)) {
                return false;
            }
            boolean wroteTarget = writer.write(entity, target);
            float actualTarget = EcaSetHealthManager.readHealthAnchor(entity);
            if (diagnostic) EcaLogger.info("[MethodProbe] MethodHandle target wrote={} expected={} actual={}",
                    wroteTarget, target, actualTarget);
            if (wroteTarget && matchesTarget(actualTarget, target)) return true;
            return false;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    private static DirectWriter bind(DirectCandidate candidate, LivingEntity entity) {
        Class<?> owner = HealthDataflowAnalyzer.loadClass(candidate.declaringInternal());
        if (owner == null) return null;
        try {
            if (candidate.kind() == WriterKind.FIELD_COMMIT) {
                return bindFieldCommit(candidate, owner);
            }
            if (candidate.kind() == WriterKind.METHOD_HANDLE_FIELD) {
                Field field = HealthDataflowAnalyzer.findFieldInHierarchy(owner, candidate.memberName());
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
            Class<?> inputType = HealthDataflowAnalyzer.descriptorToClass(candidate.inputDesc());
            if (inputType == null) return null;
            if (candidate.kind() == WriterKind.METHOD) {
                Method method = findMethod(owner, candidate.memberName(), inputType);
                if (method == null) return null;
                method.setAccessible(true);
                return new MethodWriter(method, inputType);
            }
            Field field = HealthDataflowAnalyzer.findFieldInHierarchy(owner, candidate.memberName());
            if (field == null) {
                Class<?> fieldType = HealthDataflowAnalyzer.descriptorToClass(candidate.fieldDesc());
                VarHandle handle = findVarHandle(owner, candidate, fieldType);
                Method sam = singleAbstract(fieldType);
                BoundAuxiliary auxiliary = bindAuxiliary(candidate.auxiliary());
                if (candidate.auxiliary() != null && auxiliary == null) return null;
                return handle == null || sam == null ? null
                        : new VarHandleFunctionalWriter(handle, candidate.fieldStatic(), sam, inputType, auxiliary);
            }
            Method sam = singleAbstract(field.getType());
            if (sam == null) return null;
            field.setAccessible(true);
            sam.setAccessible(true);
            BoundAuxiliary auxiliary = bindAuxiliary(candidate.auxiliary());
            if (candidate.auxiliary() != null && auxiliary == null) return null;
            return new FunctionalWriter(field, sam, inputType, auxiliary);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static BoundAuxiliary bindAuxiliary(AuxiliaryArgument argument) {
        if (argument == null) return null;
        Class<?> owner = HealthDataflowAnalyzer.loadClass(argument.declaringInternal());
        if (owner == null) return null;
        Class<?> fieldType = HealthDataflowAnalyzer.descriptorToClass(argument.fieldDesc());
        if (fieldType == null) return null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            VarHandle field = lookup.findVarHandle(owner, argument.fieldName(), fieldType);
            return new BoundAuxiliary(field, argument.kind());
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
        Field stagingField = HealthDataflowAnalyzer.findFieldInHierarchy(owner, candidate.fieldDesc());
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

    private record HandleTarget(String description, HealthDataflowAnalyzer.AnalysisResult writes) {}

    private static HandleTarget revealHandleTarget(MethodHandle handle, Class<?> owner) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            MethodHandleInfo info = lookup.revealDirect(handle);
            boolean isStatic = info.getReferenceKind() == MethodHandleInfo.REF_invokeStatic;
            String desc = info.getMethodType().toMethodDescriptorString();
            HealthDataflowAnalyzer.AnalysisResult writes = HealthDataflowAnalyzer.analyzeWriterMethod(
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
        Class<?> bridgeOwner = HealthDataflowAnalyzer.loadClass(spec.ownerInternal());
        if (bridgeOwner == null) return false;
        if (AGENT_RUNTIME_FAILED_OWNERS.contains(spec.ownerInternal())) {
            if (!EcaTransformerManager.isHealthTransformConfirmed(
                    bridgeOwner, EcaTransformerManager.Backend.JVMTI)
                    && !EcaTransformerManager.retransformHealthClassWithJvmTi(bridgeOwner).confirmed()) return false;
        } else if (!EcaTransformerManager.isHealthTransformConfirmed(bridgeOwner)
                && !EcaTransformerManager.retransformHealthClass(bridgeOwner, true).confirmed()) {
            return false;
        }
        ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.captureProbe(entity, rollbackRoots);
        float baseline = EcaSetHealthManager.readHealthAnchor(entity);
        try {
            BridgeActivation activation = new BridgeActivation(entity);
            ACTIVE_ENTITY.set(activation);
            method.invoke(entity, target);
            if (!activation.guardObserved) {
                snapshot.restore();
                Class<?> owner = HealthDataflowAnalyzer.loadClass(spec.ownerInternal());
                EcaTransformerManager.HealthTransformResult reinstall = owner == null
                        ? new EcaTransformerManager.HealthTransformResult(
                                EcaTransformerManager.Backend.NONE, false)
                        : AGENT_RUNTIME_FAILED_OWNERS.contains(spec.ownerInternal())
                                ? EcaTransformerManager.retransformHealthClassWithJvmTi(owner)
                                : EcaTransformerManager.retransformHealthClass(owner, true);
                if (!reinstall.confirmed()) return false;
                snapshot = ObjectGraphSnapshot.captureProbe(entity, rollbackRoots);
                activation = new BridgeActivation(entity);
                ACTIVE_ENTITY.set(activation);
                method.invoke(entity, target);
                if (!activation.guardObserved) {
                    if (reinstall.backend() != EcaTransformerManager.Backend.AGENT) {
                        snapshot.restore();
                        return false;
                    }
                    AGENT_RUNTIME_FAILED_OWNERS.add(spec.ownerInternal());
                    snapshot.restore();
                    reinstall = EcaTransformerManager.retransformHealthClassWithJvmTi(bridgeOwner);
                    if (!reinstall.confirmed()) return false;
                    snapshot = ObjectGraphSnapshot.captureProbe(entity, rollbackRoots);
                    activation = new BridgeActivation(entity);
                    ACTIVE_ENTITY.set(activation);
                    method.invoke(entity, target);
                    if (!activation.guardObserved) {
                        snapshot.restore();
                        return false;
                    }
                }
            }
            EcaSetHealthManager.noteAnchorResponse(entity, baseline, target);
            boolean ok = EcaSetHealthManager.verify(entity, target);
            if (ok) EcaLogger.info("[MethodProbe] head bridge hit entity={} method={}",
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

    public static boolean invokeProtocolBridges(LivingEntity entity, List<ProtocolBridgeSpec> specs,
                                                float target, List<Object> rollbackRoots) {
        if (entity == null || specs == null || specs.isEmpty()) return false;
        String activationKey = entity.getClass().getName() + "|activation-runtime";
        if (PROTOCOL_BRIDGE_DIAGNOSTICS.add(activationKey)) {
            EcaLogger.info("[MethodProbe] protocol bridge activation entity={} runtimeLoader={} entityLoader={}",
                    entity.getClass().getName(), loaderIdentity(MethodProbe.class.getClassLoader()),
                    loaderIdentity(entity.getClass().getClassLoader()));
        }
        float baseline = EcaSetHealthManager.readHealthAnchor(entity);
        Set<ProtocolBridgeSpec> runtimeReady = new HashSet<>();
        for (ProtocolBridgeSpec spec : specs) {
            String diagnosticKey = entity.getClass().getName() + "|" + spec.ownerInternal()
                    + "#" + spec.methodName() + spec.methodDesc();
            Class<?> specOwner = HealthDataflowAnalyzer.loadClass(spec.ownerInternal());
            if (specOwner == null) continue;
            boolean agentRuntimeFailed = AGENT_RUNTIME_FAILED_OWNERS.contains(spec.ownerInternal());
            if (agentRuntimeFailed) {
                if (!EcaTransformerManager.isHealthTransformConfirmed(
                        specOwner, EcaTransformerManager.Backend.JVMTI)
                        && !EcaTransformerManager.retransformHealthClassWithJvmTi(specOwner).confirmed()) continue;
            } else if (!EcaTransformerManager.isHealthTransformConfirmed(specOwner)
                    && !EcaTransformerManager.retransformHealthClass(specOwner, true).confirmed()) {
                continue;
            }
            ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.captureProbe(entity, rollbackRoots);
            try {
                ProtocolActivation activation = new ProtocolActivation(entity, target);
                ACTIVE_PROTOCOL.set(activation);
                ProtocolInvocationResult invocation = invokeProtocolMethod(entity, spec);
                if (!activation.guardObserved) {
                    snapshot.restore();
                    Class<?> owner = HealthDataflowAnalyzer.loadClass(spec.ownerInternal());
                    EcaTransformerManager.HealthTransformResult reinstall = owner == null
                            ? new EcaTransformerManager.HealthTransformResult(
                                    EcaTransformerManager.Backend.NONE, false)
                            : AGENT_RUNTIME_FAILED_OWNERS.contains(spec.ownerInternal())
                                    ? EcaTransformerManager.retransformHealthClassWithJvmTi(owner)
                                    : EcaTransformerManager.retransformHealthClass(owner, true);
                    if (reinstall.confirmed()) {
                        snapshot = ObjectGraphSnapshot.captureProbe(entity, rollbackRoots);
                        activation = new ProtocolActivation(entity, target);
                        ACTIVE_PROTOCOL.set(activation);
                        invocation = invokeProtocolMethod(entity, spec);
                    }
                    if (reinstall.confirmed() && !activation.guardObserved
                            && reinstall.backend() == EcaTransformerManager.Backend.AGENT) {
                        AGENT_RUNTIME_FAILED_OWNERS.add(spec.ownerInternal());
                        snapshot.restore();
                        EcaTransformerManager.HealthTransformResult jvmTiInstall = owner == null
                                ? new EcaTransformerManager.HealthTransformResult(
                                        EcaTransformerManager.Backend.NONE, false)
                                : EcaTransformerManager.retransformHealthClassWithJvmTi(owner);
                        reinstall = jvmTiInstall;
                        if (jvmTiInstall.confirmed()) {
                            snapshot = ObjectGraphSnapshot.captureProbe(entity, rollbackRoots);
                            activation = new ProtocolActivation(entity, target);
                            ACTIVE_PROTOCOL.set(activation);
                            invocation = invokeProtocolMethod(entity, spec);
                        }
                    }
                    if (!reinstall.confirmed() || !activation.guardObserved) {
                        String reinstallKey = diagnosticKey + "|runtime-reinstall";
                        if (PROTOCOL_BRIDGE_DIAGNOSTICS.add(reinstallKey)) {
                            EcaLogger.info("[MethodProbe] protocol bridge runtime receipt missing entity={} owner={} method={} backend={} confirmed={}",
                                    entity.getClass().getName(), spec.ownerInternal(), spec.methodName(),
                                    reinstall.backend(), reinstall.confirmed());
                        }
                        snapshot.restore();
                        continue;
                    }
                }
                if (!invocation.success()) {
                    if (PROTOCOL_BRIDGE_DIAGNOSTICS.add(diagnosticKey + "|invoke")) {
                        EcaLogger.info("[MethodProbe] protocol bridge invocation rejected entity={} owner={} method={} desc={} stage={} cause={}",
                                entity.getClass().getName(), spec.ownerInternal(), spec.methodName(), spec.methodDesc(),
                                invocation.stage(), failureSummary(invocation.failure()));
                    }
                    snapshot.restore();
                    continue;
                }
                runtimeReady.add(spec);
                EcaSetHealthManager.noteAnchorResponse(entity, baseline, target);
                if (EcaSetHealthManager.verify(entity, target)) {
                    EcaLogger.info("[MethodProbe] protocol bridge hit entity={} method={}",
                            entity.getClass().getName(), spec.methodName());
                    return true;
                }
                if (PROTOCOL_BRIDGE_DIAGNOSTICS.add(diagnosticKey + "|verify")) {
                    EcaLogger.info("[MethodProbe] protocol bridge not observed entity={} method={} target={} actual={}",
                            entity.getClass().getName(), spec.methodName(), target,
                            EcaSetHealthManager.readHealthAnchor(entity));
                }
                snapshot.restore();
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                snapshot.restore();
            } finally {
                ACTIVE_PROTOCOL.remove();
            }
        }
        return invokeProtocolCombinations(entity, specs, runtimeReady, target, rollbackRoots, baseline);
    }

    /* 前置控制和目标写入必须共享事务，单独回滚会重新建立刚解除的门控。 */
    private static boolean invokeProtocolCombinations(LivingEntity entity, List<ProtocolBridgeSpec> specs,
                                                      Set<ProtocolBridgeSpec> runtimeReady, float target,
                                                      List<Object> rollbackRoots, float baseline) {
        List<ProtocolBridgeSpec> controls = new ArrayList<>();
        List<ProtocolBridgeSpec> writers = new ArrayList<>();
        for (ProtocolBridgeSpec spec : specs) {
            if (!runtimeReady.contains(spec)) continue;
            if (spec.input() == ProtocolInput.CONTROL_ZERO) controls.add(spec);
            if (spec.input() == ProtocolInput.TARGET_FLOAT) writers.add(spec);
        }
        int attempts = 0;
        for (ProtocolBridgeSpec control : controls) {
            for (ProtocolBridgeSpec writer : writers) {
                if (++attempts > MAX_PROTOCOL_COMBINATIONS) return false;
                ObjectGraphSnapshot snapshot = ObjectGraphSnapshot.captureProbe(entity, rollbackRoots);
                boolean committed = false;
                try {
                    ProtocolActivation controlActivation = new ProtocolActivation(entity, target);
                    ACTIVE_PROTOCOL.set(controlActivation);
                    ProtocolInvocationResult controlResult = invokeProtocolMethod(entity, control);
                    if (!controlResult.success() || !controlActivation.guardObserved) continue;

                    ProtocolActivation writerActivation = new ProtocolActivation(entity, target);
                    ACTIVE_PROTOCOL.set(writerActivation);
                    ProtocolInvocationResult writerResult = invokeProtocolMethod(entity, writer);
                    if (!writerResult.success() || !writerActivation.guardObserved) continue;

                    EcaSetHealthManager.noteAnchorResponse(entity, baseline, target);
                    if (EcaSetHealthManager.verify(entity, target)) {
                        committed = true;
                        EcaLogger.info("[MethodProbe] protocol bridge composition hit entity={} control={} writer={}",
                                entity.getClass().getName(), control.methodName(), writer.methodName());
                        return true;
                    }
                    String diagnosticKey = entity.getClass().getName() + "|composition|"
                            + control.ownerInternal() + "#" + control.methodName() + "|"
                            + writer.ownerInternal() + "#" + writer.methodName();
                    if (PROTOCOL_BRIDGE_DIAGNOSTICS.add(diagnosticKey)) {
                        EcaLogger.info("[MethodProbe] protocol bridge composition not observed entity={} control={} writer={} target={} actual={}",
                                entity.getClass().getName(), control.methodName(), writer.methodName(), target,
                                EcaSetHealthManager.readHealthAnchor(entity));
                    }
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                } finally {
                    ACTIVE_PROTOCOL.remove();
                    if (!committed) snapshot.restore();
                }
            }
        }
        return false;
    }

    private static ProtocolInvocationResult invokeProtocolMethod(LivingEntity entity, ProtocolBridgeSpec spec) {
        Class<?> owner = HealthDataflowAnalyzer.loadClass(spec.ownerInternal());
        if (owner == null) return new ProtocolInvocationResult(false, "owner-resolution", null);
        MethodType type;
        MethodHandle handle;
        try {
            type = MethodType.fromMethodDescriptorString(spec.methodDesc(), owner.getClassLoader());
            handle = resolveProtocolMethod(owner, spec, type);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return new ProtocolInvocationResult(false, "handle-resolution", t);
        }
        try {
            List<Object> arguments = new ArrayList<>();
            if (!spec.methodStatic()) {
                Object receiver = owner.isInstance(entity) ? entity : externalProtocolReceiver(owner);
                if (receiver == null) return new ProtocolInvocationResult(false, "receiver-resolution", null);
                arguments.add(receiver);
            }
            for (Class<?> parameter : type.parameterArray()) arguments.add(defaultValue(parameter));
            handle.invokeWithArguments(arguments);
            return new ProtocolInvocationResult(true, "complete", null);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return new ProtocolInvocationResult(false, "execution", t);
        }
    }

    private static Object externalProtocolReceiver(Class<?> owner) {
        Object existing = EXTERNAL_PROTOCOL_RECEIVERS.get(owner);
        if (existing != null) return existing;
        if (owner == null || owner.isInterface() || Modifier.isAbstract(owner.getModifiers())
                || EXTERNAL_PROTOCOL_RECEIVER_FAILED.contains(owner)) return null;
        Object created = UnsafeUtil.lwjglAllocateInstance(owner);
        if (created == null) {
            EXTERNAL_PROTOCOL_RECEIVER_FAILED.add(owner);
            return null;
        }
        Object raced = EXTERNAL_PROTOCOL_RECEIVERS.putIfAbsent(owner, created);
        return raced == null ? created : raced;
    }

    static MethodHandle resolveProtocolMethod(Class<?> owner, ProtocolBridgeSpec spec, MethodType type)
            throws IllegalAccessException, NoSuchMethodException {
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
        return spec.methodStatic()
                ? lookup.findStatic(owner, spec.methodName(), type)
                : lookup.findSpecial(owner, spec.methodName(), type, owner);
    }

    private static String failureSummary(Throwable failure) {
        if (failure == null) return "unavailable";
        Throwable root = failure;
        Set<Throwable> visited = new HashSet<>();
        while (root.getCause() != null && visited.add(root)) root = root.getCause();
        return root.toString();
    }

    private static String loaderIdentity(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    public static boolean invokeTrustedBridge(LivingEntity entity, BridgeSpec spec, float target) {
        if (entity == null || spec == null) return false;
        TrustedBridge bridge = trustedBridge(spec);
        if (bridge == null) return false;
        float baseline = EcaSetHealthManager.readHealthAnchor(entity);
        try {
            bridge.apply().invokeExact((Entity) entity, target);
            // 桥没有两点探测，改用写入前后的锚点位移取证，否则读自定义存储的 getHealth 会被弱取证永久判死
            EcaSetHealthManager.noteAnchorResponse(entity, baseline, target);
            if (EcaSetHealthManager.verify(entity, target)) {
                EcaLogger.info("[MethodProbe] trusted bridge hit entity={} bridge={}",
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
                EcaLogger.info("[MethodProbe] trusted bridge unavailable owner={} msg={}",
                        spec.ownerInternal(), t.toString());
            }
            return null;
        }
    }

    private static TrustedBridge createTrustedBridge(BridgeSpec spec, String key) throws Throwable {
        Class<?> owner = HealthDataflowAnalyzer.loadClass(spec.ownerInternal());
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
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
        }

        @Override public float representable(float value) { return representableFor(value, inputType); }

        @Override public String describe() { return method.getDeclaringClass().getName() + "#" + method.getName(); }
    }

    private static final class FunctionalWriter implements DirectWriter {
        private final Field field;
        private final Method sam;
        private final Class<?> inputType;
        private final BoundAuxiliary auxiliary;

        private FunctionalWriter(Field field, Method sam, Class<?> inputType, BoundAuxiliary auxiliary) {
            this.field = field;
            this.sam = sam;
            this.inputType = inputType;
            this.auxiliary = auxiliary;
        }

        @Override public boolean write(LivingEntity entity, float value) {
            try {
                Object function = field.get(entity);
                if (function == null) return false;
                sam.invoke(function, samArgument(entity, value, inputType, auxiliary));
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
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
        private final BoundAuxiliary auxiliary;

        private VarHandleFunctionalWriter(VarHandle field, boolean isStatic, Method sam, Class<?> inputType,
                                          BoundAuxiliary auxiliary) {
            this.field = field;
            this.isStatic = isStatic;
            this.sam = sam;
            this.inputType = inputType;
            this.auxiliary = auxiliary;
        }

        @Override public boolean write(LivingEntity entity, float value) {
            try {
                Object function = isStatic ? field.get() : field.get(entity);
                if (function == null) return false;
                sam.invoke(function, samArgument(entity, value, inputType, auxiliary));
                return true;
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
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
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
        }

        @Override public float representable(float value) { return representableFor(value, inputType); }

        @Override public boolean writeAssociated(LivingEntity entity, float value) {
            return target != null && target.writes() != null
                    && HealthDataFlow.writeAssociated(target.writes(), entity, value);
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
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
        }

        @Override public float representable(float value) { return representableFor(value, inputType); }

        @Override public boolean writeAssociated(LivingEntity entity, float value) {
            return target != null && target.writes() != null
                    && HealthDataFlow.writeAssociated(target.writes(), entity, value);
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
            } catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; return false; }
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
        Object coerced = HealthDataflowAnalyzer.coerceForType(Float.valueOf(value), type);
        return coerced != null ? coerced : Float.valueOf(value);
    }

    private record BoundAuxiliary(VarHandle field, AuxiliaryKind kind) {
        private Object read(Object owner) {
            Object value = field.get(owner);
            if (value == null) return null;
            return switch (kind) {
                case FIELD_VALUE -> value;
                case ARRAY_LENGTH -> Array.getLength(value);
                case COLLECTION_SIZE -> ((Collection<?>) value).size();
                case MAP_SIZE -> ((Map<?, ?>) value).size();
                case TEXT_LENGTH -> ((CharSequence) value).length();
            };
        }
    }

    /* 变长 SAM 的实参必须自行装成数组：其形参本身就是 Object[]，直接传数值会按零参调用命中读取分支。
       返回类型保持 Object，确保 Method.invoke 把它当作单个形参而非实参列表展开。 */
    private static Object samArgument(LivingEntity entity, float value, Class<?> inputType,
                                      BoundAuxiliary auxiliary) throws IllegalAccessException {
        if (inputType == Object[].class) {
            if (auxiliary != null) return new Object[]{Float.valueOf(value), auxiliary.read(entity)};
            return new Object[]{Float.valueOf(value)};
        }
        return coerce(value, inputType);
    }

    private static float representableFor(float value, Class<?> type) {
        Object coerced = HealthDataflowAnalyzer.coerceForType(Float.valueOf(value), type);
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
        return HealthValueSemantics.matches(actual, expected);
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
