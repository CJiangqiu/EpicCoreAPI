package net.eca.util.health;

import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

public final class HealthWritePlan {

    public interface Mutation {
        Object snapshot(LivingEntity entity);

        boolean apply(LivingEntity entity, float target);

        boolean restore(LivingEntity entity, Object snapshot);

        String describe();
    }

    private final String kind;
    private final List<Mutation> mutations;

    public HealthWritePlan(String kind, List<Mutation> mutations) {
        this.kind = kind;
        this.mutations = List.copyOf(mutations);
    }

    public String kind() {
        return kind;
    }

    public List<Mutation> mutations() {
        return mutations;
    }

    public boolean execute(LivingEntity entity, float target) {
        if (entity == null || mutations.isEmpty()) return false;
        List<Object> snapshots = new ArrayList<>(mutations.size());
        for (Mutation mutation : mutations) snapshots.add(mutation.snapshot(entity));

        boolean applied = true;
        for (Mutation mutation : mutations) {
            if (!mutation.apply(entity, target)) {
                applied = false;
                break;
            }
        }
        if (applied && HealthVerify.matches(entity, target)) return true;

        for (int i = mutations.size() - 1; i >= 0; i--) {
            mutations.get(i).restore(entity, snapshots.get(i));
        }
        return false;
    }

    public String describe() {
        StringBuilder out = new StringBuilder(kind);
        for (Mutation mutation : mutations) out.append(" -> ").append(mutation.describe());
        return out.toString();
    }
}
