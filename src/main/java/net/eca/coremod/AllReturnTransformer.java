package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Inserts early-return guards into void and boolean methods.
 * When AllReturnToggle is enabled, matching classes' methods will return immediately.
 * <p>
 * Injected bytecode uses System.getProperties().get() to avoid referencing
 * any net.eca class, preventing NoClassDefFoundError across classloader boundaries.
 */
final class AllReturnTransformer {

    private static final String CHECKER_KEY = AllReturnToggle.CHECKER_KEY;

    static byte[] transform(String className, byte[] classfileBuffer) {
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            // COMPUTE_MAXS 只算 max stack/locals，不碰 frame
            // 读取时用 0 flag 保留原始 StackMapTable 不变
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            GuardInjector cv = new GuardInjector(cw, className);
            cr.accept(cv, 0);

            if (cv.transformed) {
                AllReturnToggle.registerTransformed(className);
                return cw.toByteArray();
            }
            return null;
        } catch (Throwable t) {
            AgentLogWriter.error("[AllReturnTransformer] Failed: " + className, t);
            return null;
        }
    }

    private static class GuardInjector extends ClassVisitor {
        private final String className;
        boolean transformed = false;

        GuardInjector(ClassVisitor cv, String className) {
            super(Opcodes.ASM9, cv);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            if (mv == null) return null;
            if (name.equals("<init>") || name.equals("<clinit>")) return mv;
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return mv;

            Type returnType = Type.getReturnType(desc);
            int sort = returnType.getSort();
            if (sort != Type.VOID && sort != Type.BOOLEAN) return mv;

            transformed = true;
            return new GuardMethodVisitor(mv, className, returnType);
        }
    }

    private static class GuardMethodVisitor extends MethodVisitor {
        private final String className;
        private final Type returnType;

        GuardMethodVisitor(MethodVisitor mv, String className, Type returnType) {
            super(Opcodes.ASM9, mv);
            this.className = className;
            this.returnType = returnType;
        }

        /**
         * Injected bytecode equivalent:
         * <pre>
         * Object checker = System.getProperties().get("eca.allreturn.checker");
         * if (checker != null && ((Predicate) checker).test(className)) {
         *     return; // or return false;
         * }
         * </pre>
         * Only references JDK classes — no net.eca dependency in target bytecode.
         */
        @Override
        public void visitCode() {
            super.visitCode();

            Label continueLabel = new Label();
            Label nullLabel = new Label();

            // Object checker = System.getProperties().get(CHECKER_KEY)
            mv.visitLdcInsn(CHECKER_KEY);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties",
                    "()Ljava/util/Properties;", false);
            mv.visitInsn(Opcodes.SWAP);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);

            // if (checker == null) goto continue
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFNULL, nullLabel);

            // ((Predicate) checker).test(className)
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Predicate");
            mv.visitLdcInsn(className);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test",
                    "(Ljava/lang/Object;)Z", true);
            mv.visitJumpInsn(Opcodes.IFEQ, continueLabel);

            // return (or return false)
            if (returnType.getSort() == Type.BOOLEAN) {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
            } else {
                mv.visitInsn(Opcodes.RETURN);
            }

            // null path: pop the null and fall through
            mv.visitLabel(nullLabel);
            mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Object"});
            mv.visitInsn(Opcodes.POP);

            mv.visitLabel(continueLabel);
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
    }

    private AllReturnTransformer() {}
}
