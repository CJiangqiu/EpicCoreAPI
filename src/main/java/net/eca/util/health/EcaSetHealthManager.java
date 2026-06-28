package net.eca.util.health;

import net.eca.config.EcaConfiguration;
import net.eca.coremod.RuntimeBytecodeProvider;
import net.eca.util.reflect.VarHandleUtil;
import net.minecraft.world.entity.LivingEntity;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.security.CodeSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

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
        CONST_OVERRIDE,   // getHealth 返回不可变常数时，劫持其返回值
        EXTERNAL_SCAN,    // CONST_OVERRIDE 失败时，逆向分析 isAlive/isDeadOrDying 定位真实存储
        METHOD_PROBE,
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

    // 实体类 → 完整分析结果，供各降级手段独立判断，避免重复 analyze
    private static final Map<Class<?>, HealthDataflowAnalyzer.AnalysisResult> ANALYSIS_CACHE = new ConcurrentHashMap<>();

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
       仅在写入并校验通过后才缓存路径。仅处理 SOLVABLE 实体——CONSTANT/HAS_CONSTANT 由 setHealthByConstOverride 独立接管。 */
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

    //外部扫描路径：const override 失败后，逆向 isAlive/isDeadOrDying 定位真实存储
    public static boolean setHealthByExternalScan(LivingEntity entity, float target) {
        if (entity == null) return false;
        Class<?> cls = entity.getClass();

        HealthPath cached = PATHS.get(cls);
        if (cached != null && cached.method() == WriteMethod.EXTERNAL_SCAN
                && cached.write(entity, target) && verifyExternalScan(entity, target)) {
            return true;
        }

        HealthPath resolved = HealthDataflowAnalyzer.resolveExternalScan(cls);
        if (resolved != null && resolved.write(entity, target) && verifyExternalScan(entity, target)) {
            PATHS.put(cls, resolved);
            return true;
        }
        return false;
    }

    //外部扫描验证：kill 时检查 isAlive / isDeadOrDying，非 kill 时读 getHealth
    private static boolean verifyExternalScan(LivingEntity entity, float target) {
        if (target <= 0.0f) {
            return !entity.isAlive() || entity.isDeadOrDying();
        }
        float actual = safeGetHealth(entity);
        if (Float.isFinite(actual)) {
            float tolerance = Math.max(0.5f, Math.abs(target) * 0.02f);
            if (Math.abs(actual - target) <= tolerance) return true;
        }
        return entity.isAlive() && !entity.isDeadOrDying();
    }

    /* 常数覆盖：仅对 getHealth 存在常数分支的实体生效(CONSTANT / HAS_CONSTANT)。
       不写实体存储，只登记覆盖表，由 getHealth 内联的 resolveHealth 读取返回。
       首次命中时触发 retransform 打 FRETURN patch，后续调用直接走覆盖表。 */
    public static boolean setHealthByConstOverride(LivingEntity entity, float target) {
        if (entity == null) return false;
        Class<?> cls = entity.getClass();
        HealthDataflowAnalyzer.AnalysisResult ar =
                ANALYSIS_CACHE.computeIfAbsent(cls, c -> HealthDataflowAnalyzer.analyze(c));
        HealthDataflowAnalyzer.AnalysisResult.Kind kind = ar.classify();
        if (kind != HealthDataflowAnalyzer.AnalysisResult.Kind.CONSTANT
                && kind != HealthDataflowAnalyzer.AnalysisResult.Kind.HAS_CONSTANT) return false;

        /* retransform 必须针对定义 getHealth()F 的类（可能是父类），
           否则 EcaClassTransformer 找不到该方法而跳过 FRETURN patch */
        if (EcaConfiguration.getAttackEnableRadicalLogicSafely()
                && EcaConfiguration.getAttackSetHealthEnableConstOverrideSafely()) {
            Class<?> defCls = ar.definingClass != null ? ar.definingClass : cls;
            String internalName = internalName(defCls);
            if (ConstOverrideManager.PATCHED_CLASSES.add(internalName)) {
                HealthDataflowAnalyzer.retransformClass(defCls);
            }
        }

        ConstOverrideManager.setOverride(entity, target);
        return verify(entity, target);
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

    // 取类的运行期字节码(含 mixin/coremod 转换后)，取不到回退磁盘原始 .class，再取不到回退 CodeSource JAR
    public static byte[] classBytes(Class<?> clazz) {
        if (clazz == null) return null;
        try {
            byte[] runtime = RuntimeBytecodeProvider.get(clazz);
            if (runtime != null) return runtime;
        } catch (Throwable ignored) {}
        ClassLoader cl = clazz.getClassLoader();
        if (cl == null) cl = ClassLoader.getSystemClassLoader();
        String path = internalName(clazz) + ".class";
        try (InputStream is = cl.getResourceAsStream(path)) {
            if (is != null) return is.readAllBytes();
        } catch (Throwable ignored) {}
        // CodeSource 回退：从 ProtectionDomain 的 JAR 中直接读取 .class 条目
        try {
            CodeSource cs = clazz.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                try (JarFile jar = new JarFile(cs.getLocation().getPath())) {
                    java.util.jar.JarEntry entry = jar.getJarEntry(path);
                    if (entry != null) {
                        try (InputStream jis = jar.getInputStream(entry)) {
                            return jis.readAllBytes();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
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
