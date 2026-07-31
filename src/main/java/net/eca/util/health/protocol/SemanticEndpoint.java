package net.eca.util.health.protocol;

import java.util.Objects;

public record SemanticEndpoint(Kind kind, MethodReference method) {
    public SemanticEndpoint {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(method, "method");
    }

    public enum Kind {
        HEALTH_OBSERVATION,
        HEALTH_MUTATION,
        ALIVE_OBSERVATION,
        DYING_OBSERVATION,
        DAMAGE,
        HEAL,
        TICK,
        SAVE,
        LOAD,
        CLIENT_SYNC
    }
}
