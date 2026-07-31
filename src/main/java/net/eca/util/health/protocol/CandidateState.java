package net.eca.util.health.protocol;

import java.util.List;
import java.util.Objects;

public record CandidateState(StateLocation location, List<ProtocolEvidence> evidence) {
    public CandidateState {
        Objects.requireNonNull(location, "location");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.stream().anyMatch(item -> !item.location().equals(location))) {
            throw new IllegalArgumentException("candidate evidence must describe the same state location");
        }
    }

    public boolean hasRequiredEvidence(ProtocolEvidence.Kind kind, ProtocolEvidence.Direction direction) {
        return evidence.stream().anyMatch(item -> item.kind() == kind
                && item.direction() == direction
                && item.strength() == ProtocolEvidence.Strength.REQUIRED);
    }

    public boolean isDisqualified() {
        return evidence.stream().anyMatch(item -> item.strength() == ProtocolEvidence.Strength.DISQUALIFYING
                || item.contradictsAuthority());
    }
}
