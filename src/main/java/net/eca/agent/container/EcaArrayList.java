package net.eca.agent.container;

import net.eca.api.EcaAPI;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;

/**
 * ECA自定义的ArrayList容器
 * 用于替换MC原版的ClassInstanceMultiMap.allInstances
 *
 * 内置无敌保护逻辑，当尝试移除无敌实体时自动拦截
 */
public class EcaArrayList<E> extends ArrayList<E> {

    private static final long serialVersionUID = 1L;

    // 是否启用调试日志
    private static final boolean DEBUG = false;

    public EcaArrayList() {
        super();
    }

    public EcaArrayList(int initialCapacity) {
        super(initialCapacity);
    }

    public EcaArrayList(Collection<? extends E> c) {
        super(c);
    }

    @Override
    public boolean remove(Object o) {
        // 检查是否应该保护这个移除操作
        if (shouldProtectRemoval(o)) {
            if (DEBUG) {
                EcaLogger.info("[EcaArrayList] Blocked removal of: {}", o);
            }
            return false; // 阻止移除，返回false
        }

        return super.remove(o);
    }

    @Override
    public E remove(int index) {
        // 先获取要移除的元素
        E element = super.get(index);

        // 检查是否应该保护
        if (shouldProtectRemoval(element)) {
            if (DEBUG) {
                EcaLogger.info("[EcaArrayList] Blocked removal at index {}: {}", index, element);
            }
            return null; // 阻止移除，返回null
        }

        return super.remove(index);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        // 过滤掉无敌实体后再移除
        Collection<?> filtered = c.stream()
            .filter(item -> !shouldProtectRemoval(item))
            .toList();

        return super.removeAll(filtered);
    }

    @Override
    public boolean removeIf(java.util.function.Predicate<? super E> filter) {
        // 添加无敌检查到过滤条件
        java.util.function.Predicate<E> protectedFilter = item ->
            !shouldProtectRemoval(item) && filter.test(item);

        return super.removeIf(protectedFilter);
    }

    /**
     * 检查是否应该保护这个移除操作
     * @param element 要移除的元素
     * @return true 如果应该保护（阻止移除），false 如果允许移除
     */
    private boolean shouldProtectRemoval(Object element) {
        // 检查元素是否是Entity实例
        if (element instanceof Entity entity) {
            // 检查实体是否处于无敌状态，同时允许切换维度操作
            // Allow dimension change operations even for invulnerable entities
            return EcaAPI.isInvulnerable(entity) && !EntityUtil.isChangingDimension(entity);
        }

        return false;
    }
}
