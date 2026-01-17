package net.eca.agent.transform;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.ReturnToggle;
import net.eca.agent.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
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

    // 标签字段名（用于验证转换是否被拦截）
    private static final String MARK_FIELD_NAME = "__ECA_ALLRETURN_MARK__";

    // 第一个被转换的类名（用于验证）
    private static volatile String firstTransformedClass = null;

    /**
     * 获取第一个被转换的类名
     * @return 第一个被转换的类名，如果没有则返回null
     */
    public static String getFirstTransformed() {
        return firstTransformedClass;
    }

    @Override
    public String getName() {
        return "AllReturnTransformer";
    }

    @Override
    public String getMarkFieldName() {
        return MARK_FIELD_NAME;
    }

    @Override
    public String getFirstTransformedClass() {
        return firstTransformedClass;
    }

    @Override
    public boolean requiresVerification() {
        // AllReturn只有在转换过类时才需要验证
        // 如果没有其他mod的类被转换，就不需要验证
        return firstTransformedClass != null;
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

            // 判断是否是第一个转换的类（需要注入标签）
            boolean isFirstClass = (firstTransformedClass == null);

            AllReturnClassVisitor cv = new AllReturnClassVisitor(cw, className, isFirstClass);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);

            if (cv.transformed) {
                // 记录第一个被转换的类
                if (isFirstClass) {
                    firstTransformedClass = className.replace('/', '.');
                    AgentLogWriter.info("[AllReturnTransformer] First transformed class: " + firstTransformedClass + " (mark field injected)");
                }

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
        private final boolean injectMark;
        private boolean transformed = false;

        AllReturnClassVisitor(ClassVisitor cv, String className, boolean injectMark) {
            super(Opcodes.ASM9, cv);
            this.className = className;
            this.injectMark = injectMark;
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

        @Override
        public void visitEnd() {
            // 如果是第一个转换的类，注入标签字段
            if (injectMark && transformed) {
                ITransformModule.injectMarkField(cv, MARK_FIELD_NAME);
            }
            super.visitEnd();
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
