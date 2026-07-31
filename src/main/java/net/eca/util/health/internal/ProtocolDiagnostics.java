package net.eca.util.health.internal;

import net.eca.util.EcaLogger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ProtocolDiagnostics {
    private static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    private ProtocolDiagnostics() {
    }

    static void reflectionFailure(String operation, Throwable failure) {
        String failureType = failure == null ? "unknown" : failure.getClass().getName();
        String key = operation + '|' + failureType;
        if (!REPORTED_FAILURES.add(key)) return;
        EcaLogger.info("[LifeProtocol] reflection operation failed operation={} type={} msg={}",
                operation, failureType, failure == null ? "" : failure.getMessage());
    }
}
