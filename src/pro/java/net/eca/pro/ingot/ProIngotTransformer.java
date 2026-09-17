package net.eca.pro.ingot;

import net.eca.agent.AgentLogWriter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;

final class ProIngotTransformer implements ClassFileTransformer {
    private static final ThreadLocal<Boolean> TRANSFORMING =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        return transformClass(className, classfileBuffer);
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        return transformClass(className, classfileBuffer);
    }

    private static byte[] transformClass(String className, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null || TRANSFORMING.get()) return null;
        List<ProIngot> ingots = ProIngotRegistry.applicable(className);
        if (ingots.isEmpty()) return null;
        TRANSFORMING.set(Boolean.TRUE);
        try {
            byte[] current = classfileBuffer;
            boolean changed = false;
            for (ProIngot ingot : ingots) {
                byte[] transformed = ingot.transform(className, current);
                if (transformed != null && transformed != current) {
                    current = transformed;
                    changed = true;
                }
            }
            return changed ? current : null;
        } catch (Throwable failure) {
            AgentLogWriter.error("[ProIngot] Terminal transformation failed: " + className, failure);
            return null;
        } finally {
            TRANSFORMING.remove();
        }
    }
}
