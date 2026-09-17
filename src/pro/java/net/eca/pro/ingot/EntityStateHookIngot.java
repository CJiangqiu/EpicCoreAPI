package net.eca.pro.ingot;

import net.eca.coremod.EcaClassTransformer;

import java.util.Set;

final class EntityStateHookIngot implements ProIngot {
    private static final Set<String> TARGETS = Set.of(
            "net/minecraft/world/entity/Entity",
            "net/minecraft/world/entity/LivingEntity"
    );

    @Override
    public String name() {
        return "entity_state_hook";
    }

    @Override
    public Set<String> targets() {
        return TARGETS;
    }

    @Override
    public byte[] transform(String internalName, byte[] classBytes) {
        byte[] transformed = EcaClassTransformer.transformHealthTail(internalName, classBytes);
        return transformed == null ? classBytes : transformed;
    }

    @Override
    public boolean verify(String internalName, byte[] classBytes) {
        return EcaClassTransformer.verifyHealthTail(internalName, classBytes);
    }
}
