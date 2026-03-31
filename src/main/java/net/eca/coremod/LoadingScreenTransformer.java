package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;

/**
 * Replaces the solid-color glClear in DisplayWindow.paintFramebuffer()
 * with a vertical gradient: purple (128,0,128) at edges → red (239,50,61) at center.
 * Implements ClassFileTransformer so it can be registered directly in CoreMod stage.
 */
public final class LoadingScreenTransformer implements ClassFileTransformer {

    public static final String TARGET_CLASS = "net/minecraftforge/fml/earlydisplay/DisplayWindow";
    public static final boolean ENABLED = readConfig();

    private static final String PAINT_METHOD  = "paintFramebuffer";
    private static final String PAINT_DESC    = "()V";
    private static final String CONTEXT_FIELD = "context";
    private static final String CONTEXT_TYPE  = "net/minecraftforge/fml/earlydisplay/RenderElement$DisplayContext";
    private static final String CONTEXT_DESC  = "L" + CONTEXT_TYPE + ";";
    private static final String GL            = "org/lwjgl/opengl/GL11C";

    private static final int GL_SCISSOR_TEST = 0x0C11;
    private static final int CLEAR_BITS      = 16640;
    private static final int BANDS           = 32;

    private static final float CR = 239f/255f, CG = 50f/255f, CB = 61f/255f;
    private static final float DR = 128f/255f - CR, DG = -CG, DB = 128f/255f - CB;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (TARGET_CLASS.equals(className)) {
            return transform(classfileBuffer);
        }
        return null;
    }

    public static byte[] transform(byte[] classfileBuffer) {
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new SafeWriter(cr, ClassWriter.COMPUTE_FRAMES);
            DisplayWindowVisitor cv = new DisplayWindowVisitor(cw);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);

            if (cv.transformed) {
                AgentLogWriter.info("[LoadingScreenTransformer] Transformed DisplayWindow.paintFramebuffer()");
                return cw.toByteArray();
            }
            return null;
        } catch (Throwable t) {
            AgentLogWriter.error("[LoadingScreenTransformer] Failed to transform DisplayWindow", t);
            return null;
        }
    }

    private static class DisplayWindowVisitor extends ClassVisitor {
        boolean transformed;

        DisplayWindowVisitor(ClassWriter cw) {
            super(Opcodes.ASM9, cw);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            if (PAINT_METHOD.equals(name) && PAINT_DESC.equals(desc)) {
                transformed = true;
                return new PaintMethodVisitor(mv);
            }
            return mv;
        }
    }

    private static class PaintMethodVisitor extends MethodVisitor {
        private boolean replaced;
        private boolean holdingSipush;

        PaintMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        private void flush() {
            if (holdingSipush) {
                holdingSipush = false;
                super.visitIntInsn(Opcodes.SIPUSH, CLEAR_BITS);
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            flush();
            if (!replaced && opcode == Opcodes.SIPUSH && operand == CLEAR_BITS) {
                holdingSipush = true;
                return;
            }
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (holdingSipush && opcode == Opcodes.INVOKESTATIC
                    && "glClear".equals(name) && "(I)V".equals(desc)) {
                holdingSipush = false;
                replaced = true;
                emitGradient();
                return;
            }
            flush();
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override public void visitInsn(int op) { flush(); super.visitInsn(op); }
        @Override public void visitVarInsn(int op, int v) { flush(); super.visitVarInsn(op, v); }
        @Override public void visitTypeInsn(int op, String t) { flush(); super.visitTypeInsn(op, t); }
        @Override public void visitFieldInsn(int op, String o, String n, String d) { flush(); super.visitFieldInsn(op, o, n, d); }
        @Override public void visitJumpInsn(int op, Label l) { flush(); super.visitJumpInsn(op, l); }
        @Override public void visitLdcInsn(Object v) { flush(); super.visitLdcInsn(v); }
        @Override public void visitIincInsn(int v, int i) { flush(); super.visitIincInsn(v, i); }
        @Override public void visitInvokeDynamicInsn(String n, String d, Handle b, Object... a) { flush(); super.visitInvokeDynamicInsn(n, d, b, a); }
        @Override public void visitTableSwitchInsn(int min, int max, Label d, Label... l) { flush(); super.visitTableSwitchInsn(min, max, d, l); }
        @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) { flush(); super.visitLookupSwitchInsn(d, k, l); }
        @Override public void visitMultiANewArrayInsn(String d, int n) { flush(); super.visitMultiANewArrayInsn(d, n); }

        private void emitGradient() {
            MethodVisitor mv = this.mv;

            Label lblContextOk = new Label();
            Label lblGradient = new Label();
            Label lblDone = new Label();
            Label lblLoopStart = new Label();
            Label lblLoopEnd = new Label();

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, CONTEXT_FIELD, CONTEXT_DESC);
            mv.visitJumpInsn(Opcodes.IFNONNULL, lblContextOk);
            mv.visitIntInsn(Opcodes.SIPUSH, CLEAR_BITS);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glClear", "(I)V", false);
            mv.visitJumpInsn(Opcodes.GOTO, lblDone);

            mv.visitLabel(lblContextOk);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, CONTEXT_FIELD, CONTEXT_DESC);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTEXT_TYPE, "scaledHeight", "()I", false);
            mv.visitVarInsn(Opcodes.ISTORE, 1);

            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitJumpInsn(Opcodes.IFGT, lblGradient);
            mv.visitIntInsn(Opcodes.SIPUSH, CLEAR_BITS);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glClear", "(I)V", false);
            mv.visitJumpInsn(Opcodes.GOTO, lblDone);

            mv.visitLabel(lblGradient);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, CONTEXT_FIELD, CONTEXT_DESC);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTEXT_TYPE, "scaledWidth", "()I", false);
            mv.visitVarInsn(Opcodes.ISTORE, 2);

            mv.visitIntInsn(Opcodes.SIPUSH, GL_SCISSOR_TEST);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glEnable", "(I)V", false);

            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 3);
            mv.visitLabel(lblLoopStart);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitIntInsn(Opcodes.BIPUSH, BANDS);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, lblLoopEnd);

            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitIntInsn(Opcodes.BIPUSH, BANDS);
            mv.visitInsn(Opcodes.IDIV);
            mv.visitVarInsn(Opcodes.ISTORE, 4);

            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitIntInsn(Opcodes.BIPUSH, BANDS);
            mv.visitInsn(Opcodes.IDIV);
            mv.visitVarInsn(Opcodes.ISTORE, 5);

            mv.visitLdcInsn(2.0f);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.I2F);
            mv.visitLdcInsn(2.0f);
            mv.visitInsn(Opcodes.FDIV);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitInsn(Opcodes.I2F);
            mv.visitInsn(Opcodes.FDIV);
            mv.visitInsn(Opcodes.FCONST_1);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(F)F", false);
            mv.visitVarInsn(Opcodes.FSTORE, 6);

            mv.visitLdcInsn(CR); mv.visitLdcInsn(DR); mv.visitVarInsn(Opcodes.FLOAD, 6);
            mv.visitInsn(Opcodes.FMUL); mv.visitInsn(Opcodes.FADD);
            mv.visitLdcInsn(CG); mv.visitLdcInsn(DG); mv.visitVarInsn(Opcodes.FLOAD, 6);
            mv.visitInsn(Opcodes.FMUL); mv.visitInsn(Opcodes.FADD);
            mv.visitLdcInsn(CB); mv.visitLdcInsn(DB); mv.visitVarInsn(Opcodes.FLOAD, 6);
            mv.visitInsn(Opcodes.FMUL); mv.visitInsn(Opcodes.FADD);
            mv.visitInsn(Opcodes.FCONST_1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glClearColor", "(FFFF)V", false);

            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glScissor", "(IIII)V", false);

            mv.visitIntInsn(Opcodes.SIPUSH, CLEAR_BITS);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glClear", "(I)V", false);

            mv.visitIincInsn(3, 1);
            mv.visitJumpInsn(Opcodes.GOTO, lblLoopStart);

            mv.visitLabel(lblLoopEnd);
            mv.visitIntInsn(Opcodes.SIPUSH, GL_SCISSOR_TEST);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glDisable", "(I)V", false);

            mv.visitLabel(lblDone);
        }
    }

    private static boolean readConfig() {
        try {
            Path configPath = Paths.get("config", "eca.toml");
            if (!Files.exists(configPath)) return true;

            for (String rawLine : Files.readAllLines(configPath)) {
                String line = rawLine;
                int comment = line.indexOf('#');
                if (comment >= 0) line = line.substring(0, comment);
                line = line.trim();
                if (!line.contains("=")) continue;

                int eq = line.indexOf('=');
                String key = line.substring(0, eq).trim();
                if (key.startsWith("\"") && key.endsWith("\"") && key.length() >= 2) {
                    key = key.substring(1, key.length() - 1);
                }
                if ("Enable Custom Loading Background".equals(key)) {
                    return "true".equalsIgnoreCase(line.substring(eq + 1).trim());
                }
            }
        } catch (Throwable ignored) {}
        return true;
    }

    static class SafeWriter extends ClassWriter {
        SafeWriter(ClassReader cr, int flags) { super(cr, flags); }
        @Override
        protected String getCommonSuperClass(String type1, String type2) { return "java/lang/Object"; }
    }

    public LoadingScreenTransformer() {}
}
