package net.eca.pro.ingot;

import net.eca.agent.AgentLogWriter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ProIngotReceiptTransformer implements ClassFileTransformer {
    private final Set<String> confirmed = ConcurrentHashMap.newKeySet();
    private final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        verify(className, classfileBuffer);
        return null;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        verify(className, classfileBuffer);
        return null;
    }

    int confirmedCount() {
        return confirmed.size();
    }

    private void verify(String className, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) return;
        List<ProIngot> ingots = ProIngotRegistry.applicable(className);
        if (ingots.isEmpty()) return;
        for (ProIngot ingot : ingots) {
            try {
                if (ingot.verify(className, classfileBuffer)) continue;
                String key = className + '#' + ingot.name();
                confirmed.remove(className);
                if (reportedFailures.add(key)) {
                    AgentLogWriter.info("[ProIngot] Receipt rejected: " + key);
                }
                return;
            } catch (Throwable failure) {
                String key = className + '#' + ingot.name();
                confirmed.remove(className);
                if (reportedFailures.add(key)) {
                    AgentLogWriter.info("[ProIngot] Receipt verification failed: " + key
                            + ": " + failure.getMessage());
                }
                return;
            }
        }
        confirmed.add(className);
    }
}
