package net.eca.util.health;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthSetterProberTest {

    private static final class Fixture {
        private Consumer<Float> writer = value -> {};
        private Supplier<Float> reader = () -> 20.0f;
    }

    @Test
    void recoversNumericInputFromErasedFunctionalSignature() throws Exception {
        Field writer = Fixture.class.getDeclaredField("writer");
        Field reader = Fixture.class.getDeclaredField("reader");
        assertTrue(HealthSetterProber.supportsFunctionalField(writer));
        assertFalse(HealthSetterProber.supportsFunctionalField(reader));
    }
}
