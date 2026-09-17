package net.eca.pro.ingot;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.EcaAgent;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ProIngotManager {
    private static volatile boolean installed;
    private static ProIngotTransformer transformer;
    private static ProIngotReceiptTransformer receiptTransformer;

    private ProIngotManager() {
    }

    public static synchronized void install() {
        if (installed) return;
        Instrumentation instrumentation = EcaAgent.getInstrumentation();
        if (instrumentation == null || !instrumentation.isRetransformClassesSupported()) {
            AgentLogWriter.info("[ProIngot] Instrumentation backend unavailable");
            return;
        }
        try {
            preload();
            transformer = new ProIngotTransformer();
            receiptTransformer = new ProIngotReceiptTransformer();
            instrumentation.addTransformer(transformer, true);
            instrumentation.addTransformer(receiptTransformer, true);
            installed = true;
            int retransformed = retransformLoadedTargets(instrumentation);
            AgentLogWriter.info("[ProIngot] Installed " + ProIngotRegistry.size()
                    + " terminal ingots; retransformed=" + retransformed
                    + ", confirmed=" + receiptTransformer.confirmedCount());
        } catch (Throwable failure) {
            AgentLogWriter.error("[ProIngot] Installation failed", failure);
        }
    }

    private static int retransformLoadedTargets(Instrumentation instrumentation) {
        Set<String> targets = ProIngotRegistry.targets();
        List<Class<?>> loadedTargets = new ArrayList<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (!targets.contains(type.getName().replace('.', '/'))) continue;
            if (instrumentation.isModifiableClass(type)) loadedTargets.add(type);
        }
        int transformed = 0;
        for (Class<?> type : loadedTargets) {
            try {
                instrumentation.retransformClasses(type);
                transformed++;
            } catch (Throwable failure) {
                AgentLogWriter.info("[ProIngot] Retransform failed for " + type.getName()
                        + ": " + failure.getMessage());
            }
        }
        return transformed;
    }

    private static void preload() throws ClassNotFoundException {
        Class<?>[] roots = {
                ProIngot.class,
                ProIngotRegistry.class,
                ProIngotTransformer.class,
                ProIngotReceiptTransformer.class,
                EntityStateHookIngot.class,
                ContainerFinalityIngot.class,
                TargetContractIngot.class,
                ProContainerHooks.class,
                SafeClassWriter.class
        };
        for (Class<?> root : roots) initializeClassTree(root);
    }

    private static void initializeClassTree(Class<?> type) throws ClassNotFoundException {
        Class.forName(type.getName(), true, type.getClassLoader());
        for (Class<?> nested : type.getDeclaredClasses()) initializeClassTree(nested);
    }
}
