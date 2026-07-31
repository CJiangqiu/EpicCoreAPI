package net.eca.util.health.protocol;

import java.util.List;
import java.util.Objects;

public record LifeProtocol(String entityInternalName, Fingerprint fingerprint, ValueExpression authoritativeRead,
                           List<StateLocation> authoritativeStates, MutationTransaction writeTransaction,
                           ValidationPlan validationPlan, List<LifecycleConstraint> lifecycleConstraints,
                           List<ProtocolEvidence> evidence) {
    public LifeProtocol {
        requireText(entityInternalName, "entityInternalName");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(authoritativeRead, "authoritativeRead");
        authoritativeStates = List.copyOf(Objects.requireNonNull(authoritativeStates, "authoritativeStates"));
        if (authoritativeStates.isEmpty()) {
            throw new IllegalArgumentException("life protocol requires an authoritative state");
        }
        Objects.requireNonNull(writeTransaction, "writeTransaction");
        Objects.requireNonNull(validationPlan, "validationPlan");
        lifecycleConstraints = List.copyOf(Objects.requireNonNull(lifecycleConstraints, "lifecycleConstraints"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("life protocol cannot exist without evidence");
        }
        if (evidence.stream().anyMatch(ProtocolEvidence::contradictsAuthority)) {
            throw new IllegalArgumentException("contradicted candidates cannot form a life protocol");
        }
        for (StateLocation state : authoritativeStates) {
            if (!hasRequiredDirection(evidence, state, ProtocolEvidence.Direction.BACKWARD_SLICE)
                    || !hasRequiredDirection(evidence, state, ProtocolEvidence.Direction.FORWARD_PROPAGATION)) {
                throw new IllegalArgumentException(
                        "authoritative states require both backward and forward required evidence");
            }
        }
    }

    public record Fingerprint(String classStructure, String protocolStructure) {
        public Fingerprint {
            requireText(classStructure, "classStructure");
            requireText(protocolStructure, "protocolStructure");
        }
    }

    public record LifecycleConstraint(Kind kind, StateLocation controllingState, String explanation) {
        public LifecycleConstraint {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(controllingState, "controllingState");
            requireText(explanation, "explanation");
        }
    }

    public enum Kind {
        MUST_REMAIN_ALIVE,
        MUST_NOT_ENTER_DYING,
        TICK_REWRITES_STATE,
        EXTERNAL_CHECKPOINT_RESTORES_STATE,
        REMOVAL_INVALIDATES_BINDING
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
    }

    private static boolean hasRequiredDirection(List<ProtocolEvidence> evidence, StateLocation state,
                                                ProtocolEvidence.Direction direction) {
        return evidence.stream().anyMatch(item -> item.location().equals(state)
                && item.direction() == direction
                && item.strength() == ProtocolEvidence.Strength.REQUIRED);
    }
}
