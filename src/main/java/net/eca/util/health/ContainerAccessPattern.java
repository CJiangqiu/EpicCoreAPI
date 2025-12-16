package net.eca.util.health;

import net.minecraft.world.entity.LivingEntity;

import java.lang.invoke.VarHandle;

// 容器访问模式
/**
 * Container access pattern for describing how to access and modify values in containers.
 * Supports various container types including HashMap, EntityData, and ArrayList.
 */
public class ContainerAccessPattern {

    // 容器获取器
    /**
     * Container getter (static method/instance field/static field)
     */
    public ContainerGetter containerGetter;

    // Key构造器
    /**
     * Key builder (this/getId()/static field/instance field)
     */
    public KeyBuilder keyBuilder;

    // 值定位器
    /**
     * Value locator: finds the value holder object based on container and key
     */
    public ValueLocator valueLocator;

    // 值的VarHandle
    /**
     * VarHandle for the value field
     */
    public VarHandle valueHandle;

    // 是否是数组元素访问
    /**
     * Flag indicating whether this is an array element access (ArrayList, etc.)
     */
    public boolean isArrayElement;

    // 容器获取器接口
    /**
     * Functional interface for getting the container from an entity.
     */
    @FunctionalInterface
    public interface ContainerGetter {
        /**
         * Get the container from the entity.
         * @param entity the living entity
         * @return the container object
         * @throws Exception if container cannot be retrieved
         */
        Object getContainer(LivingEntity entity) throws Exception;
    }

    // Key构造器接口
    /**
     * Functional interface for building the key from an entity.
     */
    @FunctionalInterface
    public interface KeyBuilder {
        /**
         * Build the key from the entity.
         * @param entity the living entity
         * @return the key object
         * @throws Exception if key cannot be built
         */
        Object buildKey(LivingEntity entity) throws Exception;
    }

    // 值定位器接口
    /**
     * Functional interface for locating the value holder object.
     */
    @FunctionalInterface
    public interface ValueLocator {
        /**
         * Locate the value holder object.
         * For HashMap: returns the Node
         * For ArrayList: returns the elementData array
         * For EntityData: returns the DataItem
         * For field: returns the Entity itself
         * @param container the container object
         * @param key the key object
         * @return the value holder object
         * @throws Exception if value holder cannot be located
         */
        Object locateValueHolder(Object container, Object key) throws Exception;
    }
}
