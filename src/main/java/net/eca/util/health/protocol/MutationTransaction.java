package net.eca.util.health.protocol;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record MutationTransaction(List<StateLocation> snapshotLocations, List<Action> actions) {
    public MutationTransaction {
        snapshotLocations = List.copyOf(Objects.requireNonNull(snapshotLocations, "snapshotLocations"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("mutation transaction requires at least one action");
        }
        Set<StateLocation> snapshots = new HashSet<>(snapshotLocations);
        for (Action action : actions) {
            if (!snapshots.containsAll(action.affectedStates())) {
                throw new IllegalArgumentException("every affected state must be included in the rollback snapshot");
            }
        }
    }

    public sealed interface Action permits WriteAction, InvokeAction {
        List<StateLocation> affectedStates();
    }

    public record WriteAction(StateLocation target, ValueExpression value) implements Action {
        public WriteAction {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public List<StateLocation> affectedStates() {
            return List.of(target);
        }
    }

    public record InvokeAction(MethodReference method, ValueExpression receiver, List<ValueExpression> arguments,
                               List<StateLocation> affectedStates) implements Action {
        public InvokeAction {
            Objects.requireNonNull(method, "method");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            affectedStates = List.copyOf(Objects.requireNonNull(affectedStates, "affectedStates"));
            if (affectedStates.isEmpty()) {
                throw new IllegalArgumentException("mutating invocation must declare its affected states");
            }
            if (method.requiresReceiver() && receiver == null) {
                throw new IllegalArgumentException("non-static invocation requires a receiver");
            }
        }
    }
}
