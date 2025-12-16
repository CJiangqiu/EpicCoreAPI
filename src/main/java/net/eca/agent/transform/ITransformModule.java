package net.eca.agent.transform;

// 字节码转换模块接口
/**
 * Interface for bytecode transformation modules.
 * Each module handles transformations for a specific target class level.
 */
public interface ITransformModule {

    // 获取模块名称
    /**
     * Get the name of this transform module.
     * @return the module name for logging purposes
     */
    String getName();

    // 判断是否应该转换该类
    /**
     * Check if this module should transform the given class.
     * @param className the internal class name (e.g., "net/minecraft/world/entity/LivingEntity")
     * @param classLoader the class loader of the class
     * @return true if this module should transform the class
     */
    boolean shouldTransform(String className, ClassLoader classLoader);

    // 执行字节码转换
    /**
     * Transform the bytecode of the class.
     * @param className the internal class name
     * @param classfileBuffer the original bytecode
     * @return the transformed bytecode, or null if no transformation was performed
     */
    byte[] transform(String className, byte[] classfileBuffer);

    // 获取目标基类（用于筛选需要retransform的类）
    /**
     * Get the target base class name that this module handles.
     * Used for filtering classes during retransformation.
     * @return the target base class name (e.g., "net.minecraft.world.entity.LivingEntity")
     */
    default String getTargetBaseClass() {
        return null;
    }

    // 判断是否需要检查方法重写
    /**
     * Check if this module requires method override checking.
     * If true, only classes that override specific methods will be transformed.
     * @return true if method override checking is required
     */
    default boolean requiresMethodOverrideCheck() {
        return false;
    }

    // 获取需要检查重写的方法名
    /**
     * Get the method name to check for override.
     * Only used if requiresMethodOverrideCheck() returns true.
     * @return the SRG method name to check
     */
    default String getTargetMethodName() {
        return null;
    }

    // 获取需要检查重写的方法描述符
    /**
     * Get the method descriptor to check for override.
     * Only used if requiresMethodOverrideCheck() returns true.
     * @return the method descriptor (e.g., "()F")
     */
    default String getTargetMethodDescriptor() {
        return null;
    }
}
