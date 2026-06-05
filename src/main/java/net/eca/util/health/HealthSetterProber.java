package net.eca.util.health;

import net.minecraft.world.entity.LivingEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 行为探针：通过观测真实写入效果来定位血量写入方法，而非靠方法名关键词或字节码反推。
 * 自定义存储常把真正的写入入口藏在不含关键词、私有、或带额外守卫的方法里，名字搜不到、符号也逆不出；
 * 但只要血量可变，就一定存在一个"喂入数值 → getHealth 随之改变"的写入点。
 * 这里对候选方法施加两个测试值，若 getHealth 都如实跟随到该值，则判定它是表现为恒等映射的血量写入方法，
 * 再以目标值调用它落地。只缓存已确认的方法，绝不记录"找不到"——失效判定本身可能误判，缓存失败会把实体永久判死。
 */
public final class HealthSetterProber {

    private HealthSetterProber() {}

    //仅缓存已确认的写入方法；永不缓存失败结果
    private static final Map<Class<?>, Method> CONFIRMED = new ConcurrentHashMap<>();

    //防止探针调用的方法内部回环再次触发本流程
    private static final ThreadLocal<Boolean> PROBING = ThreadLocal.withInitial(() -> false);

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    //识别并写入目标血量，命中返回 true。每次调用都现场重试，不因历史失败而跳过
    public static boolean resolveAndWrite(LivingEntity entity, float expected) {
        if (entity == null || PROBING.get()) return false;
        PROBING.set(true);
        try {
            Method cached = CONFIRMED.get(entity.getClass());
            if (cached != null && tryApply(entity, cached, expected)) return true;
            Method found = probe(entity, expected);
            if (found != null) {
                CONFIRMED.put(entity.getClass(), found);
                return matches(entity, expected);
            }
            return false;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            return false;
        } finally {
            PROBING.set(false);
        }
    }

    /* 两点探针：表现为"输入即新血量"的恒等写入方法判定命中，随后以目标值落地。
       对不影响血量的候选不做基线回写，避免把它控制的无关字段设成血量基线值。 */
    private static Method probe(LivingEntity entity, float expected) {
        float baseline = safeGetHealth(entity);
        if (!Float.isFinite(baseline) || baseline <= 1.0f) return null;
        float maxBaseline = safeGetMaxHealth(entity);

        float probeA = baseline * 0.5f;
        float probeB = baseline * 0.25f;
        if (tooClose(probeA, probeB) || tooClose(probeA, baseline) || tooClose(probeB, baseline)) return null;

        //会连带改最大血量的候选（上限/钳制类）：先收集，探测后用原始最大血量逐个还原，避免误伤上限；仅当没有纯当前血量 setter 时才兜底使用
        List<Method> ceilingCandidates = new ArrayList<>();
        Method pure = null;
        for (Method m : candidateSetters(entity.getClass())) {
            try {
                invoke(entity, m, probeA);
                float hA = safeGetHealth(entity);
                if (!Float.isFinite(hA) || Math.abs(hA - probeA) > tolerance(probeA)) {
                    //血量被动了但不是恒等写入：还原基线；完全没动则不再触碰该方法
                    if (Float.isFinite(hA) && Math.abs(hA - baseline) > 1.0f) restore(entity, m, baseline);
                    continue;
                }
                invoke(entity, m, probeB);
                float hB = safeGetHealth(entity);
                if (!Float.isFinite(hB) || Math.abs(hB - probeB) > tolerance(probeB)) {
                    restore(entity, m, baseline);
                    continue;
                }
                //恒等写入已确认。设回基线后血量能回升的才是纯当前血量 setter；否则归为连带改上限的候选
                invoke(entity, m, baseline);
                if (matches(entity, baseline)) {
                    pure = m;
                    break;
                }
                ceilingCandidates.add(m);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
                restore(entity, m, baseline);
            }
        }

        //用原始最大血量还原所有连带改上限的候选，撤销探测对最大血量的误伤
        if (Float.isFinite(maxBaseline) && maxBaseline > 0.0f) {
            for (Method c : ceilingCandidates) restore(entity, c, maxBaseline);
        }

        Method chosen = pure != null ? pure : (ceilingCandidates.isEmpty() ? null : ceilingCandidates.get(0));
        if (chosen != null) {
            try {
                invoke(entity, chosen, expected);
                if (matches(entity, expected)) return chosen;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            }
        }
        return null;
    }

    private static boolean tryApply(LivingEntity entity, Method m, float expected) {
        try {
            invoke(entity, m, expected);
            return matches(entity, expected);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            return false;
        }
    }

    private static void restore(LivingEntity entity, Method m, float baseline) {
        try {
            invoke(entity, m, baseline);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
        }
    }

    /* 用 MethodHandle 而非 Method.invoke：部分实体会用 Unsafe 改写对象 klass 指针把自己伪装成子类，
       反射的原生 accessor 会因"分配时真实类 != 当前 klass"统一抛 argument type mismatch；
       MethodHandle 走真实 invokevirtual/invokespecial 语义（与正常方法调用同路），可绕过该校验。 */
    private static void invoke(LivingEntity entity, Method m, float value) throws Throwable {
        MethodHandle mh = LOOKUP.unreflect(m);
        if (m.getParameterTypes()[0] == double.class) {
            mh.invoke(entity, (double) value);
        } else {
            mh.invoke(entity, value);
        }
    }

    //候选：实体自定义层级（不含原版 LivingEntity）上单个浮点参数的实例方法。浮点参数排除了多数整型的 AI/状态 setter
    private static List<Method> candidateSetters(Class<?> entityClass) {
        List<Method> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> c = entityClass; c != null && c != LivingEntity.class && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1) continue;
                if (params[0] != float.class && params[0] != double.class) continue;
                if (!seen.add(m.getName() + ":" + params[0].getName())) continue;
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                out.add(m);
            }
        }
        return out;
    }

    private static boolean matches(LivingEntity entity, float expected) {
        float h = safeGetHealth(entity);
        return Float.isFinite(h) && Math.abs(h - expected) <= tolerance(expected);
    }

    //相对容差：低血量也不至于因绝对阈值过宽而误判
    private static float tolerance(float value) {
        return Math.max(1.0f, Math.abs(value) * 0.02f);
    }

    private static boolean tooClose(float a, float b) {
        return Math.abs(a - b) < 1.0f;
    }

    private static float safeGetHealth(LivingEntity entity) {
        try {
            return entity.getHealth();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            return Float.NaN;
        }
    }

    private static float safeGetMaxHealth(LivingEntity entity) {
        try {
            return entity.getMaxHealth();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw (VirtualMachineError) t;
            return Float.NaN;
        }
    }
}
