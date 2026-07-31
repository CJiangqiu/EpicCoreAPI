package net.eca.util.health.protocol;

import java.util.Objects;

public record ProtocolEvidence(StateLocation location, SemanticEndpoint endpoint, Kind kind, Direction direction,
                               Strength strength, Provenance provenance, String explanation) {
    public ProtocolEvidence {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(strength, "strength");
        Objects.requireNonNull(provenance, "provenance");
        if (explanation == null || explanation.isBlank()) {
            throw new IllegalArgumentException("evidence requires an explanation");
        }
    }

    public boolean contradictsAuthority() {
        return kind == Kind.CONTRADICTION;
    }

    public record Provenance(MethodReference method, int instructionIndex) {
        public Provenance {
            Objects.requireNonNull(method, "method");
            if (instructionIndex < -1) {
                throw new IllegalArgumentException("instruction index cannot be less than -1");
            }
        }
    }

    public enum Kind {
        READ_DEPENDENCY,
        WRITE_PROPAGATION,
        DAMAGE_EFFECT,
        HEAL_EFFECT,
        TICK_STABILITY,
        SAVE_PROPAGATION,
        LOAD_RESTORATION,
        CLIENT_SYNC,
        LIFECYCLE_EFFECT,
        CONTRADICTION
    }

    public enum Direction {
        BACKWARD_SLICE,
        FORWARD_PROPAGATION,
        RUNTIME_OBSERVATION
    }

    public enum Strength {
        SUPPORTING,
        REQUIRED,
        DISQUALIFYING
    }
}
