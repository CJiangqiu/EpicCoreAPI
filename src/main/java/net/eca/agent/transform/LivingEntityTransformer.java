package net.eca.agent.transform;

import net.eca.agent.AgentLogWriter;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Bytecode transformer for LivingEntity and its subclasses.
 * Uses HEAD hook injection: ECA logic runs first at method entry,
 * returns early if overriding, otherwise falls through to original method body.
 */
public class LivingEntityTransformer implements ITransformModule {

    private static final String TARGET_BASE_CLASS = "net.minecraft.world.entity.LivingEntity";

    private static final String GET_HEALTH_METHOD_NAME = "m_21223_";
    private static final String GET_HEALTH_METHOD_DESC = "()F";
    private static final String GET_MAX_HEALTH_METHOD_NAME = "m_21233_";
    private static final String GET_MAX_HEALTH_METHOD_DESC = "()F";
    private static final String IS_DEAD_OR_DYING_METHOD_NAME = "m_21224_";
    private static final String IS_DEAD_OR_DYING_METHOD_DESC = "()Z";
    private static final String IS_ALIVE_METHOD_NAME = "m_6084_";
    private static final String IS_ALIVE_METHOD_DESC = "()Z";

    // getHealth hook（由 EcaAgent 设置）
    private static String hookClassName = "net/eca/util/health/LivingEntityHook";
    private static String hookMethodName = "processGetHealth";
    private static String hookMethodDesc = "(Lnet/minecraft/world/entity/LivingEntity;)F";

    // boolean 状态 hook（isAlive/isDeadOrDying 返回 int: -1=passthrough, 0=false, 1=true）
    private static final String STATUS_HOOK_CLASS_NAME = "net/eca/util/health/LivingEntityHook";
    private static final String STATUS_HOOK_METHOD_DESC = "(Lnet/minecraft/world/entity/LivingEntity;)I";

    private static volatile int transformCount = 0;

    public static int getTransformCount() {
        return transformCount;
    }

    public static void setHookHandler(String className, String methodName, String methodDesc) {
        hookClassName = className;
        hookMethodName = methodName;
        hookMethodDesc = methodDesc;
        AgentLogWriter.info("[LivingEntityTransformer] Hook handler set to: " + className + "." + methodName);
    }

    @Override
    public String getName() {
        return "LivingEntityTransformer";
    }

    @Override
    public String getTargetBaseClass() {
        return TARGET_BASE_CLASS;
    }

    @Override
    public boolean shouldTransform(String className, ClassLoader classLoader) {
        if (className.startsWith("net/eca/")) {
            return false;
        }
        if (ReturnToggle.PackageWhitelist.isCustomProtected(className)) {
            return false;
        }
        return true;
    }

    @Override
    public byte[] transform(String className, byte[] classfileBuffer) {
        try {
            if (className.equals("net/minecraft/world/entity/Entity")) {
                return null;
            }

            // 快速扫描：检查类是否声明了任一目标方法
            ClassReader scanReader = new ClassReader(classfileBuffer);
            MethodFinder finder = new MethodFinder();
            scanReader.accept(finder, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            if (!finder.hasTargetMethod) {
                return null;
            }

            // 实际转换
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            TransformClassVisitor cv = new TransformClassVisitor(cw);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);

            if (cv.transformed) {
                byte[] result = cw.toByteArray();
                transformCount++;
                AgentLogWriter.info("[LivingEntityTransformer] Transformed: " + className
                        + " mode=HEAD_HOOK (total: " + transformCount + ")");
                return result;
            }
            return null;

        } catch (Throwable t) {
            AgentLogWriter.error("[LivingEntityTransformer] Failed to transform: " + className, t);
            return null;
        }
    }

    // 快速扫描：任一目标方法声明即可
    private static class MethodFinder extends ClassVisitor {
        boolean hasTargetMethod = false;

        MethodFinder() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if ((name.equals(GET_HEALTH_METHOD_NAME) && descriptor.equals(GET_HEALTH_METHOD_DESC)) ||
                (name.equals(GET_MAX_HEALTH_METHOD_NAME) && descriptor.equals(GET_MAX_HEALTH_METHOD_DESC)) ||
                (name.equals(IS_DEAD_OR_DYING_METHOD_NAME) && descriptor.equals(IS_DEAD_OR_DYING_METHOD_DESC)) ||
                (name.equals(IS_ALIVE_METHOD_NAME) && descriptor.equals(IS_ALIVE_METHOD_DESC))) {
                hasTargetMethod = true;
            }
            return null;
        }
    }

    // ClassVisitor：对声明了目标方法的类进行 HEAD hook 注入
    private static class TransformClassVisitor extends ClassVisitor {
        boolean transformed = false;

        TransformClassVisitor(ClassWriter cw) {
            super(Opcodes.ASM9, cw);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            if (name.equals(GET_HEALTH_METHOD_NAME) && descriptor.equals(GET_HEALTH_METHOD_DESC)) {
                transformed = true;
                return new FloatHeadHookVisitor(mv, hookClassName, hookMethodName, hookMethodDesc);
            }

            if (name.equals(GET_MAX_HEALTH_METHOD_NAME) && descriptor.equals(GET_MAX_HEALTH_METHOD_DESC)) {
                transformed = true;
                return new FloatHeadHookVisitor(mv, STATUS_HOOK_CLASS_NAME,
                        "processGetMaxHealth", "(Lnet/minecraft/world/entity/LivingEntity;)F");
            }

            if (name.equals(IS_DEAD_OR_DYING_METHOD_NAME) && descriptor.equals(IS_DEAD_OR_DYING_METHOD_DESC)) {
                transformed = true;
                return new BooleanHeadHookVisitor(mv, "processIsDeadOrDying");
            }

            if (name.equals(IS_ALIVE_METHOD_NAME) && descriptor.equals(IS_ALIVE_METHOD_DESC)) {
                transformed = true;
                return new BooleanHeadHookVisitor(mv, "processIsAlive");
            }

            return mv;
        }
    }

    // ==================== HEAD Hook Visitors ====================

    // float 方法 HEAD hook：方法头调用 hook，非 NaN 则直接返回，NaN 则 fall through
    private static class FloatHeadHookVisitor extends MethodVisitor {
        private final String hookOwner;
        private final String hookName;
        private final String hookDesc;

        FloatHeadHookVisitor(MethodVisitor mv, String hookOwner, String hookName, String hookDesc) {
            super(Opcodes.ASM9, mv);
            this.hookOwner = hookOwner;
            this.hookName = hookName;
            this.hookDesc = hookDesc;
        }

        @Override
        public void visitCode() {
            super.visitCode();

            Label continueLabel = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/world/entity/LivingEntity");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc, false);
            // NaN 检测：NaN != NaN，FCMPL(NaN, NaN) = -1
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.FCMPL);
            mv.visitJumpInsn(Opcodes.IFLT, continueLabel);
            mv.visitInsn(Opcodes.FRETURN);
            mv.visitLabel(continueLabel);
            mv.visitInsn(Opcodes.POP);
        }
    }

    // boolean 方法 HEAD hook：方法头调用 hook 返回 int，-1 = passthrough，0/1 = 直接返回
    private static class BooleanHeadHookVisitor extends MethodVisitor {
        private final String hookMethod;

        BooleanHeadHookVisitor(MethodVisitor mv, String hookMethod) {
            super(Opcodes.ASM9, mv);
            this.hookMethod = hookMethod;
        }

        @Override
        public void visitCode() {
            super.visitCode();

            Label continueLabel = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/world/entity/LivingEntity");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, STATUS_HOOK_CLASS_NAME, hookMethod,
                    STATUS_HOOK_METHOD_DESC, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ICONST_M1);
            mv.visitJumpInsn(Opcodes.IF_ICMPEQ, continueLabel);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(continueLabel);
            mv.visitInsn(Opcodes.POP);
        }
    }
}
