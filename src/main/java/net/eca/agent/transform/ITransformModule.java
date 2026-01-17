package net.eca.agent.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Collections;
import java.util.List;

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

    /**
     * Check if this module should retransform an already loaded class.
     * @param className the binary class name (e.g., "net.minecraft.world.entity.LivingEntity")
     * @param classLoader the class loader of the class
     * @return true if this module wants to retransform the class
     */
    default boolean shouldRetransform(String className, ClassLoader classLoader) {
        return true;
    }

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

    /**
     * Get explicit target class names for retransformation.
     * Used for modules that transform unrelated classes (not a shared base type).
     * @return list of class names (e.g., "net.minecraft.server.level.ChunkMap")
     */
    default List<String> getTargetClassNames() {
        return Collections.emptyList();
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

    // ==================== 验证相关方法 ====================

    // 获取标签字段名
    /**
     * Get the mark field name for verification.
     * The field will be injected as: public static final boolean [fieldName] = true
     * @return the mark field name (e.g., "__ECA_LIVING_ENTITY_MARK__")
     */
    default String getMarkFieldName() {
        return "__ECA_" + getName().toUpperCase().replace("TRANSFORMER", "") + "MARK__";
    }

    // 获取第一个被转换的类名
    /**
     * Get the first transformed class name for verification.
     * @return the binary class name (e.g., "net.minecraft.world.entity.LivingEntity"), or null if no class was transformed
     */
    default String getFirstTransformedClass() {
        return null;
    }

    // 获取需要恢复转换的类列表
    /**
     * Get the classes that need to be retransformed during recovery.
     * Called when verification fails and recovery is needed.
     * @return array of classes to retransform, or empty array if not applicable
     */
    default Class<?>[] getRetransformTargets() {
        return new Class<?>[0];
    }

    // 是否需要验证
    /**
     * Check if this module requires verification.
     * Some modules (like AllReturnTransformer) may not need verification if they haven't transformed any classes.
     * @return true if verification is required
     */
    default boolean requiresVerification() {
        return getFirstTransformedClass() != null;
    }

    // 注入标签字段到类中
    /**
     * Inject the mark field into a class via ClassVisitor.
     * Call this in your ClassVisitor's visitEnd() method.
     * @param cv the ClassVisitor to inject the field into
     * @param markFieldName the name of the mark field
     */
    static void injectMarkField(ClassVisitor cv, String markFieldName) {
        FieldVisitor fv = cv.visitField(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            markFieldName,
            "Z",  // boolean
            null,
            Boolean.TRUE
        );
        if (fv != null) {
            fv.visitEnd();
        }
    }
}
