package net.eca.util.health;

import net.eca.coremod.RuntimeBytecodeProvider;
import net.eca.util.reflect.VarHandleUtil;
import net.minecraft.world.entity.LivingEntity;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 改血总管理器：持有"路径表"——每个实体类用什么改血方法(method, 后续扩充)、以及已确定的修改路径(writer)，
 * 命中即直接重放，避免重复分析。同时集中改血流程复用的基础 API(取字节码、字段 IO、数值强转、校验)。
 * 数据流逆向分析交给 HealthDataflowAnalyzer，本类只负责编排 + 缓存 + 复用工具。
 */
public final class EcaSetHealthManager {

    private EcaSetHealthManager() {}

    /* ==================== 路径表 ==================== */

    // 改血方法类型，后续会扩充更多 method
    public enum WriteMethod {
        VANILLA,    // 原版同步数据(DATA_HEALTH_ID)
        DATAFLOW,   // getHealth 字节码数据流逆向定位的存储
        METHOD_PROBE,
        CLASS_RESTORE,
        WRITE_SITE_BRIDGE,
        NUMERIC_INVERSION
    }

    // 改血写入器：把目标血量写进实体真实存储，成功返回 true
    @FunctionalInterface
    public interface HealthWriter {
        boolean write(LivingEntity entity, float target);
    }

    // 一条已确定的改血路径：用什么方法 + 具体写入器
    public record HealthPath(WriteMethod method, HealthWriter writer) {
        public boolean write(LivingEntity entity, float target) {
            try {
                return writer.write(entity, target);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return false;
            }
        }
    }

    // 实体类 → 已确定改血路径。仅缓存验证通过的路径，绝不缓存失败(避免一次误判把该类永久判死)
    private static final Map<Class<?>, HealthPath> PATHS = new ConcurrentHashMap<>();

    public static HealthPath getPath(Class<?> entityClass) {
        return entityClass == null ? null : PATHS.get(entityClass);
    }

    public static void putPath(Class<?> entityClass, HealthPath path) {
        if (entityClass != null && path != null) PATHS.put(entityClass, path);
    }

    public static void removePath(Class<?> entityClass) {
        if (entityClass != null) PATHS.remove(entityClass);
    }

    public static void clear() {
        PATHS.clear();
    }

    public static boolean replayCachedPath(LivingEntity entity, float target) {
        if (entity == null) return false;
        HealthPath cached = PATHS.get(entity.getClass());
        if (cached == null) return false;
        return cached.write(entity, target) && verify(entity, target);
    }

    /* ==================== 对外编排入口 ==================== */

    /* 用数据流路径给实体改血：先重放该类已确定的路径，未命中或失效再走逆向分析重新定位。
       仅在写入并校验通过后才缓存路径。不处理原版(DATA_HEALTH_ID)写入——那是上层 Vanilla 阶段的职责。 */
    public static boolean setHealthByDataflow(LivingEntity entity, float target) {
        if (entity == null) return false;
        Class<?> cls = entity.getClass();

        HealthPath cached = PATHS.get(cls);
        if (cached != null && cached.method() != WriteMethod.DATAFLOW) {
            cached = null;
        }
        if (cached != null && cached.write(entity, target) && verify(entity, target)) {
            return true;
        }

        HealthPath resolved = HealthDataflowAnalyzer.resolvePath(cls);
        if (resolved == null) return false;
        if (resolved.write(entity, target) && verify(entity, target)) {
            PATHS.put(cls, resolved);
            return true;
        }
        return false;
    }

    public static boolean setHealthByMethodProbe(LivingEntity entity, float target) {
        if (entity == null) return false;
        Class<?> cls = entity.getClass();

        HealthPath cached = PATHS.get(cls);
        if (cached != null && cached.method() == WriteMethod.METHOD_PROBE
                && cached.write(entity, target) && verify(entity, target)) {
            return true;
        }

        HealthPath resolved = HealthMethodProbe.resolvePath(entity, target);
        if (resolved == null) return false;
        if (resolved.write(entity, target) && verify(entity, target)) {
            PATHS.put(cls, resolved);
            return true;
        }
        return false;
    }

    public static boolean setHealthByClassRestore(LivingEntity entity, float target) {
        if (entity == null) return false;
        return HealthClassRestore.write(entity, target);
    }

    public static boolean setHealthByNumericInversion(LivingEntity entity, float target) {
        if (entity == null) return false;
        Class<?> cls = entity.getClass();

        HealthPath cached = PATHS.get(cls);
        if (cached != null && cached.method() == WriteMethod.NUMERIC_INVERSION
                && cached.write(entity, target) && verify(entity, target)) {
            return true;
        }

        HealthPath resolved = HealthNumericInverter.resolvePath(entity, target);
        if (resolved == null) return false;
        if (resolved.write(entity, target) && verify(entity, target)) {
            PATHS.put(cls, resolved);
            return true;
        }
        return false;
    }

    public static boolean setHealthByWriteSiteBridge(LivingEntity entity, float target) {
        if (entity == null) return false;
        Class<?> cls = entity.getClass();

        HealthPath cached = PATHS.get(cls);
        if (cached != null && cached.method() == WriteMethod.WRITE_SITE_BRIDGE
                && cached.write(entity, target) && verify(entity, target)) {
            return true;
        }

        HealthPath resolved = HealthWriteSiteBridge.resolvePath(entity, target);
        if (resolved == null) return false;
        if (resolved.write(entity, target) && verify(entity, target)) {
            PATHS.put(cls, resolved);
            return true;
        }
        return false;
    }

    /* ==================== 改血复用 API ==================== */

    // 取类的运行期字节码(含 mixin/coremod 转换后)，取不到回退磁盘原始 .class
    public static byte[] classBytes(Class<?> clazz) {
        if (clazz == null) return null;
        try {
            byte[] runtime = RuntimeBytecodeProvider.get(clazz);
            if (runtime != null) return runtime;
        } catch (Throwable ignored) {}
        ClassLoader cl = clazz.getClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        try (InputStream is = cl.getResourceAsStream(internalName(clazz) + ".class")) {
            return is == null ? null : is.readAllBytes();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    /* 类内部名：剥去隐藏类运行期后缀(Foo/0x000... → Foo)，按磁盘模板类定位字节码。
       隐藏类 getName() 可能只剩 SimpleName，此时用其超类的包补全(隐藏类与模板同包)。 */
    public static String internalName(Class<?> clazz) {
        String n = clazz.getName().replace('.', '/');
        int hidden = n.indexOf("/0x");
        if (hidden < 0) return n;
        String stripped = n.substring(0, hidden);
        if (stripped.indexOf('/') < 0) {
            Class<?> sup = clazz.getSuperclass();
            if (sup != null) {
                String supInternal = sup.getName().replace('.', '/');
                int lastSlash = supInternal.lastIndexOf('/');
                if (lastSlash >= 0) return supInternal.substring(0, lastSlash + 1) + stripped;
            }
        }
        return stripped;
    }

    // 安全读取实体当前血量，异常或非有限值返回 NaN
    public static float safeGetHealth(LivingEntity entity) {
        try {
            float h = entity.getHealth();
            return Float.isFinite(h) ? h : Float.NaN;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Float.NaN;
        }
    }

    // 校验改血是否生效：getHealth 落在目标值容差内
    public static boolean verify(LivingEntity entity, float target) {
        float actual = safeGetHealth(entity);
        if (!Float.isFinite(actual)) return false;
        float tolerance = Math.max(0.5f, Math.abs(target) * 0.02f);
        return Math.abs(actual - target) <= tolerance;
    }

    // 用 VarHandle 读字段值，失败返回 null
    public static <T> T readField(Object target, Field field) {
        return VarHandleUtil.getFieldValue(target, field);
    }

    // 用 VarHandle 给字段写值(自动数值强转)，成功返回 true
    public static boolean writeField(Object target, Field field, Object value) {
        return VarHandleUtil.setFieldValue(target, field, value);
    }

    // 把目标血量强转到字段的数值类型(基本类型或包装类型)，非数值类型返回 null
    public static Object coerceNumber(float value, Class<?> type) {
        if (type == float.class || type == Float.class) return value;
        if (type == double.class || type == Double.class) return (double) value;
        if (type == int.class || type == Integer.class) return (int) value;
        if (type == long.class || type == Long.class) return (long) value;
        if (type == short.class || type == Short.class) return (short) value;
        if (type == byte.class || type == Byte.class) return (byte) value;
        return null;
    }
}
