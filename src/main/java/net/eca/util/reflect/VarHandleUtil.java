package net.eca.util.reflect;

import net.eca.util.EcaLogger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// VarHandle工具类 - 高性能字段访问
/**
 * VarHandle utility class for high-performance field access.
 * VarHandle provides better performance than traditional reflection for frequent access.
 */
public final class VarHandleUtil {

    private static final Map<String, VarHandle> VARHANDLE_CACHE = new ConcurrentHashMap<>();

    // 通过映射key获取VarHandle
    /**
     * Get a VarHandle using the obfuscation mapping key.
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key like "Entity.entityData"
     * @return the VarHandle for the field, or null if not found
     */
    public static VarHandle getVarHandle(Class<?> clazz, String fieldKey) {
        String cacheKey = clazz.getName() + "#" + fieldKey;

        return VARHANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
            try {
                Field field = ReflectUtil.getField(clazz, fieldKey);
                if (field == null) {
                    return null;
                }
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
                return lookup.unreflectVarHandle(field);
            } catch (IllegalAccessException e) {
                EcaLogger.info("[VarHandleUtil] Failed to create VarHandle for: {} in {}", fieldKey, clazz.getName(), e);
                return null;
            }
        });
    }

    // 通过字段名直接获取VarHandle
    /**
     * Get a VarHandle directly by field name (for non-obfuscated fields).
     * @param clazz the class containing the field
     * @param fieldName the actual field name
     * @return the VarHandle for the field, or null if not found
     */
    public static VarHandle getVarHandleByName(Class<?> clazz, String fieldName) {
        String cacheKey = clazz.getName() + "#direct#" + fieldName;

        return VARHANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
            try {
                Field field = ReflectUtil.getFieldByName(clazz, fieldName);
                if (field == null) {
                    return null;
                }
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
                return lookup.unreflectVarHandle(field);
            } catch (IllegalAccessException e) {
                EcaLogger.info("[VarHandleUtil] Failed to create VarHandle for field: {} in {}", fieldName, clazz.getName(), e);
                return null;
            }
        });
    }

    // 获取字段值
    /**
     * Get field value using VarHandle.
     * @param target the target object (null for static fields)
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param <T> the expected return type
     * @return the field value, or null if failed
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(Object target, Class<?> clazz, String fieldKey) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return null;
        }
        return (T) handle.get(target);
    }

    // 设置字段值
    /**
     * Set field value using VarHandle.
     * @param target the target object (null for static fields)
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean set(Object target, Class<?> clazz, String fieldKey, Object value) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return false;
        }
        handle.set(target, value);
        return true;
    }

    // 原子性获取并设置
    /**
     * Atomically get and set field value.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param newValue the new value to set
     * @param <T> the value type
     * @return the previous value, or null if failed
     */
    @SuppressWarnings("unchecked")
    public static <T> T getAndSet(Object target, Class<?> clazz, String fieldKey, T newValue) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return null;
        }
        return (T) handle.getAndSet(target, newValue);
    }

    // 比较并设置
    /**
     * Atomically compare and set field value.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param expectedValue the expected current value
     * @param newValue the new value to set
     * @return true if successful, false if failed or value mismatch
     */
    public static boolean compareAndSet(Object target, Class<?> clazz, String fieldKey, Object expectedValue, Object newValue) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return false;
        }
        return handle.compareAndSet(target, expectedValue, newValue);
    }

    // volatile读取
    /**
     * Get field value with volatile semantics.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param <T> the expected return type
     * @return the field value, or null if failed
     */
    @SuppressWarnings("unchecked")
    public static <T> T getVolatile(Object target, Class<?> clazz, String fieldKey) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return null;
        }
        return (T) handle.getVolatile(target);
    }

    // volatile写入
    /**
     * Set field value with volatile semantics.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean setVolatile(Object target, Class<?> clazz, String fieldKey, Object value) {
        VarHandle handle = getVarHandle(clazz, fieldKey);
        if (handle == null) {
            return false;
        }
        handle.setVolatile(target, value);
        return true;
    }

    // 清空缓存
    /**
     * Clear all cached VarHandles.
     */
    public static void clearCache() {
        VARHANDLE_CACHE.clear();
    }

    // ==================== 运行时动态字段访问（支持Field对象）====================

    // 通过Field对象获取VarHandle
    /**
     * Get a VarHandle from a Field object (for runtime-discovered fields).
     * This is useful when you have a Field object from reflection and want high-performance access.
     * @param field the field object
     * @return the VarHandle for the field, or null if failed
     */
    public static VarHandle getVarHandleFromField(Field field) {
        if (field == null) {
            EcaLogger.info("[VarHandleUtil] Field cannot be null");
            return null;
        }

        Class<?> declaringClass = field.getDeclaringClass();
        String fieldName = field.getName();
        Class<?> fieldType = field.getType();

        // 使用更精确的缓存key：类名#字段名#类型
        String cacheKey = declaringClass.getName() + "#field#" + fieldName + "#" + fieldType.getName();

        return VARHANDLE_CACHE.computeIfAbsent(cacheKey, k -> {
            try {
                field.setAccessible(true);
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(declaringClass, MethodHandles.lookup());
                return lookup.unreflectVarHandle(field);
            } catch (IllegalAccessException e) {
                EcaLogger.info("[VarHandleUtil] Failed to create VarHandle from Field: {} in {}", fieldName, declaringClass.getName(), e);
                return null;
            }
        });
    }

    // 通过Field对象获取字段值
    /**
     * Get field value using a Field object.
     * @param target the target object (null for static fields)
     * @param field the field object
     * @param <T> the expected return type
     * @return the field value, or null if failed
     */
    @SuppressWarnings("unchecked")
    public static <T> T getFieldValue(Object target, Field field) {
        VarHandle handle = getVarHandleFromField(field);
        if (handle == null) {
            return null;
        }
        return (T) handle.get(target);
    }

    // 通过Field对象设置字段值
    /**
     * Set field value using a Field object.
     * This method automatically handles type conversion and provides better error messages.
     * @param target the target object (null for static fields)
     * @param field the field object
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean setFieldValue(Object target, Field field, Object value) {
        try {
            VarHandle handle = getVarHandleFromField(field);
            if (handle == null) {
                return false;
            }

            // 自动类型转换
            Class<?> fieldType = field.getType();
            Object convertedValue = convertValue(value, fieldType);

            handle.set(target, convertedValue);
            return true;
        } catch (Exception e) {
            // 静默失败，返回false
            return false;
        }
    }

    // 类型转换辅助方法
    /**
     * Convert value to the target type.
     * Handles primitive types and their wrapper classes.
     * @param value the value to convert
     * @param targetType the target type
     * @return the converted value
     */
    private static Object convertValue(Object value, Class<?> targetType) {
        if (value == null) return null;

        // 如果已经是目标类型，直接返回
        if (targetType.isInstance(value)) {
            return value;
        }

        // Number类型的转换
        if (value instanceof Number) {
            Number number = (Number) value;

            if (targetType == float.class || targetType == Float.class) {
                return number.floatValue();
            } else if (targetType == double.class || targetType == Double.class) {
                return number.doubleValue();
            } else if (targetType == int.class || targetType == Integer.class) {
                return number.intValue();
            } else if (targetType == long.class || targetType == Long.class) {
                return number.longValue();
            } else if (targetType == short.class || targetType == Short.class) {
                return number.shortValue();
            } else if (targetType == byte.class || targetType == Byte.class) {
                return number.byteValue();
            }
        }

        // 其他情况直接返回原值
        return value;
    }

    // 批量设置字段值（带类型转换）
    /**
     * Set field value with automatic type conversion.
     * This is a convenience method that combines Field lookup and value setting.
     * @param target the target object
     * @param clazz the class containing the field
     * @param fieldName the field name
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean setFieldValueByName(Object target, Class<?> clazz, String fieldName, Object value) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            return setFieldValue(target, field, value);
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private VarHandleUtil() {}
}
