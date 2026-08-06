package net.eca.util.call_bridge;

import net.eca.util.EcaLogger;
import net.eca.util.reflect.ObfuscationMapping;

import java.lang.StackWalker.StackFrame;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public final class CallBridgeRuntime {

    private static final String ECA_PACKAGE = "net.eca.";
    private static final Set<String> RUNTIME_RECEIPTS = ConcurrentHashMap.newKeySet();

    private CallBridgeRuntime() {}

    public static <T> Function<Stream<StackFrame>, T> wrapStackFunction(
            Function<Stream<StackFrame>, T> function) {
        if (function == null || !CallBridgeManager.isAuthorized()) return function;
        noteAuthorization("walk");
        return frames -> function.apply(adaptFrames(frames));
    }

    public static Consumer<StackFrame> wrapStackConsumer(Consumer<StackFrame> consumer) {
        if (consumer == null || !CallBridgeManager.isAuthorized()) return consumer;
        noteAuthorization("forEach");
        return frame -> {
            if (!isInternalFrame(frame.getClassName())) consumer.accept(new BridgeStackFrame(frame));
        };
    }

    public static StackTraceElement[] adaptStackTrace(StackTraceElement[] trace) {
        if (trace == null || !CallBridgeManager.isAuthorized()) return trace;
        noteAuthorization("stackTrace");
        return Stream.of(trace)
                .filter(frame -> !isInternalFrame(frame.getClassName()))
                .map(CallBridgeRuntime::adaptStackTraceElement)
                .toArray(StackTraceElement[]::new);
    }

    public static String adaptMethodName(String runtimeName) {
        if (runtimeName == null || !CallBridgeManager.isAuthorized()) return runtimeName;
        noteAuthorization("methodName");
        return bridgeMethodName(runtimeName);
    }

    private static Stream<StackFrame> adaptFrames(Stream<StackFrame> frames) {
        return frames.filter(frame -> !isInternalFrame(frame.getClassName()))
                .map(BridgeStackFrame::new);
    }

    private static StackTraceElement adaptStackTraceElement(StackTraceElement frame) {
        String methodName = bridgeMethodName(frame.getMethodName());
        if (methodName.equals(frame.getMethodName())) return frame;
        return new StackTraceElement(frame.getClassLoaderName(), frame.getModuleName(),
                frame.getModuleVersion(), frame.getClassName(), methodName,
                frame.getFileName(), frame.getLineNumber());
    }

    private static String bridgeMethodName(String runtimeName) {
        String mapped = ObfuscationMapping.getDeobfuscatedMethodName(runtimeName);
        if (mapped != null && !mapped.equals(runtimeName)) {
            String receipt = "method|" + runtimeName + "|" + mapped;
            if (RUNTIME_RECEIPTS.add(receipt)) {
                EcaLogger.info("[CallBridge] method name mapped runtime={} source={}",
                        runtimeName, mapped);
            }
        }
        return mapped == null ? runtimeName : mapped;
    }

    private static void noteAuthorization(String observation) {
        CallBridgeManager.noteRuntimeObservation();
        String receipt = "authorization|" + observation;
        if (RUNTIME_RECEIPTS.add(receipt)) {
            EcaLogger.info("[CallBridge] authorization observed api={}", observation);
        }
    }

    private static boolean isInternalFrame(String className) {
        return className != null && className.startsWith(ECA_PACKAGE);
    }

    private static final class BridgeStackFrame implements StackFrame {
        private final StackFrame delegate;

        private BridgeStackFrame(StackFrame delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getClassName() {
            return delegate.getClassName();
        }

        @Override
        public String getMethodName() {
            return bridgeMethodName(delegate.getMethodName());
        }

        @Override
        public Class<?> getDeclaringClass() {
            return delegate.getDeclaringClass();
        }

        @Override
        public MethodType getMethodType() {
            return delegate.getMethodType();
        }

        @Override
        public String getDescriptor() {
            return delegate.getDescriptor();
        }

        @Override
        public int getByteCodeIndex() {
            return delegate.getByteCodeIndex();
        }

        @Override
        public String getFileName() {
            return delegate.getFileName();
        }

        @Override
        public int getLineNumber() {
            return delegate.getLineNumber();
        }

        @Override
        public boolean isNativeMethod() {
            return delegate.isNativeMethod();
        }

        @Override
        public StackTraceElement toStackTraceElement() {
            return adaptStackTraceElement(delegate.toStackTraceElement());
        }

        @Override
        public String toString() {
            return toStackTraceElement().toString();
        }
    }
}
