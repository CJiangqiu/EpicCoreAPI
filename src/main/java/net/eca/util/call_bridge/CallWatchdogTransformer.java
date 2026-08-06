package net.eca.util.call_bridge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CallWatchdogTransformer {

    private static final String RUNTIME = "net/eca/util/call_bridge/CallBridgeRuntime";
    private static final String STACK_WALKER = "java/lang/StackWalker";
    private static final String STACK_FRAME = "java/lang/StackWalker$StackFrame";
    private static final String STACK_TRACE_ELEMENT = "java/lang/StackTraceElement";
    private static final String THREAD = "java/lang/Thread";
    private static final String THROWABLE = "java/lang/Throwable";
    private static final String STACK_TRACE_DESC = "()[Ljava/lang/StackTraceElement;";
    private static final Set<String> TARGETS = ConcurrentHashMap.newKeySet();

    private CallWatchdogTransformer() {}

    public static void register(String internalName) {
        if (internalName != null) TARGETS.add(internalName.replace('.', '/'));
    }

    public static boolean hasTarget(String internalName) {
        return internalName != null && TARGETS.contains(internalName.replace('.', '/'));
    }

    public static boolean isWatchdog(byte[] bytes) {
        if (bytes == null) return false;
        try {
            ClassNode owner = new ClassNode();
            new ClassReader(bytes).accept(owner, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : owner.methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode call && isObservationCall(call)) return true;
                }
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
        return false;
    }

    public static byte[] transform(String internalName, byte[] bytes) {
        if (!hasTarget(internalName) || bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode owner = new ClassNode();
            reader.accept(owner, ClassReader.EXPAND_FRAMES);
            if (hasRuntimeBridge(owner)) return null;
            boolean changed = false;
            for (MethodNode method : owner.methods) changed |= inject(method);
            if (!changed) return null;
            ClassWriter writer = new SafeClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            owner.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    public static boolean verifyTransform(String internalName, byte[] bytes) {
        if (!hasTarget(internalName) || bytes == null) return false;
        try {
            ClassNode owner = new ClassNode();
            new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
            return hasRuntimeBridge(owner);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
        }
        return false;
    }

    private static boolean inject(MethodNode method) {
        boolean changed = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof MethodInsnNode call && !RUNTIME.equals(call.owner)) {
                MethodInsnNode bridge = bridgeCall(call);
                if (bridge != null) {
                    if (STACK_WALKER.equals(call.owner)) method.instructions.insertBefore(call, bridge);
                    else method.instructions.insert(call, bridge);
                    changed = true;
                }
            }
            insn = next;
        }
        return changed;
    }

    private static MethodInsnNode bridgeCall(MethodInsnNode call) {
        if (STACK_WALKER.equals(call.owner) && "walk".equals(call.name)
                && "(Ljava/util/function/Function;)Ljava/lang/Object;".equals(call.desc)) {
            return new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "wrapStackFunction",
                    "(Ljava/util/function/Function;)Ljava/util/function/Function;", false);
        }
        if (STACK_WALKER.equals(call.owner) && "forEach".equals(call.name)
                && "(Ljava/util/function/Consumer;)V".equals(call.desc)) {
            return new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "wrapStackConsumer",
                    "(Ljava/util/function/Consumer;)Ljava/util/function/Consumer;", false);
        }
        if (isStackTraceCall(call)) {
            return new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "adaptStackTrace",
                    "([Ljava/lang/StackTraceElement;)[Ljava/lang/StackTraceElement;", false);
        }
        if (isMethodNameCall(call)) {
            return new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "adaptMethodName",
                    "(Ljava/lang/String;)Ljava/lang/String;", false);
        }
        return null;
    }

    private static boolean isObservationCall(MethodInsnNode call) {
        return (STACK_WALKER.equals(call.owner)
                && ("walk".equals(call.name) || "forEach".equals(call.name)))
                || isStackTraceCall(call) || isMethodNameCall(call);
    }

    private static boolean hasRuntimeBridge(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) return true;
            }
        }
        return false;
    }

    private static boolean isStackTraceCall(MethodInsnNode call) {
        return STACK_TRACE_DESC.equals(call.desc) && "getStackTrace".equals(call.name)
                && (THREAD.equals(call.owner) || THROWABLE.equals(call.owner));
    }

    private static boolean isMethodNameCall(MethodInsnNode call) {
        return "getMethodName".equals(call.name) && "()Ljava/lang/String;".equals(call.desc)
                && (STACK_FRAME.equals(call.owner) || STACK_TRACE_ELEMENT.equals(call.owner));
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override
        protected String getCommonSuperClass(String first, String second) {
            return "java/lang/Object";
        }
    }
}
