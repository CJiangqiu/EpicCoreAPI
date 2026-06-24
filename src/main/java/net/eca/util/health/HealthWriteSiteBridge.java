package net.eca.util.health;

import net.eca.agent.EcaAgent;
import net.eca.util.EcaLogger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HealthWriteSiteBridge {

    private static final ThreadLocal<Object> ACTIVE_ENTITY = new ThreadLocal<>();
    private static final Map<String, BridgeSpec> SPECS = new ConcurrentHashMap<>();
    private static final String ENTITY = Type.getInternalName(Entity.class);
    private static final String BRIDGE = Type.getInternalName(HealthWriteSiteBridge.class);
    private static volatile boolean registered;

    private HealthWriteSiteBridge() {}

    public static EcaSetHealthManager.HealthPath resolvePath(LivingEntity entity, float target) {
        if (entity == null) return null;
        BridgeSpec spec = SPECS.get(Type.getInternalName(entity.getClass()));
        if (spec == null) {
            spec = analyze(entity.getClass());
            if (spec == null || !install(entity.getClass(), spec)) {
                return null;
            }
        }
        Method method = resolveMethod(entity.getClass(), spec);
        if (method == null) return null;
        BridgeSpec resolved = spec;
        return new EcaSetHealthManager.HealthPath(EcaSetHealthManager.WriteMethod.WRITE_SITE_BRIDGE,
                (currentEntity, currentTarget) -> invokeBridge(currentEntity, method, resolved, currentTarget));
    }

    public static boolean isBridgeActive(Object entity) {
        return entity != null && entity == ACTIVE_ENTITY.get();
    }

    private static boolean invokeBridge(LivingEntity entity, Method method, BridgeSpec spec, float target) {
        try {
            ACTIVE_ENTITY.set(entity);
            method.invoke(entity, coerce(target, method.getParameterTypes()[0]));
            boolean ok = EcaSetHealthManager.verify(entity, target);
            if (ok) {
                EcaLogger.info("[HealthWriteSiteBridge] succeeded entity={} method={} writer={}",
                        entity.getClass().getName(), method.getName(), spec.writer().owner() + "#" + spec.writer().name());
            }
            return ok;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[HealthWriteSiteBridge] invoke failed entity={} method={} msg={}",
                    entity.getClass().getName(), method.getName(), t.toString());
            return false;
        } finally {
            ACTIVE_ENTITY.remove();
        }
    }

    private static BridgeSpec analyze(Class<?> entityClass) {
        byte[] bytes = EcaSetHealthManager.classBytes(entityClass);
        if (bytes == null) return null;
        ClassNode node = new ClassNode(Opcodes.ASM9);
        try {
            new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }

        for (Method reflectionMethod : entityClass.getDeclaredMethods()) {
            if (!isCandidateMethod(reflectionMethod)) continue;
            String desc = Type.getMethodDescriptor(reflectionMethod);
            MethodNode methodNode = findMethodNode(node, reflectionMethod.getName(), desc);
            if (methodNode == null) continue;
            BridgeSpec spec = findBridgeSpec(node.name, methodNode);
            if (spec == null) continue;
            EcaLogger.info("[HealthWriteSiteBridge] resolved entity={} method={} writer={} token={}",
                    entityClass.getName(), reflectionMethod.getName(),
                    spec.writer().owner() + "#" + spec.writer().name(),
                    spec.token().owner() + "#" + spec.token().name());
            return spec;
        }
        return null;
    }

    private static boolean install(Class<?> entityClass, BridgeSpec spec) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null || !inst.isModifiableClass(entityClass)) {
            return false;
        }
        ensureRegistered(inst);
        String owner = Type.getInternalName(entityClass);
        SPECS.put(owner, spec);
        try {
            inst.retransformClasses(entityClass);
            return true;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            SPECS.remove(owner);
            EcaLogger.info("[HealthWriteSiteBridge] retransform failed entity={} msg={}",
                    entityClass.getName(), t.toString());
            return false;
        }
    }

    private static void ensureRegistered(Instrumentation inst) {
        if (registered) return;
        synchronized (HealthWriteSiteBridge.class) {
            if (registered) return;
            inst.addTransformer(new BridgeTransformer(), true);
            registered = true;
        }
    }

    private static Method resolveMethod(Class<?> entityClass, BridgeSpec spec) {
        for (Method method : entityClass.getDeclaredMethods()) {
            if (method.getName().equals(spec.methodName())
                    && Type.getMethodDescriptor(method).equals(spec.methodDesc())) {
                try {
                    method.setAccessible(true);
                    return method;
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean isCandidateMethod(Method method) {
        if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) return false;
        if (method.getReturnType() != void.class) return false;
        Class<?> input = method.getParameterTypes()[0];
        return input == float.class;
    }

    private static MethodNode findMethodNode(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    private static BridgeSpec findBridgeSpec(String owner, MethodNode method) {
        StaticCall token = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
            if (isTokenCall(call)) {
                token = new StaticCall(call.owner, call.name, call.desc);
                continue;
            }
            if (token != null && isWriterCall(call)) {
                return new BridgeSpec(owner, method.name, method.desc, token,
                        new StaticCall(call.owner, call.name, call.desc));
            }
        }
        return null;
    }

    private static boolean isTokenCall(MethodInsnNode call) {
        Type[] args = Type.getArgumentTypes(call.desc);
        return Type.getReturnType(call.desc).getSort() == Type.LONG
                && args.length == 1
                && args[0].getSort() == Type.OBJECT
                && ENTITY.equals(args[0].getInternalName());
    }

    private static boolean isWriterCall(MethodInsnNode call) {
        Type[] args = Type.getArgumentTypes(call.desc);
        return Type.getReturnType(call.desc).getSort() == Type.VOID
                && args.length == 3
                && args[0].getSort() == Type.OBJECT
                && ENTITY.equals(args[0].getInternalName())
                && args[1].getSort() == Type.FLOAT
                && args[2].getSort() == Type.LONG;
    }

    private static Object coerce(float value, Class<?> type) {
        if (type == float.class || type == Float.class) return value;
        return value;
    }

    private record StaticCall(String owner, String name, String desc) {}

    private record BridgeSpec(String owner, String methodName, String methodDesc, StaticCall token, StaticCall writer) {}

    private static final class BridgeTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            BridgeSpec spec = SPECS.get(className);
            if (spec == null) return null;
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                reader.accept(new BridgeClassVisitor(writer, spec), ClassReader.EXPAND_FRAMES);
                return writer.toByteArray();
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                EcaLogger.info("[HealthWriteSiteBridge] transform failed class={} msg={}", className, t.toString());
                return null;
            }
        }
    }

    private static final class BridgeClassVisitor extends ClassVisitor {
        private final BridgeSpec spec;

        private BridgeClassVisitor(ClassVisitor visitor, BridgeSpec spec) {
            super(Opcodes.ASM9, visitor);
            this.spec = spec;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!name.equals(spec.methodName()) || !descriptor.equals(spec.methodDesc())) {
                return visitor;
            }
            return new BridgeMethodVisitor(visitor, spec);
        }
    }

    private static final class BridgeMethodVisitor extends MethodVisitor {
        private final BridgeSpec spec;

        private BridgeMethodVisitor(MethodVisitor visitor, BridgeSpec spec) {
            super(Opcodes.ASM9, visitor);
            this.spec = spec;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            Label skip = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "isBridgeActive", "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, skip);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.CHECKCAST, ENTITY);
            mv.visitVarInsn(Opcodes.FLOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.CHECKCAST, ENTITY);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, spec.token().owner(), spec.token().name(), spec.token().desc(), false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, spec.writer().owner(), spec.writer().name(), spec.writer().desc(), false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(skip);
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
}
