package net.eca.util.health.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record AccessPath(Root root, List<Step> steps) {
    public AccessPath {
        Objects.requireNonNull(root, "root");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }

    public static AccessPath entity(String entityInternalName) {
        return new AccessPath(new Root(RootKind.ENTITY, entityInternalName, -1), List.of());
    }

    public AccessPath append(Step step) {
        List<Step> expanded = new ArrayList<>(steps);
        expanded.add(Objects.requireNonNull(step, "step"));
        return new AccessPath(root, expanded);
    }

    public record Root(RootKind kind, String typeInternalName, int argumentIndex) {
        public Root {
            Objects.requireNonNull(kind, "kind");
            requireText(typeInternalName, "typeInternalName");
            if (kind == RootKind.METHOD_ARGUMENT && argumentIndex < 0) {
                throw new IllegalArgumentException("method argument roots require a non-negative index");
            }
            if (kind != RootKind.METHOD_ARGUMENT && argumentIndex != -1) {
                throw new IllegalArgumentException("only method argument roots may carry an argument index");
            }
        }
    }

    public sealed interface Step permits FieldStep, MethodStep, ArrayStep, MapStep, CapabilityStep, SavedDataStep {
    }

    public record FieldStep(String ownerInternalName, String name, String descriptor, boolean staticField)
            implements Step {
        public FieldStep {
            requireText(ownerInternalName, "ownerInternalName");
            requireText(name, "name");
            requireText(descriptor, "descriptor");
        }
    }

    public record MethodStep(MethodReference method, List<ValueExpression> arguments) implements Step {
        public MethodStep {
            Objects.requireNonNull(method, "method");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
        }
    }

    public record ArrayStep(ValueExpression index) implements Step {
        public ArrayStep {
            Objects.requireNonNull(index, "index");
        }
    }

    public record MapStep(ValueExpression key) implements Step {
        public MapStep {
            Objects.requireNonNull(key, "key");
        }
    }

    public record CapabilityStep(String capabilityIdentity) implements Step {
        public CapabilityStep {
            requireText(capabilityIdentity, "capabilityIdentity");
        }
    }

    public record SavedDataStep(String dataInternalName, ValueExpression identityKey) implements Step {
        public SavedDataStep {
            requireText(dataInternalName, "dataInternalName");
            Objects.requireNonNull(identityKey, "identityKey");
        }
    }

    public enum RootKind {
        ENTITY,
        LEVEL,
        SERVER,
        STATIC,
        METHOD_ARGUMENT,
        RETURN_VALUE
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }
}
