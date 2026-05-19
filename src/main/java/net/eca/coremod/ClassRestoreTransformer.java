package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Map;

/**
 * Injects per-instance restore guards into custom entity lifecycle methods.
 * <p>
 * For each overridden lifecycle method, a head guard is injected:
 * <pre>
 * if (EcaRestoreHook.isRestored(this.getUUID())) {
 *     return super.&lt;method&gt;(args);
 * }
 * </pre>
 * When the whole custom class chain is guarded, a restored instance delegates
 * level-by-level until it reaches the vanilla {@code LivingEntity} implementation,
 * so no vanilla logic needs to be re-implemented.
 */
final class ClassRestoreTransformer {

    private static final String HOOK = "net/eca/coremod/EcaRestoreHook";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String GET_UUID = "m_20148_";

    // SRG 名 → 描述符：被还原拦截的关键生命周期方法
    private static final Map<String, String> TARGET_METHODS = Map.ofEntries(
        Map.entry("m_21223_", "()F"),                                                          // getHealth
        Map.entry("m_21233_", "()F"),                                                          // getMaxHealth
        Map.entry("m_21153_", "(F)V"),                                                         // setHealth
        Map.entry("m_6469_",  "(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),          // hurt
        Map.entry("m_6475_",  "(Lnet/minecraft/world/damagesource/DamageSource;F)V"),          // actuallyHurt
        Map.entry("m_6667_",  "(Lnet/minecraft/world/damagesource/DamageSource;)V"),           // die
        Map.entry("m_6153_",  "()V"),                                                          // tickDeath
        Map.entry("m_6084_",  "()Z"),                                                          // isAlive
        Map.entry("m_21224_", "()Z"),                                                          // isDeadOrDying
        Map.entry("m_213877_", "()Z")                                                          // isRemoved
    );

    private ClassRestoreTransformer() {}

    static boolean isTargetMethod(String name, String desc) {
        return desc.equals(TARGET_METHODS.get(name));
    }

    static byte[] transform(String internalClassName, byte[] classfileBuffer) {
        if (!RestoreManager.isTargetClass(internalClassName)) return null;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new EcaClassTransformer.SafeClassWriter(cr,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            RestoreInjector injector = new RestoreInjector(cw);
            cr.accept(injector, ClassReader.EXPAND_FRAMES);
            if (!injector.transformed) return null;
            AgentLogWriter.info("[ClassRestoreTransformer] Restored: " + internalClassName);
            return cw.toByteArray();
        } catch (Throwable t) {
            AgentLogWriter.error("[ClassRestoreTransformer] Failed: " + internalClassName, t);
            return null;
        }
    }

    private static final class RestoreInjector extends ClassVisitor {
        private String superName;
        boolean transformed = false;

        RestoreInjector(ClassWriter cw) {
            super(Opcodes.ASM9, cw);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.superName = superName;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            if (mv == null) return null;
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0) return mv;
            if (!isTargetMethod(name, desc)) return mv;

            transformed = true;
            return new RestoreMethodVisitor(mv, superName, name, desc);
        }
    }

    private static final class RestoreMethodVisitor extends MethodVisitor {
        private final String superName;
        private final String methodName;
        private final String methodDesc;

        RestoreMethodVisitor(MethodVisitor mv, String superName, String methodName, String methodDesc) {
            super(Opcodes.ASM9, mv);
            this.superName = superName;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            Label skip = new Label();

            // if (!EcaRestoreHook.isRestored(this.getUUID())) goto skip
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, GET_UUID, "()Ljava/util/UUID;", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "isRestored", "(Ljava/util/UUID;)Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, skip);

            // return super.<method>(args)
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            Type[] argTypes = Type.getArgumentTypes(methodDesc);
            int local = 1;
            for (Type at : argTypes) {
                mv.visitVarInsn(at.getOpcode(Opcodes.ILOAD), local);
                local += at.getSize();
            }
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, methodName, methodDesc, false);
            mv.visitInsn(Type.getReturnType(methodDesc).getOpcode(Opcodes.IRETURN));

            mv.visitLabel(skip);
        }
    }
}
