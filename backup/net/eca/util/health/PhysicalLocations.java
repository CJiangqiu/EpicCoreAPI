package net.eca.util.health;

import net.eca.coremod.AccessTrace;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class PhysicalLocations {

    private PhysicalLocations() {}

    public static PhysicalLocation field(Object owner, Field field) {
        if (field == null || Modifier.isStatic(field.getModifiers())) return null;
        try {
            field.setAccessible(true);
            return new FieldLocation(owner, field);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    public static PhysicalLocation staticField(Field field) {
        if (field == null || !Modifier.isStatic(field.getModifiers())) return null;
        try {
            field.setAccessible(true);
            return new StaticFieldLocation(field);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    public static PhysicalLocation arrayElement(Object array, int index) {
        if (array == null || !array.getClass().isArray()) return null;
        if (index < 0 || index >= Array.getLength(array)) return null;
        return new ArrayElementLocation(array, index);
    }

    private static final class FieldLocation implements PhysicalLocation {
        private final Object owner;
        private final Field field;

        private FieldLocation(Object owner, Field field) {
            this.owner = owner;
            this.field = field;
        }

        @Override
        public Object read() {
            try {
                return field.get(owner);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return null;
            }
        }

        @Override
        public boolean write(Object value) {
            try {
                field.set(owner, HealthAnalyzer.coerceForType(value, field.getType()));
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return false;
            }
        }

        @Override
        public Class<?> valueType() {
            return field.getType();
        }

        @Override
        public String describe() {
            return owner.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(owner))
                + "#" + field.getName();
        }
    }

    private static final class StaticFieldLocation implements PhysicalLocation {
        private final Field field;

        private StaticFieldLocation(Field field) {
            this.field = field;
        }

        @Override
        public Object read() {
            try {
                return field.get(null);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return null;
            }
        }

        @Override
        public boolean write(Object value) {
            try {
                field.set(null, HealthAnalyzer.coerceForType(value, field.getType()));
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return false;
            }
        }

        @Override
        public Class<?> valueType() {
            return field.getType();
        }

        @Override
        public String describe() {
            return field.getDeclaringClass().getName() + "#" + field.getName();
        }
    }

    private static final class ArrayElementLocation implements PhysicalLocation {
        private final Object array;
        private final int index;
        private final Class<?> valueType;

        private ArrayElementLocation(Object array, int index) {
            this.array = array;
            this.index = index;
            this.valueType = array.getClass().getComponentType();
        }

        @Override
        public Object read() {
            try {
                return Array.get(array, index);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return null;
            }
        }

        @Override
        public boolean write(Object value) {
            try {
                Array.set(array, index, HealthAnalyzer.coerceForType(value, valueType));
                return true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                return false;
            }
        }

        @Override
        public Class<?> valueType() {
            return valueType;
        }

        @Override
        public String describe() {
            return array.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(array))
                + "[" + index + "]";
        }
    }

    /* ==================== 轨迹 → 物理位置解析 ==================== */

    //把一条取证读/写记录解析成可写物理位置：数组下标走数组元素，否则按 site 解析字段
    static PhysicalLocation fromTrace(AccessTrace.Entry entry) {
        if (entry == null) return null;
        return fromTrace(entry.site, entry.container, entry.index);
    }

    static PhysicalLocation fromTrace(AccessTrace.WriteEntry entry) {
        if (entry == null) return null;
        return fromTrace(entry.site, entry.container, entry.index);
    }

    private static PhysicalLocation fromTrace(String site, Object container, long index) {
        if (index >= 0) return arrayElement(container, (int) index);
        Field field = resolveField(site, container);
        if (field == null) return null;
        return Modifier.isStatic(field.getModifiers()) ? staticField(field) : field(container, field);
    }

    private static Field resolveField(String site, Object container) {
        FieldRef ref = parseFieldRef(site);
        if (ref == null) return null;
        try {
            Class<?> owner = Class.forName(ref.owner.replace('/', '.'), false,
                Thread.currentThread().getContextClassLoader());
            Field field = owner.getDeclaredField(ref.name);
            if (!Modifier.isStatic(field.getModifiers()) && container == null) return null;
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    //site 形如 "owner#method owner.field:desc"，取末段的 owner.field
    private static FieldRef parseFieldRef(String site) {
        if (site == null) return null;
        int space = site.lastIndexOf(' ');
        int colon = site.lastIndexOf(':');
        int dot = colon < 0 ? site.lastIndexOf('.') : site.lastIndexOf('.', colon);
        if (space < 0 || dot <= space) return null;
        String owner = site.substring(space + 1, dot);
        String name = site.substring(dot + 1, colon < 0 ? site.length() : colon);
        return owner.isEmpty() || name.isEmpty() ? null : new FieldRef(owner, name);
    }

    private record FieldRef(String owner, String name) {}
}

/**
 * A bound writable JVM heap location. Implementations are limited to storage
 * primitives exposed by JVM bytecode rather than application container types.
 */
interface PhysicalLocation {

    Object read();

    boolean write(Object value);

    Class<?> valueType();

    String describe();

    default Object snapshot() {
        return read();
    }

    default boolean restore(Object snapshot) {
        return write(snapshot);
    }
}
