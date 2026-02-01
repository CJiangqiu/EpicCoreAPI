package net.eca.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Label;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * Bytecode verification tool for ECA transformations.
 * Verifies that ECA hooks are properly injected into target methods.
 */
public final class BytecodeVerifier {

    // 目标方法的 SRG 名称
    private static final String GET_HEALTH_METHOD = "m_21223_";
    private static final String IS_ALIVE_METHOD = "m_6084_";
    private static final String IS_DEAD_OR_DYING_METHOD = "m_21224_";

    // ECA Hook 类名
    private static final String GET_HEALTH_HOOK = "net/eca/util/health/LivingEntityHook";
    private static final String STATUS_HOOK = "net/eca/util/health/LivingEntityHook";

    /**
     * Verify and log the bytecode of a transformed class.
     * @param inst the instrumentation instance
     * @param targetClass the class to verify
     */
    public static void verifyAndLog(Instrumentation inst, Class<?> targetClass) {
        if (inst == null || targetClass == null) {
            return;
        }

        try {
            AgentLogWriter.info("========================================");
            AgentLogWriter.info("ECA Bytecode Verification");
            AgentLogWriter.info("========================================");
            AgentLogWriter.info("Target Class: " + targetClass.getName());
            AgentLogWriter.info("");

            // 捕获类的字节码
            byte[] bytecode = captureClassBytecode(inst, targetClass);
            if (bytecode == null) {
                AgentLogWriter.warn("Failed to capture bytecode for: " + targetClass.getName());
                return;
            }

            // 解析并验证字节码
            ClassReader cr = new ClassReader(bytecode);
            VerificationVisitor visitor = new VerificationVisitor();
            cr.accept(visitor, ClassReader.EXPAND_FRAMES);

            // 输出验证结果
            AgentLogWriter.info("");
            AgentLogWriter.info("========================================");
            AgentLogWriter.info("Verification Summary:");
            AgentLogWriter.info("  getHealth():      " + (visitor.getHealthHooked ? "✅ HOOKED" : "❌ NOT HOOKED"));
            AgentLogWriter.info("  isAlive():        " + (visitor.isAliveHooked ? "✅ HOOKED" : "❌ NOT HOOKED"));
            AgentLogWriter.info("  isDeadOrDying():  " + (visitor.isDeadOrDyingHooked ? "✅ HOOKED" : "❌ NOT HOOKED"));
            AgentLogWriter.info("========================================");
            AgentLogWriter.info("");

        } catch (Throwable t) {
            AgentLogWriter.error("Bytecode verification failed", t);
        }
    }

    /**
     * Capture the current bytecode of a class using a temporary transformer.
     */
    private static byte[] captureClassBytecode(Instrumentation inst, Class<?> targetClass) {
        BytecodeCaptureTransformer capturer = new BytecodeCaptureTransformer(targetClass);
        try {
            inst.addTransformer(capturer, true);
            inst.retransformClasses(targetClass);
            return capturer.getCapturedBytecode();
        } catch (Throwable t) {
            AgentLogWriter.error("Failed to capture bytecode: " + t.getMessage());
            return null;
        } finally {
            inst.removeTransformer(capturer);
        }
    }

    /**
     * Temporary transformer that captures bytecode without modifying it.
     */
    private static class BytecodeCaptureTransformer implements ClassFileTransformer {
        private final Class<?> targetClass;
        private byte[] capturedBytecode;

        BytecodeCaptureTransformer(Class<?> targetClass) {
            this.targetClass = targetClass;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (classBeingRedefined == targetClass) {
                this.capturedBytecode = classfileBuffer;
            }
            return null; // 不修改字节码
        }

        byte[] getCapturedBytecode() {
            return capturedBytecode;
        }
    }

    /**
     * ASM ClassVisitor for verifying method transformations.
     */
    private static class VerificationVisitor extends ClassVisitor {
        boolean getHealthHooked = false;
        boolean isAliveHooked = false;
        boolean isDeadOrDyingHooked = false;

        VerificationVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (GET_HEALTH_METHOD.equals(name) && "()F".equals(descriptor)) {
                AgentLogWriter.info("--- Method: m_21223_ (getHealth) ---");
                return new MethodVerifier(GET_HEALTH_HOOK, "processGetHealth", result -> getHealthHooked = result);
            }

            if (IS_ALIVE_METHOD.equals(name) && "()Z".equals(descriptor)) {
                AgentLogWriter.info("--- Method: m_6084_ (isAlive) ---");
                return new MethodVerifier(STATUS_HOOK, "processIsAlive", result -> isAliveHooked = result);
            }

            if (IS_DEAD_OR_DYING_METHOD.equals(name) && "()Z".equals(descriptor)) {
                AgentLogWriter.info("--- Method: m_21224_ (isDeadOrDying) ---");
                return new MethodVerifier(STATUS_HOOK, "processIsDeadOrDying", result -> isDeadOrDyingHooked = result);
            }

            return null;
        }
    }

    /**
     * ASM MethodVisitor for verifying individual methods.
     */
    private static class MethodVerifier extends MethodVisitor {
        private final String expectedHookClass;
        private final String expectedHookMethod;
        private final ResultCallback callback;
        private final List<String> instructions = new ArrayList<>();
        private int instructionIndex = 0;
        private boolean hookFound = false;

        MethodVerifier(String expectedHookClass, String expectedHookMethod, ResultCallback callback) {
            super(Opcodes.ASM9);
            this.expectedHookClass = expectedHookClass;
            this.expectedHookMethod = expectedHookMethod;
            this.callback = callback;
        }

        @Override
        public void visitLabel(Label label) {
            // Skip labels for cleaner output
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            // Skip line numbers
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            // Skip frames
        }

        @Override
        public void visitInsn(int opcode) {
            String insnText = getOpcodeText(opcode);
            instructions.add(String.format("  #%d: %s", instructionIndex++, insnText));
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            instructions.add(String.format("  #%d: %s %d", instructionIndex++, getOpcodeText(opcode), operand));
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            String varName = (var == 0) ? "this" : "var" + var;
            instructions.add(String.format("  #%d: %s %d (%s)", instructionIndex++, getOpcodeText(opcode), var, varName));
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            instructions.add(String.format("  #%d: %s %s", instructionIndex++, getOpcodeText(opcode), type));
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            instructions.add(String.format("  #%d: %s %s.%s", instructionIndex++, getOpcodeText(opcode),
                simplifyClassName(owner), name));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            String hookMarker = "";
            if (opcode == Opcodes.INVOKESTATIC && owner.equals(expectedHookClass) && name.equals(expectedHookMethod)) {
                hookMarker = " ✅ ECA HOOK";
                hookFound = true;
            }

            instructions.add(String.format("  #%d: %s %s.%s%s", instructionIndex++, getOpcodeText(opcode),
                simplifyClassName(owner), name, hookMarker));
        }

        @Override
        public void visitLdcInsn(Object value) {
            instructions.add(String.format("  #%d: LDC %s", instructionIndex++, value));
        }

        @Override
        public void visitEnd() {
            // 输出所有指令
            for (String insn : instructions) {
                AgentLogWriter.info(insn);
            }

            // 输出结果
            if (hookFound) {
                AgentLogWriter.info("Result: ✅ ECA hook detected");
            } else {
                AgentLogWriter.warn("Result: ❌ ECA hook NOT found (expected " + expectedHookClass + "." + expectedHookMethod + ")");
            }
            AgentLogWriter.info("");

            // 回调结果
            callback.onResult(hookFound);
        }

        private String getOpcodeText(int opcode) {
            switch (opcode) {
                case Opcodes.ALOAD: return "ALOAD";
                case Opcodes.ILOAD: return "ILOAD";
                case Opcodes.FLOAD: return "FLOAD";
                case Opcodes.DLOAD: return "DLOAD";
                case Opcodes.LLOAD: return "LLOAD";
                case Opcodes.ASTORE: return "ASTORE";
                case Opcodes.ISTORE: return "ISTORE";
                case Opcodes.FSTORE: return "FSTORE";
                case Opcodes.DSTORE: return "DSTORE";
                case Opcodes.LSTORE: return "LSTORE";
                case Opcodes.GETFIELD: return "GETFIELD";
                case Opcodes.PUTFIELD: return "PUTFIELD";
                case Opcodes.GETSTATIC: return "GETSTATIC";
                case Opcodes.PUTSTATIC: return "PUTSTATIC";
                case Opcodes.INVOKEVIRTUAL: return "INVOKEVIRTUAL";
                case Opcodes.INVOKESTATIC: return "INVOKESTATIC";
                case Opcodes.INVOKESPECIAL: return "INVOKESPECIAL";
                case Opcodes.INVOKEINTERFACE: return "INVOKEINTERFACE";
                case Opcodes.INVOKEDYNAMIC: return "INVOKEDYNAMIC";
                case Opcodes.IRETURN: return "IRETURN";
                case Opcodes.FRETURN: return "FRETURN";
                case Opcodes.ARETURN: return "ARETURN";
                case Opcodes.DRETURN: return "DRETURN";
                case Opcodes.LRETURN: return "LRETURN";
                case Opcodes.RETURN: return "RETURN";
                case Opcodes.SWAP: return "SWAP";
                case Opcodes.DUP: return "DUP";
                case Opcodes.POP: return "POP";
                case Opcodes.CHECKCAST: return "CHECKCAST";
                case Opcodes.INSTANCEOF: return "INSTANCEOF";
                case Opcodes.NEW: return "NEW";
                default: return "OPCODE_" + opcode;
            }
        }

        private String simplifyClassName(String internalName) {
            if (internalName == null) return "null";
            String[] parts = internalName.split("/");
            return parts[parts.length - 1];
        }
    }

    /**
     * Callback interface for verification results.
     */
    @FunctionalInterface
    private interface ResultCallback {
        void onResult(boolean success);
    }

    private BytecodeVerifier() {}
}
