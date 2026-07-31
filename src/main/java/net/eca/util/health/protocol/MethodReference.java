package net.eca.util.health.protocol;

import java.util.Objects;

public record MethodReference(String ownerInternalName, String name, String descriptor, InvocationKind invocationKind) {
    public MethodReference {
        requireText(ownerInternalName, "ownerInternalName");
        requireText(name, "name");
        requireText(descriptor, "descriptor");
        Objects.requireNonNull(invocationKind, "invocationKind");
    }

    public boolean requiresReceiver() {
        return invocationKind == InvocationKind.VIRTUAL
                || invocationKind == InvocationKind.INTERFACE
                || invocationKind == InvocationKind.SPECIAL;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }

    public enum InvocationKind {
        VIRTUAL,
        INTERFACE,
        SPECIAL,
        STATIC,
        DYNAMIC
    }
}
