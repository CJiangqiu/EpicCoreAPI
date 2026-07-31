package net.eca.util.health.protocol;

import net.eca.util.health.LifeProtocolAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolModelTest {
    private static final String ENTITY = "example/Entity";

    @Test
    void transactionRejectsUnsnapshottedWrites() {
        StateLocation state = healthField();
        ValueExpression value = new ValueExpression.Constant(10.0f, "F");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new MutationTransaction(List.of(),
                        List.of(new MutationTransaction.WriteAction(state, value))));

        assertTrue(error.getMessage().contains("rollback snapshot"));
    }

    @Test
    void validationPlanRequiresImmediateReadback() {
        assertThrows(IllegalArgumentException.class,
                () -> new ValidationPlan(List.of(
                        new ValidationPlan.Check(ValidationPlan.Stage.AFTER_TICK, 1, true))));
    }

    @Test
    void candidateDoesNotTreatContradictionAsAuthority() {
        StateLocation state = healthField();
        ProtocolEvidence evidence = evidence(state, ProtocolEvidence.Kind.CONTRADICTION,
                ProtocolEvidence.Direction.RUNTIME_OBSERVATION, ProtocolEvidence.Strength.DISQUALIFYING);

        CandidateState candidate = new CandidateState(state, List.of(evidence));

        assertTrue(candidate.isDisqualified());
    }

    @Test
    void resolvedAnalysisMustContainProtocol() {
        assertThrows(IllegalArgumentException.class,
                () -> new LifeProtocolAnalyzer.AnalysisResult(
                        LifeProtocolAnalyzer.AnalysisResult.Status.RESOLVED,
                        List.of(), Optional.empty(), ""));
    }

    @Test
    void protocolRejectsOneWayAuthorityEvidence() {
        StateLocation state = healthField();
        ValueExpression read = new ValueExpression.StateRead(state, "F");
        MutationTransaction transaction = new MutationTransaction(List.of(state),
                List.of(new MutationTransaction.WriteAction(state,
                        new ValueExpression.Parameter(0, "F"))));
        ValidationPlan validation = new ValidationPlan(List.of(
                new ValidationPlan.Check(ValidationPlan.Stage.IMMEDIATE_READBACK, 0, true)));
        ProtocolEvidence backward = evidence(state, ProtocolEvidence.Kind.READ_DEPENDENCY,
                ProtocolEvidence.Direction.BACKWARD_SLICE, ProtocolEvidence.Strength.REQUIRED);

        assertThrows(IllegalArgumentException.class,
                () -> new LifeProtocol(ENTITY, new LifeProtocol.Fingerprint("class", "protocol"), read,
                        List.of(state), transaction, validation, List.of(), List.of(backward)));
    }

    @Test
    void dynamicInvocationDoesNotRequireReceiver() {
        MethodReference method = new MethodReference(ENTITY, "bootstrap", "()F",
                MethodReference.InvocationKind.DYNAMIC);

        ValueExpression.Invocation invocation = new ValueExpression.Invocation(method, null, List.of(), "F");

        assertEquals(method, invocation.method());
    }

    @Test
    void accessPathsAreExtendedWithoutMutation() {
        AccessPath original = AccessPath.entity(ENTITY);
        AccessPath extended = original.append(new AccessPath.FieldStep(ENTITY, "state", "F", false));

        assertEquals(0, original.steps().size());
        assertEquals(1, extended.steps().size());
    }

    private static StateLocation healthField() {
        return new StateLocation.FieldState(AccessPath.entity(ENTITY), ENTITY, "health", "F", false);
    }

    private static ProtocolEvidence evidence(StateLocation state, ProtocolEvidence.Kind kind,
                                             ProtocolEvidence.Direction direction,
                                             ProtocolEvidence.Strength strength) {
        MethodReference method = new MethodReference(ENTITY, "getHealth", "()F",
                MethodReference.InvocationKind.VIRTUAL);
        SemanticEndpoint endpoint = new SemanticEndpoint(SemanticEndpoint.Kind.HEALTH_OBSERVATION, method);
        return new ProtocolEvidence(state, endpoint, kind, direction, strength,
                new ProtocolEvidence.Provenance(method, 0), "test evidence");
    }
}
