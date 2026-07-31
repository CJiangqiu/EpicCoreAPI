package net.eca.util.health.protocol;

import java.util.List;
import java.util.Objects;

public sealed interface ValueExpression permits ValueExpression.Constant, ValueExpression.Parameter,
        ValueExpression.StateRead, ValueExpression.Unary, ValueExpression.Binary, ValueExpression.Conditional,
        ValueExpression.Invocation, ValueExpression.Merge {

    String descriptor();

    record Constant(Object value, String descriptor) implements ValueExpression {
        public Constant {
            requireDescriptor(descriptor);
        }
    }

    record Parameter(int index, String descriptor) implements ValueExpression {
        public Parameter {
            if (index < 0) {
                throw new IllegalArgumentException("parameter index cannot be negative");
            }
            requireDescriptor(descriptor);
        }
    }

    record StateRead(StateLocation location, String descriptor) implements ValueExpression {
        public StateRead {
            Objects.requireNonNull(location, "location");
            requireDescriptor(descriptor);
        }
    }

    record Unary(UnaryOperator operator, ValueExpression operand, String descriptor) implements ValueExpression {
        public Unary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
            requireDescriptor(descriptor);
        }
    }

    record Binary(BinaryOperator operator, ValueExpression left, ValueExpression right, String descriptor)
            implements ValueExpression {
        public Binary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            requireDescriptor(descriptor);
        }
    }

    record Conditional(ValueExpression condition, ValueExpression whenTrue, ValueExpression whenFalse,
                       String descriptor) implements ValueExpression {
        public Conditional {
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(whenTrue, "whenTrue");
            Objects.requireNonNull(whenFalse, "whenFalse");
            requireDescriptor(descriptor);
        }
    }

    record Invocation(MethodReference method, ValueExpression receiver, List<ValueExpression> arguments,
                      String descriptor) implements ValueExpression {
        public Invocation {
            Objects.requireNonNull(method, "method");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            requireDescriptor(descriptor);
            if (method.requiresReceiver() && receiver == null) {
                throw new IllegalArgumentException("non-static invocation requires a receiver");
            }
        }
    }

    record Merge(List<ValueExpression> alternatives, String descriptor) implements ValueExpression {
        public Merge {
            alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
            if (alternatives.isEmpty()) {
                throw new IllegalArgumentException("merge requires at least one alternative");
            }
            requireDescriptor(descriptor);
        }
    }

    enum UnaryOperator {
        NEGATE,
        NOT,
        CAST,
        DECODE,
        ENCODE
    }

    enum BinaryOperator {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        REMAINDER,
        AND,
        OR,
        XOR,
        SHIFT_LEFT,
        SHIFT_RIGHT,
        EQUAL,
        NOT_EQUAL,
        LESS_THAN,
        LESS_OR_EQUAL,
        GREATER_THAN,
        GREATER_OR_EQUAL
    }

    private static void requireDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            throw new IllegalArgumentException("descriptor cannot be blank");
        }
    }
}
