package net.eca.util.health;

/**
 * A bound writable JVM heap location. Implementations are limited to storage
 * primitives exposed by JVM bytecode rather than application container types.
 */
public interface PhysicalLocation {

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
