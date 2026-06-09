package net.eca.util.health;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalLocationsTest {

    private static final class Holder {
        private int value = 7;
        private static long shared = 11L;
    }

    @Test
    void readsWritesAndRestoresJvmLocations() throws Exception {
        Holder holder = new Holder();
        Field value = Holder.class.getDeclaredField("value");
        PhysicalLocation field = PhysicalLocations.field(holder, value);
        assertNotNull(field);
        Object snapshot = field.snapshot();
        assertTrue(field.write(19));
        assertEquals(19, holder.value);
        assertTrue(field.restore(snapshot));
        assertEquals(7, holder.value);

        Field shared = Holder.class.getDeclaredField("shared");
        PhysicalLocation staticField = PhysicalLocations.staticField(shared);
        assertNotNull(staticField);
        assertTrue(staticField.write(23L));
        assertEquals(23L, Holder.shared);

        int[] values = {3, 5, 8};
        PhysicalLocation element = PhysicalLocations.arrayElement(values, 1);
        assertNotNull(element);
        assertTrue(element.write(13));
        assertEquals(13, values[1]);
    }
}
