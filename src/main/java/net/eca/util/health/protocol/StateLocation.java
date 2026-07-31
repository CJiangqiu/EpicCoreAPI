package net.eca.util.health.protocol;

import java.util.Objects;

public sealed interface StateLocation permits StateLocation.FieldState, StateLocation.SynchedDataState,
        StateLocation.ArrayState, StateLocation.MapState, StateLocation.CapabilityState,
        StateLocation.NbtState, StateLocation.SavedDataState, StateLocation.MethodState {

    String descriptor();

    record FieldState(AccessPath receiver, String ownerInternalName, String name, String descriptor,
                      boolean staticField) implements StateLocation {
        public FieldState {
            Objects.requireNonNull(receiver, "receiver");
            requireText(ownerInternalName, "ownerInternalName");
            requireText(name, "name");
            requireText(descriptor, "descriptor");
        }
    }

    record SynchedDataState(AccessPath holder, String accessorOwnerInternalName, String accessorFieldName,
                            Integer runtimeAccessorId, String descriptor) implements StateLocation {
        public SynchedDataState {
            Objects.requireNonNull(holder, "holder");
            requireText(accessorOwnerInternalName, "accessorOwnerInternalName");
            if ((accessorFieldName == null || accessorFieldName.isBlank()) && runtimeAccessorId == null) {
                throw new IllegalArgumentException("synched data state requires a symbolic field or runtime ID");
            }
            if (runtimeAccessorId != null && runtimeAccessorId < 0) {
                throw new IllegalArgumentException("runtime accessor ID cannot be negative");
            }
            requireText(descriptor, "descriptor");
        }
    }

    record ArrayState(AccessPath container, ValueExpression index, String descriptor) implements StateLocation {
        public ArrayState {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(index, "index");
            requireText(descriptor, "descriptor");
        }
    }

    record MapState(AccessPath container, ValueExpression key, String descriptor) implements StateLocation {
        public MapState {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(key, "key");
            requireText(descriptor, "descriptor");
        }
    }

    record NbtState(AccessPath container, ValueExpression key, String descriptor) implements StateLocation {
        public NbtState {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(key, "key");
            requireText(descriptor, "descriptor");
        }
    }

    record CapabilityState(AccessPath provider, String capabilityIdentity, AccessPath valuePath,
                           String descriptor) implements StateLocation {
        public CapabilityState {
            Objects.requireNonNull(provider, "provider");
            requireText(capabilityIdentity, "capabilityIdentity");
            Objects.requireNonNull(valuePath, "valuePath");
            requireText(descriptor, "descriptor");
        }
    }

    record SavedDataState(AccessPath level, String dataInternalName, ValueExpression identityKey,
                          AccessPath valuePath, String descriptor) implements StateLocation {
        public SavedDataState {
            Objects.requireNonNull(level, "level");
            requireText(dataInternalName, "dataInternalName");
            Objects.requireNonNull(identityKey, "identityKey");
            Objects.requireNonNull(valuePath, "valuePath");
            requireText(descriptor, "descriptor");
        }
    }

    record MethodState(AccessPath receiver, MethodReference reader, MethodReference writer,
                       String descriptor) implements StateLocation {
        public MethodState {
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(reader, "reader");
            requireText(descriptor, "descriptor");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }
}
