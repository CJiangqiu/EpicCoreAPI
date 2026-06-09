package net.eca.util.health;

import net.eca.util.health.HealthAnalyzer.Call;
import net.eca.util.health.HealthAnalyzer.EvalContext;
import net.eca.util.health.HealthAnalyzer.Expr;
import net.eca.util.health.HealthAnalyzer.Op;
import net.eca.util.health.HealthAnalyzer.Primitive;
import net.eca.util.health.HealthAnalyzer.Reference;
import net.eca.util.health.HealthAnalyzer.Source;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthAnalyzerInversionTest {

    private static final int KEY = -115900097;
    private static final EvalContext NO_ENTITY = new EvalContext() {
        @Override
        public Object eval(Expr expression) {
            return HealthAnalyzer.evaluate(expression, this);
        }

        @Override
        public LivingEntity entity() {
            return null;
        }
    };

    @Test
    void invertsStringXorAndFloatBitChain() {
        Source source = new MemorySource("SSH@00000000");
        Expr stripped = new Call("java/lang/String", "replace",
            "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;",
            List.of(source, new Reference("SSH@", "java/lang/String"), new Reference("", "java/lang/String")));
        Expr parsed = new Call("java/lang/Long", "parseLong", "(Ljava/lang/String;I)J",
            List.of(stripped, new Primitive(16, 'I')));
        Expr narrowed = new Op(Opcodes.L2I, List.of(parsed));
        Expr xor = new Op(Opcodes.IXOR, List.of(narrowed, new Primitive(KEY, 'I')));
        Expr health = new Call("java/lang/Float", "intBitsToFloat", "(I)F", List.of(xor));

        HealthSolveResult result = HealthAnalyzer.solveDetailed(health, source, 37.5f, NO_ENTITY);
        assertTrue(result.solved(), result::detail);
        String encoded = result.value().toString();
        int bits = (int) Long.parseLong(encoded.replace("SSH@", ""), 16) ^ KEY;
        assertEquals(37.5f, Float.intBitsToFloat(bits));
    }

    @Test
    void reportsRepeatedSourceInsteadOfGenericNull() {
        Source source = new MemorySource(2.0f);
        Expr expression = new Op(Opcodes.FADD, List.of(source, source));
        HealthSolveResult result = HealthAnalyzer.solveDetailed(expression, source, 10.0f, NO_ENTITY);
        assertEquals(HealthSolveFailure.MULTI_LOCATION_UNSUPPORTED, result.failure());
    }

    @Test
    void followsSupplierFieldIntoLambdaImplementation() {
        Expr expression = HealthAnalyzer.analyzeSeeded(LambdaFixture.class, "read", "()F",
            new Expr[]{HealthAnalyzer.thisMarker()});
        Set<Source> sources = HealthAnalyzer.collectSources(expression);
        assertEquals(1, sources.size());
        Source source = sources.iterator().next();
        assertTrue(source.label.contains("encoded"), source.label);

        HealthSolveResult result = HealthAnalyzer.solveDetailed(expression, source, 1.0f, NO_ENTITY);
        assertTrue(result.solved(), result::detail);
        String encoded = result.value().toString();
        int bits = (int) Long.parseLong(encoded.replace("SSH@", ""), 16) ^ KEY;
        assertEquals(1.0f, Float.intBitsToFloat(bits));
    }

    @Test
    void followsInheritedSupplierFieldIntoBaseConstructorLambda() {
        Expr expression = HealthAnalyzer.analyzeSeeded(InheritedLambdaFixture.class, "read", "()F",
            new Expr[]{HealthAnalyzer.thisMarker()});
        Set<Source> sources = HealthAnalyzer.collectSources(expression);
        assertEquals(1, sources.size());
        Source source = sources.iterator().next();
        assertTrue(source.label.contains("encoded"), source.label);

        HealthSolveResult result = HealthAnalyzer.solveDetailed(expression, source, 5.0f, NO_ENTITY);
        assertTrue(result.solved(), result::detail);
        String encoded = result.value().toString();
        int bits = (int) Long.parseLong(encoded.replace("SSH@", ""), 16) ^ KEY;
        assertEquals(5.0f, Float.intBitsToFloat(bits));
    }

    @Test
    void resolvesInheritedFieldThroughGetField() {
        Expr expression = HealthAnalyzer.analyzeSeeded(InheritedReflectionFieldFixture.class, "read", "()F",
            new Expr[]{HealthAnalyzer.thisMarker()});
        Set<Source> sources = HealthAnalyzer.collectSources(expression);
        assertEquals(1, sources.size());
        Source source = sources.iterator().next();
        assertTrue(source.label.contains("encoded"), source.label);

        HealthSolveResult result = HealthAnalyzer.solveDetailed(expression, source, 6.5f, NO_ENTITY);
        assertTrue(result.solved(), result::detail);
        String encoded = result.value().toString();
        int bits = (int) Long.parseLong(encoded.replace("SSH@", ""), 16) ^ KEY;
        assertEquals(6.5f, Float.intBitsToFloat(bits));
    }

    @Test
    void resolvesInheritedMethodThroughGetMethod() {
        Expr expression = HealthAnalyzer.analyzeSeeded(InheritedReflectionMethodFixture.class, "read", "()F",
            new Expr[]{HealthAnalyzer.thisMarker()});
        Set<Source> sources = HealthAnalyzer.collectSources(expression);
        assertEquals(1, sources.size());
        Source source = sources.iterator().next();
        assertTrue(source.label.contains("encoded"), source.label);

        HealthSolveResult result = HealthAnalyzer.solveDetailed(expression, source, 7.25f, NO_ENTITY);
        assertTrue(result.solved(), result::detail);
        String encoded = result.value().toString();
        int bits = (int) Long.parseLong(encoded.replace("SSH@", ""), 16) ^ KEY;
        assertEquals(7.25f, Float.intBitsToFloat(bits));
    }

    private static final class MemorySource extends Source {
        private Object value;

        private MemorySource(Object value) {
            super(value.getClass(), "memory");
            this.value = value;
        }

        @Override
        public Object read(LivingEntity entity) {
            return value;
        }

        @Override
        public boolean write(LivingEntity entity, Object value) {
            this.value = value;
            return true;
        }

        @Override
        protected String canonicalKey() {
            return "memory";
        }
    }

    private static final class LambdaFixture {
        private String encoded = "SSH@B8B7813F";
        private final Supplier<Float> reader = this::decode;

        private float read() {
            return reader.get();
        }

        private Float decode() {
            String value = encoded.replace("SSH@", "");
            int bits = (int) Long.parseLong(value, 16) ^ KEY;
            return Float.intBitsToFloat(bits);
        }
    }

    private abstract static class LambdaBaseFixture {
        protected String encoded = "SSH@B8B7813F";
        protected final Supplier<Float> getHealth = this::decode;

        private Float decode() {
            String value = encoded.replace("SSH@", "");
            int bits = (int) Long.parseLong(value, 16) ^ KEY;
            return Float.intBitsToFloat(bits);
        }
    }

    private static final class InheritedLambdaFixture extends LambdaBaseFixture {
        private float read() {
            return getHealth.get();
        }
    }

    private abstract static class ReflectionBaseFixture {
        public String encoded = "SSH@B8B7813F";

        public Float decodePublic() {
            String value = encoded.replace("SSH@", "");
            int bits = (int) Long.parseLong(value, 16) ^ KEY;
            return Float.intBitsToFloat(bits);
        }
    }

    private static final class InheritedReflectionFieldFixture extends ReflectionBaseFixture {
        private float read() {
            try {
                String value = (String) InheritedReflectionFieldFixture.class.getField("encoded").get(this);
                int bits = (int) Long.parseLong(value.replace("SSH@", ""), 16) ^ KEY;
                return Float.intBitsToFloat(bits);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class InheritedReflectionMethodFixture extends ReflectionBaseFixture {
        private float read() {
            try {
                return ((Float) InheritedReflectionMethodFixture.class.getMethod("decodePublic").invoke(this)).floatValue();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
