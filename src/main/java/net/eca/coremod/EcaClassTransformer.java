package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.EcaAgent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified ClassFileTransformer for all ECA bytecode injection.
 * Handles LivingEntity (getHealth, getMaxHealth, isDeadOrDying, isAlive),
 * Entity (isRemoved), and DisplayWindow (loading screen gradient).
 */
public final class EcaClassTransformer implements ClassFileTransformer {

    // ==================== SRG 方法名 ====================

    private static final String GET_HEALTH         = "m_21223_";
    private static final String GET_MAX_HEALTH     = "m_21233_";
    private static final String IS_DEAD_OR_DYING   = "m_21224_";
    private static final String IS_ALIVE           = "m_6084_";
    private static final String IS_REMOVED         = "m_213877_";

    // ==================== Hook 类路径 ====================

    private static final String LIVING_HOOK = "net/eca/coremod/LivingEntityHook";
    private static final String ENTITY_HOOK = "net/eca/coremod/EntityHook";

    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String ENTITY        = "net/minecraft/world/entity/Entity";

    private static volatile int transformCount = 0;

    // 收集阶段预计算：哪些类名是 LivingEntity 子类
    private static final Set<String> KNOWN_LIVING_ENTITY_CLASSES = ConcurrentHashMap.newKeySet();
    // 哪些类名是 Entity 子类（但不是 LivingEntity 子类）
    private static final Set<String> KNOWN_ENTITY_ONLY_CLASSES = ConcurrentHashMap.newKeySet();

    public static int getTransformCount() {
        return transformCount;
    }

    // ==================== 初始化入口 ====================

    private static volatile boolean registered = false;

    //注册 Transformer 并重转换已加载的类（首次调用注册，后续只 retransform）
    public static void init() {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            AgentLogWriter.info("[EcaClassTransformer] No Instrumentation available, skipping init");
            return;
        }

        if (!registered) {
            inst.addTransformer(new EcaClassTransformer(), true);
            AgentLogWriter.info("[EcaClassTransformer] Registered as ClassFileTransformer");
            registered = true;
        }

        retransformLoadedClasses(inst);
    }

    //重转换已加载的类（Entity/LivingEntity 子类 + DisplayWindow）
    private static void retransformLoadedClasses(Instrumentation inst) {
        List<Class<?>> toRetransform = new ArrayList<>();

        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (!inst.isModifiableClass(clazz)) continue;
            String name = clazz.getName();

            // 白名单内的特殊目标
            if (LoadingScreenTransformer.ENABLED &&
                name.equals("net.minecraftforge.fml.earlydisplay.DisplayWindow")) {
                toRetransform.add(clazz);
                continue;
            }
            String internalName = name.replace('.', '/');
            if (ContainerReplacementTransformer.isTarget(internalName)) {
                toRetransform.add(clazz);
                continue;
            }

            if (TransformerWhitelist.isSystemProtected(name)) continue;

            // 一次遍历继承链，同时判断 LivingEntity 和 Entity
            try {
                int ancestorType = classifyEntity(clazz);
                if (ancestorType == 1) {
                    KNOWN_LIVING_ENTITY_CLASSES.add(internalName);
                    toRetransform.add(clazz);
                } else if (ancestorType == 2) {
                    KNOWN_ENTITY_ONLY_CLASSES.add(internalName);
                    toRetransform.add(clazz);
                }
            } catch (Throwable ignored) {}
        }

        AgentLogWriter.info("[EcaClassTransformer] Retransforming " + toRetransform.size() + " loaded classes");

        // 批量 retransform
        int batchSize = 32;
        for (int i = 0; i < toRetransform.size(); i += batchSize) {
            int end = Math.min(i + batchSize, toRetransform.size());
            Class<?>[] batch = toRetransform.subList(i, end).toArray(new Class<?>[0]);
            try {
                inst.retransformClasses(batch);
            } catch (Throwable t) {
                // 批量失败时逐个重试
                for (Class<?> clazz : batch) {
                    try {
                        inst.retransformClasses(clazz);
                    } catch (Throwable t2) {
                        AgentLogWriter.error("[EcaClassTransformer] Failed to retransform: " + clazz.getName(), t2);
                    }
                }
            }
        }
    }

    // 返回 1=LivingEntity子类, 2=Entity子类(非LivingEntity), 0=都不是
    private static int classifyEntity(Class<?> clazz) {
        for (Class<?> c = clazz.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.equals("net.minecraft.world.entity.LivingEntity")) return 1;
            if (name.equals("net.minecraft.world.entity.Entity")) return 2;
        }
        return 0;
    }

    // ==================== ClassFileTransformer ====================

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;

        // 白名单内的特殊目标：DisplayWindow 渐变背景
        if (LoadingScreenTransformer.ENABLED &&
            LoadingScreenTransformer.TARGET_CLASS.equals(className)) {
            return LoadingScreenTransformer.transform(classfileBuffer);
        }

        // 白名单内的特殊目标：MC 原版容器替换
        if (ContainerReplacementTransformer.isTarget(className)) {
            return ContainerReplacementTransformer.transform(className, classfileBuffer);
        }

        if (TransformerWhitelist.isSystemProtectedInternal(className)) return null;

        try {
            return doTransform(className, classfileBuffer);
        } catch (Throwable t) {
            AgentLogWriter.error("[EcaClassTransformer] Failed: " + className, t);
            return null;
        }
    }

    private byte[] doTransform(String className, byte[] classfileBuffer) {
        byte[] result = classfileBuffer;
        boolean anyTransformed = false;

        // AllReturn guard 注入
        if (AllReturnToggle.shouldInjectGuard(className)) {
            byte[] allReturnResult = AllReturnTransformer.transform(className, result);
            if (allReturnResult != null) {
                result = allReturnResult;
                anyTransformed = true;
            }
        }

        // Entity/LivingEntity hook 注入
        byte[] hookResult = doHookTransform(className, result);
        if (hookResult != null) {
            result = hookResult;
            anyTransformed = true;
        }

        return anyTransformed ? result : null;
    }

    private byte[] doHookTransform(String className, byte[] classfileBuffer) {
        // 查预计算缓存，O(1)
        boolean isLivingEntity = KNOWN_LIVING_ENTITY_CLASSES.contains(className);
        boolean isEntity = !isLivingEntity && KNOWN_ENTITY_ONLY_CLASSES.contains(className);
        if (!isLivingEntity && !isEntity) return null;

        // 确认类声明了目标方法（跳过代码/调试/帧，只遍历方法签名）
        ClassReader cr = new ClassReader(classfileBuffer);
        MethodScanner scanner = new MethodScanner();
        cr.accept(scanner, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!scanner.hasAnyTarget) return null;

        // ASM 转换（复用同一个 ClassReader）
        ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        HookInjector injector = new HookInjector(cw, isLivingEntity);
        cr.accept(injector, ClassReader.EXPAND_FRAMES);

        if (!injector.transformed) return null;

        transformCount++;
        AgentLogWriter.info("[EcaClassTransformer] Transformed: " + className + " (total: " + transformCount + ")");
        return cw.toByteArray();
    }

    // ==================== 快速方法扫描 ====================

    private static class MethodScanner extends ClassVisitor {
        boolean hasAnyTarget = false;

        MethodScanner() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if (desc.equals("()F") && (name.equals(GET_HEALTH) || name.equals(GET_MAX_HEALTH))) {
                hasAnyTarget = true;
            } else if (desc.equals("()Z") && (name.equals(IS_DEAD_OR_DYING) || name.equals(IS_ALIVE) || name.equals(IS_REMOVED))) {
                hasAnyTarget = true;
            }
            return null;
        }
    }

    // ==================== Hook 注入 ====================

    private static class HookInjector extends ClassVisitor {
        final boolean isLivingEntity;
        boolean transformed = false;

        HookInjector(ClassWriter cw, boolean isLivingEntity) {
            super(Opcodes.ASM9, cw);
            this.isLivingEntity = isLivingEntity;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

            if (isLivingEntity) {
                if (name.equals(GET_HEALTH) && desc.equals("()F")) {
                    transformed = true;
                    return new FloatHookVisitor(mv, LIVING_HOOK, "processGetHealth",
                            "(Lnet/minecraft/world/entity/LivingEntity;)F", LIVING_ENTITY);
                }
                if (name.equals(GET_MAX_HEALTH) && desc.equals("()F")) {
                    transformed = true;
                    return new FloatHookVisitor(mv, LIVING_HOOK, "processGetMaxHealth",
                            "(Lnet/minecraft/world/entity/LivingEntity;)F", LIVING_ENTITY);
                }
                if (name.equals(IS_DEAD_OR_DYING) && desc.equals("()Z")) {
                    transformed = true;
                    return new BooleanHookVisitor(mv, LIVING_HOOK, "processIsDeadOrDying",
                            "(Lnet/minecraft/world/entity/LivingEntity;)I", LIVING_ENTITY);
                }
                if (name.equals(IS_ALIVE) && desc.equals("()Z")) {
                    transformed = true;
                    return new BooleanHookVisitor(mv, LIVING_HOOK, "processIsAlive",
                            "(Lnet/minecraft/world/entity/LivingEntity;)I", LIVING_ENTITY);
                }
            }

            if (name.equals(IS_REMOVED) && desc.equals("()Z")) {
                transformed = true;
                return new BooleanHookVisitor(mv, ENTITY_HOOK, "processIsRemoved",
                        "(Lnet/minecraft/world/entity/Entity;)I", ENTITY);
            }

            return mv;
        }
    }

    // ==================== HEAD Hook 注入器 ====================

    // float 方法：调用 hook，非 NaN 则 FRETURN，NaN 则 fall through
    private static class FloatHookVisitor extends MethodVisitor {
        private final String hookOwner, hookName, hookDesc, castType;

        FloatHookVisitor(MethodVisitor mv, String hookOwner, String hookName, String hookDesc, String castType) {
            super(Opcodes.ASM9, mv);
            this.hookOwner = hookOwner;
            this.hookName = hookName;
            this.hookDesc = hookDesc;
            this.castType = castType;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            Label passthrough = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.CHECKCAST, castType);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.FCMPL);
            mv.visitJumpInsn(Opcodes.IFLT, passthrough);
            mv.visitInsn(Opcodes.FRETURN);
            mv.visitLabel(passthrough);
            mv.visitInsn(Opcodes.POP);
        }
    }

    // boolean 方法：调用 hook 返回 int，-1 = passthrough，0/1 = IRETURN
    private static class BooleanHookVisitor extends MethodVisitor {
        private final String hookOwner, hookName, hookDesc, castType;

        BooleanHookVisitor(MethodVisitor mv, String hookOwner, String hookName, String hookDesc, String castType) {
            super(Opcodes.ASM9, mv);
            this.hookOwner = hookOwner;
            this.hookName = hookName;
            this.hookDesc = hookDesc;
            this.castType = castType;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            Label passthrough = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.CHECKCAST, castType);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDesc, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ICONST_M1);
            mv.visitJumpInsn(Opcodes.IF_ICMPEQ, passthrough);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(passthrough);
            mv.visitInsn(Opcodes.POP);
        }
    }

    // ==================== SafeClassWriter ====================

    static class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader cr, int flags) {
            super(cr, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
