package net.eca.pro;

import forgevm.core.ForgeVM;
import forgevm.forge.FvmIngot;
import net.eca.agent.AgentLogWriter;
import net.eca.agent.EcaAgent;
import net.eca.coremod.EcaTransformerManager;
import net.eca.pro.ingot.EntityStateFallbackIngots;
import net.eca.pro.ingot.TransformerMonitorIngot;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class EcaProFallbackController {
    private static final int CHECK_INTERVAL = 200;
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean FALLBACK = new AtomicBoolean();
    private static final AtomicInteger PULSES = new AtomicInteger();
    private static volatile int failureThreshold = 3;
    private static volatile int consecutiveFailures;

    private EcaProFallbackController() {
    }

    static void activate(int threshold) {
        if (!ACTIVE.compareAndSet(false, true)) return;
        failureThreshold = Math.max(1, threshold);
        boolean loaded = ForgeVM.forge().load(new TransformerMonitorIngot());
        AgentLogWriter.info("[EcaPro] Transformer monitor " + (loaded ? "active" : "failed"));
        if (!loaded) {
            activateFallback();
        } else if (!primaryHealthy()) {
            recordFailure();
        }
    }

    public static void pulse() {
        if (!ACTIVE.get() || FALLBACK.get()) return;
        if (PULSES.incrementAndGet() % CHECK_INTERVAL != 0) return;
        if (primaryHealthy()) {
            consecutiveFailures = 0;
        } else {
            recordFailure();
        }
    }

    private static boolean primaryHealthy() {
        if (EcaAgent.getInstrumentation() == null) return false;
        try {
            Class<?> living = Class.forName("net.minecraft.world.entity.LivingEntity", false,
                    EcaProFallbackController.class.getClassLoader());
            return EcaTransformerManager.retransformHealthClass(living, false).confirmed();
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaPro] Primary transformer check failed: " + t.getMessage());
            return false;
        }
    }

    private static synchronized void recordFailure() {
        if (FALLBACK.get()) return;
        consecutiveFailures++;
        AgentLogWriter.info("[EcaPro] Primary transformer check failed " + consecutiveFailures
                + "/" + failureThreshold);
        if (consecutiveFailures < failureThreshold) return;
        activateFallback();
    }

    private static synchronized void activateFallback() {
        if (FALLBACK.get()) return;
        List<FvmIngot> ingots = EntityStateFallbackIngots.create();
        ForgeVM.forge().load(ingots);
        int loaded = 0;
        for (FvmIngot ingot : ingots) {
            if (ForgeVM.forge().isLoaded(ingot.getClass())) loaded++;
        }
        if (loaded == ingots.size()) {
            FALLBACK.set(true);
            AgentLogWriter.info("[EcaPro] Runtime fallback active with " + loaded + " ingots");
        } else {
            AgentLogWriter.info("[EcaPro] Runtime fallback incomplete: " + loaded + "/" + ingots.size());
        }
    }
}
