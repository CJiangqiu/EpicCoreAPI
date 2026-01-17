package net.eca.agent.transform;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.ReturnToggle;
import net.eca.agent.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Inserts an early-return guard into void and boolean methods only.
 * When ReturnToggle is enabled, matching classes will return immediately.
 * Only hooks void (return) and boolean (return false) methods to avoid NPE crashes.
 */
public class AllReturnTransformer implements ITransformModule {
    private static final String CHECK_CLASS = "net/eca/agent/ReturnToggle";
    private static final String CHECK_METHOD = "shouldReturn";
    private static final String CHECK_DESC = "(Ljava/lang/String;)Z";

    @Override
    public String getName() {
        return "AllReturnTransformer";
    }

    @Override
    public boolean shouldTransform(String className, ClassLoader classLoader) {
        // 检查是否需要转换（explicitTargets 或 allowedPackagePrefixes）
        return ReturnToggle.shouldTransformClass(className);
    }

    @Override
    public byte[] transform(String className, byte[] classfileBuffer) {
        // shouldTransform 已经检查过了，这里直接转换
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            AllReturnClassVisitor cv = new AllReturnClassVisitor(cw, className);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);

            if (cv.transformed) {
                ReturnToggle.registerTransformedClass(className);
                AgentLogWriter.info("[AllReturnTransformer] Instrumented: " + className);
                return cw.toByteArray();
            }
            return null;
        } catch (Throwable t) {
            AgentLogWriter.error("[AllReturnTransformer] Failed to transform: " + className, t);
            return null;
        }
    }

    private static class AllReturnClassVisitor extends ClassVisitor {
        private final String className;
        private boolean transformed = false;

        AllReturnClassVisitor(ClassVisitor cv, String className) {
            super(Opcodes.ASM9, cv);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) {
                return null;
            }

            // 跳过构造函数和静态初始化块
            if (name.equals("<init>") || name.equals("<clinit>")) {
                return mv;
            }

            // 跳过抽象方法和native方法
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return mv;
            }

            // 只处理返回 void 或 boolean 的方法（避免返回 null 导致崩溃）
            Type returnType = Type.getReturnType(descriptor);
            int sort = returnType.getSort();
            if (sort != Type.VOID && sort != Type.BOOLEAN) {
                return mv;
            }

            transformed = true;
            return new AllReturnMethodVisitor(mv, className, descriptor);
        }
    }

    private static class AllReturnMethodVisitor extends MethodVisitor {
        private final String className;
        private final Type returnType;

        AllReturnMethodVisitor(MethodVisitor mv, String className, String descriptor) {
            super(Opcodes.ASM9, mv);
            this.className = className;
            this.returnType = Type.getReturnType(descriptor);
        }

        @Override
        public void visitCode() {
            super.visitCode();

            Label continueLabel = new Label();
            mv.visitLdcInsn(className);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CHECK_CLASS, CHECK_METHOD, CHECK_DESC, false);
            mv.visitJumpInsn(Opcodes.IFEQ, continueLabel);

            emitDefaultReturn();

            mv.visitLabel(continueLabel);
        }

        private void emitDefaultReturn() {
            switch (returnType.getSort()) {
                case Type.VOID:
                    mv.visitInsn(Opcodes.RETURN);
                    return;
                case Type.BOOLEAN:
                case Type.CHAR:
                case Type.BYTE:
                case Type.SHORT:
                case Type.INT:
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                    return;
                case Type.LONG:
                    mv.visitInsn(Opcodes.LCONST_0);
                    mv.visitInsn(Opcodes.LRETURN);
                    return;
                case Type.FLOAT:
                    mv.visitInsn(Opcodes.FCONST_0);
                    mv.visitInsn(Opcodes.FRETURN);
                    return;
                case Type.DOUBLE:
                    mv.visitInsn(Opcodes.DCONST_0);
                    mv.visitInsn(Opcodes.DRETURN);
                    return;
                case Type.ARRAY:
                case Type.OBJECT:
                default:
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitInsn(Opcodes.ARETURN);
            }
        }
    }
}
