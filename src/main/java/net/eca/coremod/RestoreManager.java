package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.EcaAgent;
import net.minecraft.world.entity.LivingEntity;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the class-restore attack.
 * On {@link #restore(LivingEntity)}, the entity's custom class chain (from its
 * runtime class up to the first vanilla/JDK class) is registered as a transform
 * target and retransformed so that {@link ClassRestoreTransformer} injects the
 * per-instance restore guards. The entity is then marked restored by UUID.
 */
public final class RestoreManager {

    private RestoreManager() {}

    private static final Set<String> TARGET_CLASSES = ConcurrentHashMap.newKeySet();

    //加载/重转换时检查：该类是否应被注入还原 guard
    static boolean isTargetClass(String internalClassName) {
        return internalClassName != null && TARGET_CLASSES.contains(internalClassName);
    }

    //还原入口：收集类链、注入 guard、按 UUID 标记实例
    public static boolean restore(LivingEntity entity) {
        if (entity == null) return false;

        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            AgentLogWriter.warn("[RestoreManager] No Instrumentation available");
            return false;
        }

        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (isBoundary(c.getName())) break;
            chain.add(c);
        }

        //标记 UUID 后即生效；vanilla 实体无自定义链,直接返回
        EcaRestoreHook.mark(entity.getUUID());
        if (chain.isEmpty()) return true;

        List<Class<?>> toRetransform = new ArrayList<>();
        for (Class<?> c : chain) {
            String internal = c.getName().replace('.', '/');
            if (TARGET_CLASSES.add(internal) && inst.isModifiableClass(c)) {
                toRetransform.add(c);
            }
        }
        //链上的类已被先前的 restore 注入过 guard,无需重复重转换
        if (toRetransform.isEmpty()) return true;

        try {
            inst.retransformClasses(toRetransform.toArray(new Class<?>[0]));
            return true;
        } catch (Throwable t) {
            boolean anySuccess = false;
            for (Class<?> c : toRetransform) {
                try {
                    inst.retransformClasses(c);
                    anySuccess = true;
                } catch (Throwable t2) {
                    AgentLogWriter.error("[RestoreManager] Failed to retransform: " + c.getName(), t2);
                }
            }
            return anySuccess;
        }
    }

    //取消还原：仅清除该实例的 UUID 标记,已注入的 guard 对其余实例保持休眠
    public static void unrestore(LivingEntity entity) {
        if (entity != null) EcaRestoreHook.unmark(entity.getUUID());
    }

    //检查实体是否处于还原状态
    public static boolean isRestored(LivingEntity entity) {
        return entity != null && EcaRestoreHook.isRestored(entity.getUUID());
    }

    public static void clearAll() {
        TARGET_CLASSES.clear();
        EcaRestoreHook.clear();
    }

    //继承链边界：第一个 vanilla / Forge / JDK 类
    private static boolean isBoundary(String className) {
        return className.startsWith("net.minecraft.")
            || className.startsWith("net.minecraftforge.")
            || className.startsWith("java.")
            || className.startsWith("javax.")
            || className.startsWith("jdk.")
            || className.startsWith("sun.");
    }
}
