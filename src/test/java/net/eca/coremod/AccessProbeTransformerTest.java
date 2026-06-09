package net.eca.coremod;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AccessProbeTransformerTest {

    @Test
    void recordsReadsWritesAndMethodExitWithoutChangingBehavior() throws Exception {
        String internal = ProbeFixture.class.getName().replace('.', '/');
        byte[] original;
        try (InputStream input = ProbeFixture.class.getClassLoader().getResourceAsStream(internal + ".class")) {
            original = input.readAllBytes();
        }

        Constructor<AccessProbeTransformer> constructor = AccessProbeTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        AccessProbeTransformer transformer = constructor.newInstance();
        AccessProbeTransformer.setTargets(Set.of(internal));
        byte[] transformed = transformer.transform(ProbeFixture.class.getClassLoader(), internal, null, null, original);
        AccessProbeTransformer.clearTargets();

        Class<?> fixtureClass = new ByteArrayLoader().define(ProbeFixture.class.getName(), transformed);
        Object fixture = fixtureClass.getDeclaredConstructor().newInstance();
        Method update = fixtureClass.getMethod("update", int.class);

        AccessTrace.begin();
        Object result;
        try {
            result = update.invoke(fixture, 9);
            assertFalse(AccessTrace.reads().isEmpty());
            assertFalse(AccessTrace.writes().isEmpty());
            assertFalse(AccessTrace.exits().isEmpty());
        } finally {
            AccessTrace.finish();
        }
        assertEquals(19, result);
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private ByteArrayLoader() {
            super(AccessProbeTransformerTest.class.getClassLoader());
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
