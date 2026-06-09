package net.eca.util.health;

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
}
