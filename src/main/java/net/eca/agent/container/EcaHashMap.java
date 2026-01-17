package net.eca.agent.container;

import net.eca.api.EcaAPI;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * ECA自定义的HashMap容器
 * 用于替换MC原版的EntityLookup.byUuid和ClassInstanceMultiMap.byClass
 *
 * 内置无敌保护逻辑，当尝试移除无敌实体时自动拦截
 */
public class EcaHashMap<K, V> extends HashMap<K, V> {

    private static final long serialVersionUID = 1L;

    // 是否启用调试日志
    private static final boolean DEBUG = false;

    public EcaHashMap() {
        super();
    }

    public EcaHashMap(int initialCapacity) {
        super(initialCapacity);
    }

    public EcaHashMap(int initialCapacity, float loadFactor) {
        super(initialCapacity, loadFactor);
    }

    public EcaHashMap(Map<? extends K, ? extends V> m) {
        super(m);
    }

    @Override
    public V put(K key, V value) {
        return super.put(key, wrapListIfNeeded(value));
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        V value = super.computeIfAbsent(key, mappingFunction);
        V wrapped = wrapListIfNeeded(value);
        if (wrapped != value) {
            super.put(key, wrapped);
            return wrapped;
        }
        return value;
    }

    @Override
    public V remove(Object key) {
        // 检查是否应该保护这个移除操作
        if (shouldProtectRemoval(key)) {
            if (DEBUG) {
                EcaLogger.info("[EcaHashMap] Blocked removal of key: {}", key);
            }
            return null; // 阻止移除，返回null
        }

        return super.remove(key);
    }

    /**
     * 检查是否应该保护这个移除操作
     * @param key 要移除的键
     * @return true 如果应该保护（阻止移除），false 如果允许移除
     */
    private boolean shouldProtectRemoval(Object key) {
        // 获取对应的值
        V value = super.get(key);

        // 情况1：值直接是Entity（用于EntityLookup.byUuid）
        if (value instanceof Entity entity) {
            // Allow dimension change operations even for invulnerable entities
            return EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity);
        }

        // 情况2：值是List（用于ClassInstanceMultiMap.byClass）
        // 检查List中是否包含无敌实体
        if (value instanceof java.util.List<?> list) {
            for (Object item : list) {
                if (item instanceof Entity entity) {
                    // Allow dimension change operations even for invulnerable entities
                    if (EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity)) {
                        return true; // 如果List中有任何无敌实体，阻止移除整个List
                    }
                }
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private V wrapListIfNeeded(V value) {
        if (value instanceof EcaArrayList) {
            return value;
        }
        if (value instanceof java.util.List<?> list) {
            return (V) new EcaArrayList<>(list);
        }
        return value;
    }
}
