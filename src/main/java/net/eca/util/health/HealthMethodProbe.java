package net.eca.util.health;

import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HealthMethodProbe {

    private static final ThreadLocal<Boolean> PROBING = ThreadLocal.withInitial(() -> false);

    private HealthMethodProbe() {}

    public static EcaSetHealthManager.HealthPath resolvePath(LivingEntity entity, float target) {
        if (entity == null || PROBING.get()) return null;
        Writer writer = probe(entity, target);
        if (writer == null) return null;
        return new EcaSetHealthManager.HealthPath(EcaSetHealthManager.WriteMethod.METHOD_PROBE,
                (currentEntity, currentTarget) -> tryApply(currentEntity, writer, currentTarget));
    }

    private static Writer probe(LivingEntity entity, float target) {
        float baseline = EcaSetHealthManager.safeGetHealth(entity);
        if (!Float.isFinite(baseline)) return null;

        float probeA = probeValue(baseline, target, 0.5f);
        float probeB = probeValue(baseline, target, 0.25f);
        if (tooClose(probeA, probeB) || tooClose(probeA, baseline) || tooClose(probeB, baseline)) {
            return null;
        }

        PROBING.set(true);
        try {
            List<Writer> candidates = candidateWriters(entity);
            for (Writer writer : candidates) {
                Writer found = testWriter(entity, writer, baseline, probeA, probeB, target);
                if (found != null) {
                    EcaLogger.info("[HealthMethodProbe] succeeded entity={} writer={}",
                            entity.getClass().getName(), found.describe());
                    return found;
                }
            }
            return null;
        } finally {
            PROBING.set(false);
        }
    }

    private static Writer testWriter(LivingEntity entity, Writer writer, float baseline,
                                     float probeA, float probeB, float target) {
        try {
            float a = writer.representable(probeA);
            float b = writer.representable(probeB);
            if (tooClose(a, b) || tooClose(a, baseline) || tooClose(b, baseline)) return null;

            writer.invoke(entity, a);
            float hA = EcaSetHealthManager.safeGetHealth(entity);
            if (!matches(hA, a)) {
                restore(entity, writer, baseline);
                return null;
            }

            writer.invoke(entity, b);
            float hB = EcaSetHealthManager.safeGetHealth(entity);
            if (!matches(hB, b)) {
                restore(entity, writer, baseline);
                return null;
            }

            writer.invoke(entity, baseline);
            if (!EcaSetHealthManager.verify(entity, baseline)) {
                restore(entity, writer, baseline);
                return null;
            }

            writer.invoke(entity, target);
            if (EcaSetHealthManager.verify(entity, target)) {
                return writer;
            }
            restore(entity, writer, baseline);
            return null;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            restore(entity, writer, baseline);
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
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                }
            }
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                SamBinding binding = singleNumericSam(field);
                if (binding == null || !seen.add("F:" + c.getName() + ":" + field.getName())) continue;
                try {
                    field.setAccessible(true);
                    if (field.get(entity) != null) {
                        out.add(new FunctionalWriter(field, binding));
                    }
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
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

    private static Class<?> genericNumericInput(Field field) {
        Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType parameterized)) return null;
        for (Type argument : parameterized.getActualTypeArguments()) {
            if (argument instanceof Class<?> clazz && isNumericInput(clazz)) {
                return clazz;
            }
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

    private static boolean tryApply(LivingEntity entity, Writer writer, float target) {
        try {
            writer.invoke(entity, target);
            return EcaSetHealthManager.verify(entity, target);
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

    private static float probeValue(float baseline, float target, float factor) {
        float value = baseline * factor;
        if (tooClose(value, baseline) || tooClose(value, target)) {
            value = baseline + Math.max(2.0f, Math.abs(baseline) * factor);
        }
        return value;
    }

    private static boolean matches(float actual, float expected) {
        if (!Float.isFinite(actual)) return false;
        float tolerance = Math.max(0.5f, Math.abs(expected) * 0.02f);
        return Math.abs(actual - expected) <= tolerance;
    }

    private static boolean tooClose(float a, float b) {
        return Math.abs(a - b) < 1.0f;
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
            method.invoke(entity, coerce(value, inputType));
        }

        @Override
        public float representable(float value) {
            return HealthMethodProbe.representable(value, inputType);
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
            sam.invoke(function, coerce(value, inputType));
        }

        @Override
        public float representable(float value) {
            return HealthMethodProbe.representable(value, inputType);
        }

        @Override
        public String describe() {
            return field.getDeclaringClass().getName() + "#" + field.getName() + "::" + sam.getName();
        }
    }
}
