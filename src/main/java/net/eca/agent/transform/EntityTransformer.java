package net.eca.agent.transform;

import net.eca.agent.AgentLogWriter;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Bytecode transformer for Entity and its subclasses.
 * Uses HEAD hook injection for isRemoved() hooking.
 */
public class EntityTransformer implements ITransformModule {

    private static final String TARGET_BASE_CLASS = "net.minecraft.world.entity.Entity";

    private static final String IS_REMOVED_METHOD_NAME = "m_213877_";
    private static final String IS_REMOVED_METHOD_DESC = "()Z";

    private static final String HOOK_CLASS_NAME = "net/eca/util/entity/EntityHook";
    private static final String HOOK_METHOD_NAME = "processIsRemoved";
    private static final String HOOK_METHOD_DESC = "(Lnet/minecraft/world/entity/Entity;)I";

    private static volatile int transformCount = 0;

    public static int getTransformCount() {
        return transformCount;
    }

    @Override
    public String getName() {
        return "EntityTransformer";
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
            // 快速扫描：检查类是否声明了 isRemoved
            ClassReader scanReader = new ClassReader(classfileBuffer);
            MethodFinder finder = new MethodFinder();
            scanReader.accept(finder, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            if (!finder.hasTargetMethod) {
                return null;
            }

            // 实际转换
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            IsRemovedClassVisitor cv = new IsRemovedClassVisitor(cw);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);

            if (cv.transformed) {
                byte[] transformedBytes = cw.toByteArray();
                transformCount++;
                AgentLogWriter.info("[EntityTransformer] Transformed: " + className
                        + " mode=HEAD_HOOK (total: " + transformCount + ")");
                return transformedBytes;
            }
            return null;

        } catch (Throwable t) {
            AgentLogWriter.error("[EntityTransformer] Failed to transform: " + className, t);
            return null;
        }
    }

    private static class MethodFinder extends ClassVisitor {
        boolean hasTargetMethod = false;

        MethodFinder() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (name.equals(IS_REMOVED_METHOD_NAME) && descriptor.equals(IS_REMOVED_METHOD_DESC)) {
                hasTargetMethod = true;
            }
            return null;
        }
    }

    private static class IsRemovedClassVisitor extends ClassVisitor {
        boolean transformed = false;

        IsRemovedClassVisitor(ClassWriter cw) {
            super(Opcodes.ASM9, cw);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            if (name.equals(IS_REMOVED_METHOD_NAME) && descriptor.equals(IS_REMOVED_METHOD_DESC)) {
                transformed = true;
                return new IsRemovedHeadHookVisitor(mv);
            }

            return mv;
        }
    }

    // HEAD hook：方法头调用 hook 返回 int，-1 = passthrough，0/1 = 直接返回
    private static class IsRemovedHeadHookVisitor extends MethodVisitor {

        IsRemovedHeadHookVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();

            Label continueLabel = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/world/entity/Entity");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_CLASS_NAME, HOOK_METHOD_NAME,
                    HOOK_METHOD_DESC, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ICONST_M1);
            mv.visitJumpInsn(Opcodes.IF_ICMPEQ, continueLabel);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(continueLabel);
            mv.visitInsn(Opcodes.POP);
        }
    }
}
