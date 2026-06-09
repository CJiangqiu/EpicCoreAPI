package net.eca.util.health;

import net.minecraft.world.entity.LivingEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 行为探针只依赖“输入数值后 getHealth 达到同一数值”这一可观察事实。
 * 候选可以是普通方法或任意单抽象方法对象，具体类名、接口名和字段名都不参与判定。
 */
public final class HealthSetterProber {

    private HealthSetterProber() {}

    private static final Map<Class<?>, Writer> CONFIRMED = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> PROBING = ThreadLocal.withInitial(() -> false);
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    public static boolean resolveAndWrite(LivingEntity entity, float expected) {
        if (entity == null || PROBING.get()) return false;
        PROBING.set(true);
        try {
            Writer cached = CONFIRMED.get(entity.getClass());
            if (cached != null && tryApply(entity, cached, expected)) return true;
            Writer found = probe(entity, expected);
            if (found == null) return false;
            CONFIRMED.put(entity.getClass(), found);
            HealthAnalyzerManager.confirmPlan(entity.getClass(), plan(found));
            return HealthVerify.matches(entity, expected);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        } finally {
            PROBING.set(false);
        }
    }

    private static Writer probe(LivingEntity entity, float expected) {
        float baseline = HealthVerify.safeGetHealth(entity);
        if (!Float.isFinite(baseline) || baseline <= 1.0f) return null;
        float maxBaseline = safeGetMaxHealth(entity);
        float probeA = baseline * 0.5f;
        float probeB = baseline * 0.25f;
        if (tooClose(probeA, probeB) || tooClose(probeA, baseline) || tooClose(probeB, baseline)) return null;

        List<Writer> ceilingCandidates = new ArrayList<>();
        Writer pure = null;
        for (Writer writer : candidateWriters(entity)) {
            try {
                float a = writer.representable(probeA);
                float b = writer.representable(probeB);
                if (tooClose(a, b) || tooClose(a, baseline) || tooClose(b, baseline)) continue;

                writer.invoke(entity, a);
                float hA = HealthVerify.safeGetHealth(entity);
                if (!matches(hA, a)) {
                    if (Float.isFinite(hA) && Math.abs(hA - baseline) > 1.0f) restore(entity, writer, baseline);
                    continue;
                }

                writer.invoke(entity, b);
                float hB = HealthVerify.safeGetHealth(entity);
                if (!matches(hB, b)) {
                    restore(entity, writer, baseline);
                    continue;
                }

                writer.invoke(entity, baseline);
                if (HealthVerify.matches(entity, baseline)) {
                    pure = writer;
                    break;
                }
                ceilingCandidates.add(writer);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                restore(entity, writer, baseline);
            }
        }

        if (Float.isFinite(maxBaseline) && maxBaseline > 0.0f) {
            for (Writer writer : ceilingCandidates) restore(entity, writer, maxBaseline);
        }

        Writer chosen = pure != null ? pure : (ceilingCandidates.isEmpty() ? null : ceilingCandidates.get(0));
        if (chosen == null) return null;
        try {
            chosen.invoke(entity, expected);
            return HealthVerify.matches(entity, expected) ? chosen : null;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static List<Writer> candidateWriters(LivingEntity entity) {
        List<Writer> out = new ArrayList<>();
        List<Writer> methods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> c = entity.getClass(); c != null && c != LivingEntity.class && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) continue;
                Class<?> input = method.getParameterTypes()[0];
                if (!isMethodInput(input)) continue;
                String key = "M:" + method.getName() + ":" + input.getName();
                if (!seen.add(key)) continue;
                try {
                    method.setAccessible(true);
                    methods.add(new MethodWriter(method));
                } catch (Throwable ignored) {
                    if (ignored instanceof VirtualMachineError e) throw e;
                }
            }
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                SamBinding binding = singleNumericSam(field);
                if (binding == null || !seen.add("F:" + c.getName() + ":" + field.getName())) continue;
                try {
                    field.setAccessible(true);
                    if (field.get(entity) != null) out.add(new FunctionalWriter(field, binding));
                } catch (Throwable ignored) {
                    if (ignored instanceof VirtualMachineError e) throw e;
                }
            }
        }
        out.addAll(methods);
        return out;
    }

    private static SamBinding singleNumericSam(Field field) {
        Class<?> type = field.getType();
        if (type == null || !type.isInterface()) return null;
        Method found = null;
        for (Method method : type.getMethods()) {
            int mods = method.getModifiers();
            if (!Modifier.isAbstract(mods) || Modifier.isStatic(mods) || method.getParameterCount() != 1) continue;
            if (found != null && !sameSignature(found, method)) return null;
            found = method;
        }
        if (found == null) return null;
        Class<?> input = found.getParameterTypes()[0];
        if (!isNumericInput(input)) input = genericNumericInput(field);
        return isNumericInput(input) ? new SamBinding(found, input) : null;
    }

    static boolean supportsFunctionalField(Field field) {
        return singleNumericSam(field) != null;
    }

    private static Class<?> genericNumericInput(Field field) {
        Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType parameterized)) return null;
        for (Type argument : parameterized.getActualTypeArguments()) {
            if (argument instanceof Class<?> clazz && isNumericInput(clazz)) return clazz;
        }
        return null;
    }

    private static boolean sameSignature(Method a, Method b) {
        return a.getName().equals(b.getName())
            && a.getReturnType() == b.getReturnType()
            && a.getParameterTypes()[0] == b.getParameterTypes()[0];
    }

    private static boolean isNumericInput(Class<?> type) {
        return type == float.class || type == double.class || type == int.class || type == long.class
            || type == short.class || type == byte.class || type == Float.class || type == Double.class
            || type == Integer.class || type == Long.class || type == Short.class || type == Byte.class
            || type == Number.class;
    }

    private static boolean isMethodInput(Class<?> type) {
        return type == float.class || type == double.class || type == Float.class
            || type == Double.class || type == Number.class;
    }

    private static Object coerce(float value, Class<?> type) {
        if (type == float.class || type == Float.class || type == Number.class) return value;
        if (type == double.class || type == Double.class) return (double) value;
        if (type == int.class || type == Integer.class) return Math.round(value);
        if (type == long.class || type == Long.class) return (long) Math.round(value);
        if (type == short.class || type == Short.class) return (short) Math.round(value);
        if (type == byte.class || type == Byte.class) return (byte) Math.round(value);
        return value;
    }

    private static float representable(float value, Class<?> type) {
        Object converted = coerce(value, type);
        return converted instanceof Number number ? number.floatValue() : value;
    }

    private static boolean tryApply(LivingEntity entity, Writer writer, float expected) {
        try {
            writer.invoke(entity, expected);
            return HealthVerify.matches(entity, expected);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return false;
        }
    }

    private static void restore(LivingEntity entity, Writer writer, float baseline) {
        try {
            writer.invoke(entity, baseline);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
    }

    private static boolean matches(float actual, float expected) {
        return Float.isFinite(actual) && Math.abs(actual - expected) <= HealthVerify.tolerance(expected);
    }

    private static boolean tooClose(float a, float b) {
        return Math.abs(a - b) < 1.0f;
    }

    private static float safeGetMaxHealth(LivingEntity entity) {
        try {
            return entity.getMaxHealth();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Float.NaN;
        }
    }

    private static HealthWritePlan plan(Writer writer) {
        HealthWritePlan.Mutation mutation = new HealthWritePlan.Mutation() {
            @Override
            public Object snapshot(LivingEntity entity) {
                return HealthVerify.safeGetHealth(entity);
            }

            @Override
            public boolean apply(LivingEntity entity, float target) {
                return tryApply(entity, writer, target);
            }

            @Override
            public boolean restore(LivingEntity entity, Object snapshot) {
                if (!(snapshot instanceof Number number)) return false;
                try {
                    writer.invoke(entity, number.floatValue());
                    return true;
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                    return false;
                }
            }

            @Override
            public String describe() {
                return writer.describe();
            }
        };
        return new HealthWritePlan("behavioral-writer", List.of(mutation));
    }

    private interface Writer {
        void invoke(LivingEntity entity, float value) throws Throwable;

        float representable(float value);

        String describe();
    }

    private record SamBinding(Method method, Class<?> inputType) {}

    private static final class MethodWriter implements Writer {
        private final Method method;
        private final Class<?> inputType;

        private MethodWriter(Method method) {
            this.method = method;
            this.inputType = method.getParameterTypes()[0];
        }

        @Override
        public void invoke(LivingEntity entity, float value) throws Throwable {
            MethodHandle handle = LOOKUP.unreflect(method);
            handle.invokeWithArguments(entity, coerce(value, inputType));
        }

        @Override
        public float representable(float value) {
            return HealthSetterProber.representable(value, inputType);
        }

        @Override
        public String describe() {
            return method.getDeclaringClass().getName() + "#" + method.getName();
        }
    }

    private static final class FunctionalWriter implements Writer {
        private final Field field;
        private final Method sam;
        private final Class<?> inputType;

        private FunctionalWriter(Field field, SamBinding binding) {
            this.field = field;
            this.sam = binding.method();
            this.inputType = binding.inputType();
        }

        @Override
        public void invoke(LivingEntity entity, float value) throws Throwable {
            Object function = field.get(entity);
            if (function == null) throw new IllegalStateException("functional writer is null");
            MethodHandle handle = LOOKUP.unreflect(sam);
            handle.invokeWithArguments(function, coerce(value, inputType));
        }

        @Override
        public float representable(float value) {
            return HealthSetterProber.representable(value, inputType);
        }

        @Override
        public String describe() {
            return field.getDeclaringClass().getName() + "#" + field.getName() + "::" + sam.getName();
        }
    }
}
