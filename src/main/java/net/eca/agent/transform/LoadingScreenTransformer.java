package net.eca.agent.transform;

import net.eca.agent.AgentLogWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * Agent transform module for DisplayWindow.
 * Replaces the solid-color glClear in paintFramebuffer() with a vertical gradient:
 * purple (128,0,128) at top/bottom edges → red (239,50,61) at center.
 *
 * DisplayWindow is loaded before the agent attaches (ImmediateWindowProvider via ServiceLoader),
 * so this relies on retransformation via getTargetClassNames().
 *
 * Note: retransformation cannot add new methods, so the gradient code is inlined
 * directly into paintFramebuffer() replacing the original glClear call.
 */
public class LoadingScreenTransformer implements ITransformModule {

    private static final String TARGET_CLASS = "net/minecraftforge/fml/earlydisplay/DisplayWindow";
    private static final String PAINT_METHOD = "paintFramebuffer";
    private static final String PAINT_DESC = "()V";

    private static final String CONTEXT_FIELD = "context";
    private static final String CONTEXT_TYPE = "net/minecraftforge/fml/earlydisplay/RenderElement$DisplayContext";
    private static final String CONTEXT_DESC = "L" + CONTEXT_TYPE + ";";

    private static final String GL = "org/lwjgl/opengl/GL11C";

    private static final int GL_SCISSOR_TEST = 0x0C11;
    private static final int CLEAR_BITS = 16640; // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT

    private static final int BANDS = 32;

    // center = red (239,50,61), edge = purple (128,0,128)
    private static final float CR = 239f / 255f, CG = 50f / 255f, CB = 61f / 255f;
    private static final float DR = 128f / 255f - CR;  // -0.435
    private static final float DG = -CG;               // -0.196
    private static final float DB = 128f / 255f - CB;  //  0.263

    @Override
    public String getName() {
        return "LoadingScreenTransformer";
    }

    @Override
    public boolean shouldTransform(String className, ClassLoader classLoader) {
        return TARGET_CLASS.equals(className);
    }

    @Override
    public List<String> getTargetClassNames() {
        return List.of("net.minecraftforge.fml.earlydisplay.DisplayWindow");
    }

    @Override
    public byte[] transform(String className, byte[] classfileBuffer) {
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            DisplayWindowVisitor cv = new DisplayWindowVisitor(cw);
            cr.accept(cv, ClassReader.EXPAND_FRAMES);

            if (cv.transformed) {
                byte[] result = cw.toByteArray();
                AgentLogWriter.info("[LoadingScreenTransformer] Transformed DisplayWindow.paintFramebuffer()");
                return result;
            }
            AgentLogWriter.warn("[LoadingScreenTransformer] paintFramebuffer() not found in DisplayWindow");
            return null;
        } catch (Throwable t) {
            AgentLogWriter.error("[LoadingScreenTransformer] Failed to transform DisplayWindow", t);
            return null;
        }
    }

    private static class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader cr, int flags) {
            super(cr, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
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

    /**
     * Replaces the first glClear(16640) call in paintFramebuffer() with gradient code.
     *
     * Strategy: hold back SIPUSH 16640, and if the next instruction is INVOKESTATIC glClear,
     * emit gradient code instead. Otherwise flush the held SIPUSH normally.
     */
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

        // flush held SIPUSH before any other instruction
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

        /**
         * Emit inline gradient clear replacing the original glClear(CLEAR_BITS).
         *
         * Local variable slots: 0=this, 1=h, 2=w, 3=i, 4=y0, 5=y1, 6=t
         * (original method only uses slot 0)
         */
        private void emitGradient() {
            MethodVisitor mv = this.mv;

            Label lblContextOk = new Label();
            Label lblGradient = new Label();
            Label lblDone = new Label();
            Label lblLoopStart = new Label();
            Label lblLoopEnd = new Label();

            // --- null/sanity check ---
            // if (this.context == null) → fallback plain clear
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, CONTEXT_FIELD, CONTEXT_DESC);
            mv.visitJumpInsn(Opcodes.IFNONNULL, lblContextOk);

            mv.visitIntInsn(Opcodes.SIPUSH, CLEAR_BITS);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glClear", "(I)V", false);
            mv.visitJumpInsn(Opcodes.GOTO, lblDone);

            // --- get viewport dimensions from this.context ---
            mv.visitLabel(lblContextOk);

            // int h = this.context.scaledHeight();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, CONTEXT_FIELD, CONTEXT_DESC);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTEXT_TYPE, "scaledHeight", "()I", false);
            mv.visitVarInsn(Opcodes.ISTORE, 1);

            // if (h <= 0) → fallback
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitJumpInsn(Opcodes.IFGT, lblGradient);

            mv.visitIntInsn(Opcodes.SIPUSH, CLEAR_BITS);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glClear", "(I)V", false);
            mv.visitJumpInsn(Opcodes.GOTO, lblDone);

            // --- gradient rendering via scissored clears ---
            mv.visitLabel(lblGradient);

            // int w = this.context.scaledWidth();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, CONTEXT_FIELD, CONTEXT_DESC);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTEXT_TYPE, "scaledWidth", "()I", false);
            mv.visitVarInsn(Opcodes.ISTORE, 2);

            // glEnable(GL_SCISSOR_TEST)
            mv.visitIntInsn(Opcodes.SIPUSH, GL_SCISSOR_TEST);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glEnable", "(I)V", false);

            // for (int i = 0; i < BANDS; i++)
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 3);
            mv.visitLabel(lblLoopStart);

            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitIntInsn(Opcodes.BIPUSH, BANDS);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, lblLoopEnd);

            // int y0 = h * i / BANDS
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitIntInsn(Opcodes.BIPUSH, BANDS);
            mv.visitInsn(Opcodes.IDIV);
            mv.visitVarInsn(Opcodes.ISTORE, 4);

            // int y1 = h * (i + 1) / BANDS
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitIntInsn(Opcodes.BIPUSH, BANDS);
            mv.visitInsn(Opcodes.IDIV);
            mv.visitVarInsn(Opcodes.ISTORE, 5);

            // float t = Math.abs(2.0f * ((y0 + y1) / 2.0f) / (float)h - 1.0f)
            // stack trace: 2.0f → (y0+y1) → I2F → /2.0f → *2.0f → /h → -1.0f → abs
            mv.visitLdcInsn(2.0f);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitInsn(Opcodes.IADD);
            mv.visitInsn(Opcodes.I2F);
            mv.visitLdcInsn(2.0f);
            mv.visitInsn(Opcodes.FDIV);       // mid = (y0+y1)/2.0f
            mv.visitInsn(Opcodes.FMUL);       // 2.0f * mid
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitInsn(Opcodes.I2F);
            mv.visitInsn(Opcodes.FDIV);       // 2.0f * mid / h
            mv.visitInsn(Opcodes.FCONST_1);
            mv.visitInsn(Opcodes.FSUB);       // ... - 1.0f
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(F)F", false);
            mv.visitVarInsn(Opcodes.FSTORE, 6);

            // glClearColor(CR + DR*t, CG + DG*t, CB + DB*t, 1.0f)
            // red component
            mv.visitLdcInsn(CR);
            mv.visitLdcInsn(DR);
            mv.visitVarInsn(Opcodes.FLOAD, 6);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FADD);
            // green component
            mv.visitLdcInsn(CG);
            mv.visitLdcInsn(DG);
            mv.visitVarInsn(Opcodes.FLOAD, 6);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FADD);
            // blue component
            mv.visitLdcInsn(CB);
            mv.visitLdcInsn(DB);
            mv.visitVarInsn(Opcodes.FLOAD, 6);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FADD);
            // alpha = 1.0f
            mv.visitInsn(Opcodes.FCONST_1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glClearColor", "(FFFF)V", false);

            // glScissor(0, y0, w, y1 - y0)
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 5);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.ISUB);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glScissor", "(IIII)V", false);

            // glClear(CLEAR_BITS)
            mv.visitIntInsn(Opcodes.SIPUSH, CLEAR_BITS);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glClear", "(I)V", false);

            // i++
            mv.visitIincInsn(3, 1);
            mv.visitJumpInsn(Opcodes.GOTO, lblLoopStart);

            // --- end of loop ---
            mv.visitLabel(lblLoopEnd);

            // glDisable(GL_SCISSOR_TEST)
            mv.visitIntInsn(Opcodes.SIPUSH, GL_SCISSOR_TEST);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL, "glDisable", "(I)V", false);

            // --- done, method continues with glEnable(GL_BLEND) etc. ---
            mv.visitLabel(lblDone);
        }
    }
}
