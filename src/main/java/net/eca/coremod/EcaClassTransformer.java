package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.EcaAgent;
import net.eca.util.health.ConstOverrideManager;
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

    /* 供 JVM TI 原生回调调用的静态入口——绕过 InstrumentationImpl，直接访问变换逻辑。
       JVM TI 回调无法提供 ClassLoader/ProtectionDomain/classBeingRedefined，
       但内部逻辑仅依赖 className 和字节码。 */
    public static byte[] transformStatic(String className, byte[] classfileBuffer) {
        if (className == null) return null;
        if (TransformerWhitelist.isSystemProtectedInternal(className)) return null;
        try {
            // 通过静态实例调用（ConstOverrideClassVisitor 等方法是非 static inner class，需实例）
            return SINGLETON.doTransform(className, classfileBuffer);
        } catch (Throwable t) {
            AgentLogWriter.error("[EcaClassTransformer] Failed: " + className, t);
            return null;
        }
    }

    /* 单例实例，供 transformStatic + JVM TI 回调复用 */
    private static final EcaClassTransformer SINGLETON = new EcaClassTransformer();

    // ==================== 初始化入口 ====================

    private static volatile boolean registered = false;

    /* 仅注册 Transformer 与永久捕获器，不做 retransform。
       在 mod CONSTRUCT 阶段调用：此时其他 mod（如 SuperSteve）正在并发构造/defineClass，
       一旦在此 retransform 已加载的类，会与并发的 ModuleClassLoader 锁 + Mixin LaunchPluginHandler 锁
       发生顺序反转死锁。注册是廉价的，且注册后续加载的类都会在加载期被变换，无需 retransform。 */
    public static void register() {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            AgentLogWriter.info("[EcaClassTransformer] No Instrumentation available, skipping register");
            return;
        }
        ensureRegistered(inst);
    }

    //注册 Transformer 并重转换已加载的类。须在 mod 加载完成后（线程池空闲）调用，避免并发 retransform 死锁
    public static void init() {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            AgentLogWriter.info("[EcaClassTransformer] No Instrumentation available, skipping init");
            return;
        }

        ensureRegistered(inst);
        retransformLoadedClasses(inst);
    }

    private static void ensureRegistered(Instrumentation inst) {
        if (!registered) {
            TransformerWhitelist.loadJsonWhitelist();
            inst.addTransformer(new EcaClassTransformer(), true);
            AgentLogWriter.info("[EcaClassTransformer] Registered as ClassFileTransformer");
            registered = true;
        }

        //注册永久捕获器排在链尾，之后所有类加载/retransform 自动缓存最终字节码
        RuntimeBytecodeProvider.registerPermanentCapture(inst);
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

        // 常数覆盖 FRETURN 替换：对分析阶段标记的 CONSTANT 类,内联 resolveHealth 调用
        if (ConstOverrideManager.PATCHED_CLASSES.contains(className)) {
            byte[] constResult = applyConstOverridePatching(className, result);
            if (constResult != null) {
                result = constResult;
                anyTransformed = true;
            }
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

    // ==================== 常数覆盖 FRETURN 替换 ====================

    /* 对 PATCHED_CLASSES 中的 CONSTANT 实体类，在 getHealth 的每个 FRETURN 前内联
       ConstOverrideManager.resolveHealth(this, originalValue) 调用。
       若类不在 KNOWN_LIVING_ENTITY_CLASSES 中（加载晚于 init），同时注入 FloatHookVisitor。 */
    private byte[] applyConstOverridePatching(String className, byte[] classfileBuffer) {
        ClassReader cr = new ClassReader(classfileBuffer);
        boolean[] hasGetHealth = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                if (desc.equals("()F") && (name.equals(GET_HEALTH) || name.equals("getHealth"))) {
                    hasGetHealth[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!hasGetHealth[0]) return null;

        boolean needsHook = !KNOWN_LIVING_ENTITY_CLASSES.contains(className);
        cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ConstOverrideClassVisitor cv = new ConstOverrideClassVisitor(cw, needsHook);
        cr.accept(cv, ClassReader.EXPAND_FRAMES);

        if (cv.transformed) {
            transformCount++;
            AgentLogWriter.info("[EcaClassTransformer] ConstOverride patched: " + className
                    + " needsHook=" + needsHook + " (total: " + transformCount + ")");
            return cw.toByteArray();
        }
        return null;
    }

    /* 遍历类方法，对 getHealth()F 链式包裹 FloatHookVisitor（如需）+ ConstOverrideMethodVisitor */
    private static class ConstOverrideClassVisitor extends ClassVisitor {
        final boolean needsHook;
        boolean transformed;

        ConstOverrideClassVisitor(ClassWriter cw, boolean needsHook) {
            super(Opcodes.ASM9, cw);
            this.needsHook = needsHook;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
            if (desc.equals("()F") && (name.equals(GET_HEALTH) || name.equals("getHealth"))) {
                transformed = true;
                if (needsHook) {
                    mv = new FloatHookVisitor(mv, LIVING_HOOK, "processGetHealth",
                            "(Lnet/minecraft/world/entity/LivingEntity;)F", LIVING_ENTITY);
                }
                mv = new ConstOverrideMethodVisitor(mv);
            }
            return mv;
        }
    }

    /* 在 getHealth 方法体的每条 FRETURN 前插入: ALOAD 0 + SWAP + INVOKESTATIC resolveHealth */
    private static class ConstOverrideMethodVisitor extends MethodVisitor {
        private static final String CO_MGR = "net/eca/util/health/ConstOverrideManager";
        private static final String CO_DESC = "(Lnet/minecraft/world/entity/LivingEntity;F)F";

        ConstOverrideMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.FRETURN) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, CO_MGR, "resolveHealth", CO_DESC, false);
            }
            super.visitInsn(opcode);
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
