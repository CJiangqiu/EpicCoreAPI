package net.eca.util.health;

import net.minecraft.world.entity.LivingEntity;

import java.util.function.BiFunction;
import java.util.function.Function;

// 生命值字段缓存
/**
 * Cache for entity health field access information.
 * Stores the analyzed access pattern and transformation formulas for each entity class.
 */
public class HealthFieldCache {

    // 统一的访问模式
    /**
     * Unified access pattern for both fields and containers
     */
    public ContainerAccessPattern accessPattern;

    // 逆向公式
    /**
     * Reverse transformation formula: converts target health to the value that needs to be written
     */
    public Function<Float, Float> reverseTransform;

    // 统一的写入函数
    /**
     * Unified write function for modifying health values
     */
    public BiFunction<LivingEntity, Float, Boolean> writePath;

    // 容器检测标志
    /**
     * Container detection flag (used for active scanning)
     */
    public boolean containerDetected = false;

    // 容器类名
    /**
     * Container class name (for HashMap, etc.)
     */
    public String containerClass;

    // 容器getter方法名
    /**
     * Container getter method name
     */
    public String containerGetterMethod;

    // 容器类型
    /**
     * Container type (HashMap, WeakHashMap, etc.)
     */
    public String containerType;
}
