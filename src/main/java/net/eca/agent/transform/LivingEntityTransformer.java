package net.eca.agent.transform;

import net.eca.agent.AgentLogWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Bytecode transformer for LivingEntity and its subclasses.
 * Handles transformations like getHealth() hooking for heal negation.
 */
public class LivingEntityTransformer implements ITransformModule {

    private static final String TARGET_BASE_CLASS = "net.minecraft.world.entity.LivingEntity";

    // getHealth()方法的SRG混淆名
    private static final String GET_HEALTH_METHOD_NAME = "m_21223_";
    private static final String GET_HEALTH_METHOD_DESC = "()F";

    // Hook处理器类（由使用者设置）
    private static String hookClassName = "net/eca/util/health/GetHealthHook";
    private static String hookMethodName = "processGetHealth";
    private static String hookMethodDesc = "(Lnet/minecraft/world/entity/LivingEntity;F)F";

    // 设置Hook处理器
    /**
     * Set the hook handler class and method.
     * @param className the internal class name of the hook handler
     * @param methodName the method name to call
     * @param methodDesc the method descriptor
     */
    public static void setHookHandler(String className, String methodName, String methodDesc) {
        hookClassName = className;
        hookMethodName = methodName;
        hookMethodDesc = methodDesc;
        AgentLogWriter.info("[LivingEntityTransformer] Hook handler set to: " + className + "." + methodName);
    }

    // 默认的Hook处理方法（直接返回原值）
    /**
     * Default hook handler that returns the original health value.
     * Override by setting a custom hook handler via setHookHandler().
     * @param originalHealth the original health value
     * @param entity the living entity
     * @param className the class name of the entity
     * @return the processed health value
     */
    public static float processGetHealth(float originalHealth, Object entity, String className) {
        return originalHealth;
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
    public boolean requiresMethodOverrideCheck() {
        return true;
    }

    @Override
    public String getTargetMethodName() {
        return GET_HEALTH_METHOD_NAME;
    }

    @Override
    public String getTargetMethodDescriptor() {
        return GET_HEALTH_METHOD_DESC;
    }

    @Override
    public boolean shouldTransform(String className, ClassLoader classLoader) {
        // 跳过ECA自己的类
        if (className.startsWith("net/eca/")) {
            return false;
        }
        // 实际的类型检查在EcaAgent中进行
        return true;
    }

    @Override
    public byte[] transform(String className, byte[] classfileBuffer) {
        try {
            // 第一遍：快速扫描，检查类是否定义了目标方法
            ClassReader scanReader = new ClassReader(classfileBuffer);
            MethodFinder finder = new MethodFinder();
            scanReader.accept(finder, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            if (!finder.hasTargetMethod) {
                return null;
            }

            // 第二遍：实际转换
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            GetHealthClassVisitor cv = new GetHealthClassVisitor(cw, className);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);

            if (cv.transformed) {
                byte[] transformedBytes = cw.toByteArray();
                AgentLogWriter.info("[LivingEntityTransformer] Hooked getHealth() in: " + className);
                return transformedBytes;
            }
            return null;

        } catch (Throwable t) {
            AgentLogWriter.error("[LivingEntityTransformer] Failed to transform: " + className, t);
            return null;
        }
    }

    // 快速扫描类是否定义了目标方法
    private static class MethodFinder extends ClassVisitor {
        boolean hasTargetMethod = false;

        MethodFinder() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (name.equals(GET_HEALTH_METHOD_NAME) && descriptor.equals(GET_HEALTH_METHOD_DESC)) {
                hasTargetMethod = true;
            }
            return null;
        }
    }

    // 安全的ClassWriter，避免类加载问题
    private static class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader classReader, int flags) {
            super(classReader, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            // 避免在Agent中加载类，直接返回Object
            return "java/lang/Object";
        }
    }

    // ClassVisitor：遍历类的所有方法
    private static class GetHealthClassVisitor extends ClassVisitor {
        private final String className;
        public boolean transformed = false;

        GetHealthClassVisitor(ClassWriter cw, String className) {
            super(Opcodes.ASM9, cw);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            if (name.equals(GET_HEALTH_METHOD_NAME) && descriptor.equals(GET_HEALTH_METHOD_DESC)) {
                transformed = true;
                return new GetHealthMethodVisitor(mv, className);
            }

            return mv;
        }
    }

    // MethodVisitor：在返回前拦截并修改返回值
    private static class GetHealthMethodVisitor extends MethodVisitor {
        private final String className;

        GetHealthMethodVisitor(MethodVisitor mv, String className) {
            super(Opcodes.ASM9, mv);
            this.className = className;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.FRETURN) {
                // 在返回前修改栈顶的返回值
                // 栈：[原始血量] → [this, 原始血量] → [最终血量]
                mv.visitVarInsn(Opcodes.ALOAD, 0);  // 加载this（放在栈底）
                mv.visitInsn(Opcodes.SWAP);  // 交换栈顶两个元素，变成 [this, 原始血量]
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        hookClassName,
                        hookMethodName,
                        hookMethodDesc,
                        false
                );
            }
            super.visitInsn(opcode);
        }
    }
}
