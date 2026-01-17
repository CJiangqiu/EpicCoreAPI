package net.eca.util.reflect;

import net.eca.util.EcaLogger;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// 反射工具类 - 使用传统Field/Method反射访问字段和方法
/**
 * Reflection utility class using traditional Field/Method reflection.
 * Provides cached access to fields and methods with obfuscation support.
 */
@SuppressWarnings("unchecked")
public final class ReflectUtil {

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    // 通过映射key获取Field
    /**
     * Get a field using the obfuscation mapping key.
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key like "Entity.entityData"
     * @return the accessible Field object, or null if not found
     */
    public static Field getField(Class<?> clazz, String fieldKey) {
        String cacheKey = clazz.getName() + "#" + fieldKey;

        return FIELD_CACHE.computeIfAbsent(cacheKey, k -> {
            String obfName = ObfuscationMapping.getFieldMapping(fieldKey);
            if (obfName == null) {
                EcaLogger.info("[ReflectUtil] No mapping found for field: {}", fieldKey);
                return null;
            }

            try {
                Field field = ObfuscationReflectionHelper.findField(clazz, obfName);
                field.setAccessible(true);
                return field;
            } catch (Exception e) {
                EcaLogger.info("[ReflectUtil] Failed to get field: {} in {}", fieldKey, clazz.getName(), e);
                return null;
            }
        });
    }

    // 通过字段名直接获取Field（适用于未混淆的字段）
    /**
     * Get a field directly by its name (for non-obfuscated fields).
     * @param clazz the class containing the field
     * @param fieldName the actual field name
     * @return the accessible Field object, or null if not found
     */
    public static Field getFieldByName(Class<?> clazz, String fieldName) {
        String cacheKey = clazz.getName() + "#direct#" + fieldName;

        return FIELD_CACHE.computeIfAbsent(cacheKey, k -> {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                // 尝试在父类中查找
                Class<?> superClass = clazz.getSuperclass();
                if (superClass != null && superClass != Object.class) {
                    return getFieldByName(superClass, fieldName);
                }
                EcaLogger.info("[ReflectUtil] Field not found: {} in {}", fieldName, clazz.getName());
                return null;
            }
        });
    }

    // 获取字段值
    /**
     * Get the value of a field using the obfuscation mapping key.
     * @param target the target object (null for static fields)
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param <T> the expected return type
     * @return the field value, or null if failed
     */
    public static <T> T getFieldValue(Object target, Class<?> clazz, String fieldKey) {
        try {
            Field field = getField(clazz, fieldKey);
            if (field == null) {
                return null;
            }
            return (T) field.get(target);
        } catch (IllegalAccessException e) {
            EcaLogger.info("[ReflectUtil] Failed to get field value: {}", fieldKey, e);
            return null;
        }
    }

    // 设置字段值
    /**
     * Set the value of a field using the obfuscation mapping key.
     * @param target the target object (null for static fields)
     * @param clazz the class containing the field
     * @param fieldKey the field mapping key
     * @param value the value to set
     * @return true if successful, false if failed
     */
    public static boolean setFieldValue(Object target, Class<?> clazz, String fieldKey, Object value) {
        try {
            Field field = getField(clazz, fieldKey);
            if (field == null) {
                return false;
            }
            field.set(target, value);
            return true;
        } catch (IllegalAccessException e) {
            EcaLogger.info("[ReflectUtil] Failed to set field value: {}", fieldKey, e);
            return false;
        }
    }

    // 通过映射key获取Method
    /**
     * Get a method using the obfuscation mapping key.
     * @param clazz the class containing the method
     * @param methodKey the method mapping key like "LivingEntity.actuallyHurt"
     * @param paramTypes the method parameter types
     * @return the accessible Method object, or null if not found
     */
    public static Method getMethod(Class<?> clazz, String methodKey, Class<?>... paramTypes) {
        String cacheKey = buildMethodCacheKey(clazz, methodKey, paramTypes);

        return METHOD_CACHE.computeIfAbsent(cacheKey, k -> {
            String obfName = ObfuscationMapping.getMethodMapping(methodKey);
            if (obfName == null) {
                EcaLogger.info("[ReflectUtil] No mapping found for method: {}", methodKey);
                return null;
            }

            try {
                Method method = ObfuscationReflectionHelper.findMethod(clazz, obfName, paramTypes);
                method.setAccessible(true);
                return method;
            } catch (Exception e) {
                EcaLogger.info("[ReflectUtil] Failed to get method: {} in {}", methodKey, clazz.getName(), e);
                return null;
            }
        });
    }

    // 通过方法名直接获取Method
    /**
     * Get a method directly by its name (for non-obfuscated methods).
     * @param clazz the class containing the method
     * @param methodName the actual method name
     * @param paramTypes the method parameter types
     * @return the accessible Method object, or null if not found
     */
    public static Method getMethodByName(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        String cacheKey = buildMethodCacheKey(clazz, "direct#" + methodName, paramTypes);

        return METHOD_CACHE.computeIfAbsent(cacheKey, k -> {
            try {
                Method method = clazz.getDeclaredMethod(methodName, paramTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                // 尝试在父类中查找
                Class<?> superClass = clazz.getSuperclass();
                if (superClass != null && superClass != Object.class) {
                    return getMethodByName(superClass, methodName, paramTypes);
                }
                EcaLogger.info("[ReflectUtil] Method not found: {} in {}", methodName, clazz.getName());
                return null;
            }
        });
    }

    // 调用方法
    /**
     * Invoke a method using the obfuscation mapping key.
     * @param target the target object (null for static methods)
     * @param clazz the class containing the method
     * @param methodKey the method mapping key
     * @param paramTypes the method parameter types
     * @param args the method arguments
     * @param <T> the expected return type
     * @return the method return value, or null if failed
     */
    public static <T> T invokeMethod(Object target, Class<?> clazz, String methodKey, Class<?>[] paramTypes, Object... args) {
        try {
            Method method = getMethod(clazz, methodKey, paramTypes);
            if (method == null) {
                return null;
            }
            return (T) method.invoke(target, args);
        } catch (Exception e) {
            EcaLogger.info("[ReflectUtil] Failed to invoke method: {}", methodKey, e);
            return null;
        }
    }

    // 构建方法缓存key
    private static String buildMethodCacheKey(Class<?> clazz, String methodKey, Class<?>[] paramTypes) {
        StringBuilder sb = new StringBuilder(clazz.getName()).append("#").append(methodKey);
        if (paramTypes != null && paramTypes.length > 0) {
            sb.append("(");
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(paramTypes[i].getName());
            }
            sb.append(")");
        }
        return sb.toString();
    }

    // 清空缓存
    /**
     * Clear all cached fields and methods.
     */
    public static void clearCache() {
        FIELD_CACHE.clear();
        METHOD_CACHE.clear();
    }

    private ReflectUtil() {}
}
