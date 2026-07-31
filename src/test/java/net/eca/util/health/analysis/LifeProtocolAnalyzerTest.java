package net.eca.util.health.analysis;

import net.eca.util.health.LifeProtocolAnalyzer;
import net.eca.util.health.internal.ProtocolDataflowAnalyzer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifeProtocolAnalyzerTest {
    @Test
    void discoversFieldBehindHealthObservation() {
        LifeProtocolAnalyzer.AnalysisResult result =
                new LifeProtocolAnalyzer().analyze(SyntheticEntity.class);

        assertEquals(LifeProtocolAnalyzer.AnalysisResult.Status.AMBIGUOUS, result.status());
        assertFalse(result.candidates().isEmpty());
    }

    @Test
    void includesLifecycleWriterDependenciesInTransactionGraph() {
        LifeProtocolAnalyzer analyzer = new LifeProtocolAnalyzer();

        var graph = analyzer.protocolGraph(MaintainedEntity.class);

        assertTrue(graph.hasMaintenanceWriter());
        assertTrue(graph.dependencySources().size() >= 2);
        assertTrue(graph.transactionSources().size() >= 2);
    }

    @Test
    void representsPersistentTagValuesAsWritableStates() {
        LifeProtocolAnalyzer analyzer = new LifeProtocolAnalyzer();

        var graph = analyzer.protocolGraph(PersistentEntity.class);

        assertTrue(graph.dependencySources().stream()
                .anyMatch(ProtocolDataflowAnalyzer.NbtValueSource.class::isInstance));
    }

    @Test
    void followsEntityIdentityIntoRenamableStaticProcedures() {
        LifeProtocolAnalyzer analyzer = new LifeProtocolAnalyzer();

        var graph = analyzer.protocolGraph(DelegatingEntity.class);

        assertTrue(graph.hasMaintenanceWriter());
        assertTrue(graph.dependencySources().size() >= 2);
    }

    @Test
    void keepsAlternativeAuthoritiesInIndependentBranches() {
        LifeProtocolAnalyzer analyzer = new LifeProtocolAnalyzer();

        var graph = analyzer.protocolGraph(AlternativeEntity.class);

        assertTrue(graph.authorityBranches().size() >= 2);
        assertTrue(graph.authorityBranches().stream()
                .allMatch(branch -> branch.transactionSources().contains(branch.authority())));
    }

    @Test
    void separatesPersistenceCallbacksFromRecurringMaintenance() {
        LifeProtocolAnalyzer analyzer = new LifeProtocolAnalyzer();

        var graph = analyzer.protocolGraph(SerializedEntity.class);

        assertFalse(graph.hasMaintenanceWriter());
        assertFalse(graph.persistenceWrites().isEmpty());
        assertTrue(graph.persistenceSources().stream()
                .anyMatch(ProtocolDataflowAnalyzer.NbtValueSource.class::isInstance));
    }

    private abstract static class SyntheticEntity extends LivingEntity {
        private float storedHealth;

        protected SyntheticEntity(EntityType<? extends LivingEntity> entityType, Level level) {
            super(entityType, level);
        }

        @Override
        public float getHealth() {
            return storedHealth;
        }

        @Override
        public void setHealth(float health) {
            storedHealth = health;
        }
    }

    private abstract static class MaintainedEntity extends LivingEntity {
        private float storedHealth;
        private float checkpoint;

        protected MaintainedEntity(EntityType<? extends LivingEntity> entityType, Level level) {
            super(entityType, level);
        }

        @Override
        public float getHealth() {
            return storedHealth;
        }

        @Override
        public void tick() {
            storedHealth = Math.max(storedHealth, checkpoint);
            checkpoint = storedHealth;
        }
    }

    private abstract static class PersistentEntity extends LivingEntity {
        private float storedHealth;

        protected PersistentEntity(EntityType<? extends LivingEntity> entityType, Level level) {
            super(entityType, level);
        }

        @Override
        public float getHealth() {
            return storedHealth;
        }

        @Override
        public void tick() {
            storedHealth = (float) getPersistentData().getDouble("checkpoint");
        }
    }

    private abstract static class DelegatingEntity extends LivingEntity {
        private float storedHealth;
        private float checkpoint;

        protected DelegatingEntity(EntityType<? extends LivingEntity> entityType, Level level) {
            super(entityType, level);
        }

        @Override
        public float getHealth() {
            return storedHealth;
        }

        @Override
        public void tick() {
            ArbitrarilyNamedProcedure.apply(this);
        }
    }

    private abstract static class AlternativeEntity extends LivingEntity {
        private boolean alternate;
        private float primaryHealth;
        private float secondaryHealth;

        protected AlternativeEntity(EntityType<? extends LivingEntity> entityType, Level level) {
            super(entityType, level);
        }

        @Override
        public float getHealth() {
            return alternate ? secondaryHealth : primaryHealth;
        }
    }

    private abstract static class SerializedEntity extends LivingEntity {
        private float storedHealth;

        protected SerializedEntity(EntityType<? extends LivingEntity> entityType, Level level) {
            super(entityType, level);
        }

        @Override
        public float getHealth() {
            return storedHealth;
        }

        @Override
        public void addAdditionalSaveData(CompoundTag tag) {
            super.addAdditionalSaveData(tag);
            tag.putFloat("stored", storedHealth);
        }

        @Override
        public void readAdditionalSaveData(CompoundTag tag) {
            super.readAdditionalSaveData(tag);
            storedHealth = tag.getFloat("stored");
        }
    }

    private static final class ArbitrarilyNamedProcedure {
        private ArbitrarilyNamedProcedure() {
        }

        private static void apply(DelegatingEntity entity) {
            entity.storedHealth = Math.max(entity.storedHealth, entity.checkpoint);
            entity.checkpoint = entity.storedHealth;
        }
    }
}
