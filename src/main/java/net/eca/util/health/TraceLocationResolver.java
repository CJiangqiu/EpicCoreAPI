package net.eca.util.health;

import net.eca.coremod.AccessTrace;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class TraceLocationResolver {

    private TraceLocationResolver() {}

    public static PhysicalLocation resolve(AccessTrace.Entry entry) {
        if (entry == null) return null;
        return resolve(entry.site, entry.container, entry.index);
    }

    public static PhysicalLocation resolve(AccessTrace.WriteEntry entry) {
        if (entry == null) return null;
        return resolve(entry.site, entry.container, entry.index);
    }

    private static PhysicalLocation resolve(String site, Object container, long index) {
        if (index >= 0) return PhysicalLocations.arrayElement(container, (int) index);
        Field field = resolveField(site, container);
        if (field == null) return null;
        return Modifier.isStatic(field.getModifiers())
            ? PhysicalLocations.staticField(field) : PhysicalLocations.field(container, field);
    }

    private static Field resolveField(String site, Object container) {
        FieldRef ref = parseFieldRef(site);
        if (ref == null) return null;
        try {
            Class<?> owner = Class.forName(ref.owner.replace('/', '.'), false,
                Thread.currentThread().getContextClassLoader());
            Field field = owner.getDeclaredField(ref.name);
            if (!Modifier.isStatic(field.getModifiers()) && container == null) return null;
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static FieldRef parseFieldRef(String site) {
        if (site == null) return null;
        int space = site.lastIndexOf(' ');
        int colon = site.lastIndexOf(':');
        int dot = colon < 0 ? site.lastIndexOf('.') : site.lastIndexOf('.', colon);
        if (space < 0 || dot <= space) return null;
        String owner = site.substring(space + 1, dot);
        String name = site.substring(dot + 1, colon < 0 ? site.length() : colon);
        return owner.isEmpty() || name.isEmpty() ? null : new FieldRef(owner, name);
    }

    private record FieldRef(String owner, String name) {}
}
