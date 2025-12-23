package net.eca.agent.container;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import net.eca.api.EcaAPI;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.Entity;

/**
 * ECA自定义的Int2ObjectLinkedOpenHashMap容器
 * 用于替换MC原版的EntityTickList.active和EntityTickList.passive
 *
 * 内置无敌保护逻辑，当尝试移除无敌实体时自动拦截
 */
public class EcaInt2ObjectLinkedOpenHashMap<V> extends Int2ObjectLinkedOpenHashMap<V> {

    private static final long serialVersionUID = 1L;

    // 是否启用调试日志
    private static final boolean DEBUG = false;

    public EcaInt2ObjectLinkedOpenHashMap() {
        super();
    }

    public EcaInt2ObjectLinkedOpenHashMap(int expected) {
        super(expected);
    }

    public EcaInt2ObjectLinkedOpenHashMap(int expected, float f) {
        super(expected, f);
    }

    @Override
    public V remove(int key) {
        // 检查是否应该保护这个移除操作
        if (shouldProtectRemoval(key)) {
            if (DEBUG) {
                EcaLogger.info("[EcaInt2ObjectLinkedOpenHashMap] Blocked removal of entity id: {}", key);
            }
            return null; // 阻止移除，返回null
        }

        return super.remove(key);
    }

    /**
     * 检查是否应该保护这个移除操作
     * @param entityId 实体ID
     * @return true 如果应该保护（阻止移除），false 如果允许移除
     */
    private boolean shouldProtectRemoval(int entityId) {
        // 从Map中获取值
        V value = super.get(entityId);

        // 检查值是否是Entity实例
        if (value instanceof Entity entity) {
            // 检查实体是否处于无敌状态，同时允许切换维度操作
            // Allow dimension change operations even for invulnerable entities
            return EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity);
        }

        return false;
    }
}
