package net.eca.compat;

import net.eca.coremod.AllReturnToggle;
import net.eca.coremod.TransformerWhitelist;
import net.eca.util.EntityUtil;
import net.eca.util.reflect.UnsafeUtil;
import net.eca.util.EcaLogger;
import net.eca.agent.EcaAgent;
import net.minecraft.world.entity.Entity;

import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.Set;

/*
 * 内部测试 API，仅供 ECA 自身及内部工具使用，不对外公开，不生成文档。
 * 所有方法的语义与 EcaAPI 对应方法完全一致，省去 getAttackEnableRadicalLogicSafely() 闸门。
 */
final class TestAPI {

    private TestAPI() {}

    // ==================== memoryRemove ====================

    /* 同 EcaAPI.memoryRemove，免配置 */
    static boolean memoryRemove(Entity entity, Entity.RemovalReason reason) {
        if (entity == null) return false;
        EntityUtil.prepareForMemoryRemove(entity);
        return UnsafeUtil.unsafeRemove(entity, reason);
    }

    // ==================== AllReturn ====================

    /* 同 EcaAPI.enableAllReturn(Entity)，免配置 */
    static boolean enableAllReturn(Entity entity) {
        if (entity == null) return false;
        if (EcaAgent.getInstrumentation() == null) {
            EcaLogger.warn("AllReturn: Agent is not initialized");
            return false;
        }

        String binaryName = entity.getClass().getName();
        if (TransformerWhitelist.isProtected(binaryName)) return false;

        String internalPrefix = toInternalPrefix(binaryName);
        if (internalPrefix == null) return false;

        AllReturnToggle.setEnabled(true);
        AllReturnToggle.addAllowedPrefix(internalPrefix);
        return true;
    }

    /* 同 EcaAPI.setGlobalAllReturn(boolean)，免配置 */
    static boolean setGlobalAllReturn(boolean enable) {
        if (!enable) {
            AllReturnToggle.clearAll();
            return true;
        }

        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            EcaLogger.warn("GlobalAllReturn: Agent is not initialized");
            return false;
        }

        Set<String> collectedPrefixes = new HashSet<>();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (!inst.isModifiableClass(clazz)) continue;
            if (clazz.isInterface() || clazz.isArray() || clazz.isPrimitive()) continue;

            String className = clazz.getName();
            if (TransformerWhitelist.isProtected(className)) continue;

            String prefix = toInternalPrefix(className);
            if (prefix != null) collectedPrefixes.add(prefix);
        }

        if (collectedPrefixes.isEmpty()) {
            EcaLogger.warn("GlobalAllReturn: No candidate package prefixes found");
            return false;
        }

        AllReturnToggle.setEnabled(true);
        for (String prefix : collectedPrefixes) {
            AllReturnToggle.addAllowedPrefix(prefix);
        }
        return true;
    }

    private static String toInternalPrefix(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        if (lastDot <= 0) return null;
        return binaryName.substring(0, lastDot + 1).replace('.', '/');
    }
}
