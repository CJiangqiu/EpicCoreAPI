package net.eca.util.health;

import net.eca.util.EcaLogger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/*
 * 函数式调用的参数来源分析：从字节码中的实际数组写入恢复标记参数、数值位置和令牌获取步骤，
 * 为 MethodProbe 提供有依据的调用协议，避免盲目枚举参数组合。
 * 仅在受限范围内追踪可确定的来源；遇到分支、数组逃逸或含糊来源时放弃该候选，
 * 本类不执行候选调用，实际调用及效果校验由方法探针负责。
 */
final class FunctionalArgumentAnalyzer {
    private static final Object UNKNOWN = new Object();

    private FunctionalArgumentAnalyzer() {}

    record StaticArgument(String owner, String name, String desc, Integer index) {
        Object resolve(ClassLoader loader) {
            try {
                Class<?> type = Class.forName(owner.replace('/', '.'), false, loader);
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    try {
                        Field field = current.getDeclaredField(name);
                        if (!Modifier.isStatic(field.getModifiers()) || !Type.getDescriptor(field.getType()).equals(desc)) return null;
                        field.setAccessible(true);
                        Object value = field.get(null);
                        return index == null ? value : Array.get(value, index);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                EcaLogger.info("[MethodProbe] argument source read failed: {}", exception.getClass().getSimpleName());
            }
            return null;
        }
    }

    static List<MethodProbe.FunctionalProtocol> find(String owner, MethodNode method, String fieldOwner,
                                                    String fieldName, String fieldDesc, String samOwner,
                                                    String samName, String samDesc) {
        List<MethodProbe.FunctionalProtocol> result = new ArrayList<>();
        if (method.instructions.size() == 0 || method.instructions.size() > 12000) return result;
        boolean hasCall = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(samOwner)
                    && call.name.equals(samName) && call.desc.equals(samDesc)) { hasCall = true; break; }
        }
        if (!hasCall) return result;
        try {
            SourceInterpreter interpreter = new SourceInterpreter(Opcodes.ASM9) {
                @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
                @Override public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
                    return instruction.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(instruction, value);
                }
            };
            Frame<SourceValue>[] frames = new Analyzer<>(interpreter).analyze(owner, method);
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(samOwner)
                        || !call.name.equals(samName) || !call.desc.equals(samDesc)) continue;
                Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
                if (!receiverMatches(frame, fieldOwner, fieldName, fieldDesc)) continue;
                SourceValue[] arguments = arguments(method, frames, call, frame.getStack(frame.getStackSize() - 1), 2);
                if (arguments == null) continue;
                for (int markerIndex = 0; markerIndex < 2; markerIndex++) {
                    AbstractInsnNode numeric = single(arguments[1 - markerIndex]);
                    if (!(numeric instanceof MethodInsnNode box) || !box.name.equals("valueOf")
                            || box.getOpcode() != Opcodes.INVOKESTATIC
                            || !box.owner.equals("java/lang/Float") || !box.desc.equals("(F)Ljava/lang/Float;")) continue;
                    Object marker = constant(method, frames, arguments[markerIndex], 0);
                    boolean acquire = false;
                    AbstractInsnNode source = single(arguments[markerIndex]);
                    if (marker == UNKNOWN && source instanceof MethodInsnNode inner
                            && inner.owner.equals(samOwner) && inner.name.equals(samName) && inner.desc.equals(samDesc)) {
                        Frame<SourceValue> innerFrame = frames[method.instructions.indexOf(inner)];
                        if (!receiverMatches(innerFrame, fieldOwner, fieldName, fieldDesc)) continue;
                        SourceValue[] tokenArgs = arguments(method, frames, inner,
                                innerFrame.getStack(innerFrame.getStackSize() - 1), 1);
                        if (tokenArgs == null) continue;
                        marker = constant(method, frames, tokenArgs[0], 0);
                        acquire = true;
                    }
                    if (marker != UNKNOWN) result.add(new MethodProbe.FunctionalProtocol(marker, markerIndex == 0, acquire));
                    if (result.size() >= 16) return result;
                }
            }
        } catch (AnalyzerException | RuntimeException exception) {
            EcaLogger.info("[MethodProbe] argument flow unavailable: {}", exception.getClass().getSimpleName());
        }
        return result;
    }

    private static boolean receiverMatches(Frame<SourceValue> frame, String owner, String name, String desc) {
        return frame != null && frame.getStackSize() >= 2
                && single(frame.getStack(frame.getStackSize() - 2)) instanceof FieldInsnNode field
                && MethodProbe.resolvesToField(field, owner, name, desc);
    }

    private static SourceValue[] arguments(MethodNode method, Frame<SourceValue>[] frames,
                                           AbstractInsnNode call, SourceValue array, int count) {
        AbstractInsnNode allocation = single(array);
        if (allocation == null || allocation.getOpcode() != Opcodes.ANEWARRAY) return null;
        int start = method.instructions.indexOf(allocation), end = method.instructions.indexOf(call);
        if (start < 0 || end <= start || end - start > 300) return null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump) {
                int target = method.instructions.indexOf(jump.label);
                if (target > start && target <= end) return null;
            }
            if (instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode) return null;
        }
        Frame<SourceValue> allocationFrame = frames[start];
        if (allocationFrame == null || !Integer.valueOf(count).equals(constant(method, frames,
                allocationFrame.getStack(allocationFrame.getStackSize() - 1), 0))) return null;
        SourceValue[] values = new SourceValue[count];
        for (int i = start + 1; i < end; i++) {
            AbstractInsnNode instruction = method.instructions.get(i);
            // 分支数组和别名逃逸需要更完整的堆分析，不在这里猜测参数。
            if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode) return null;
            Frame<SourceValue> frame = frames[i];
            if (frame == null) return null;
            if (instruction instanceof MethodInsnNode invocation) {
                int consumed = Type.getArgumentTypes(invocation.desc).length
                        + (invocation.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
                for (int slot = frame.getStackSize() - consumed; slot < frame.getStackSize(); slot++) {
                    if (slot >= 0 && single(frame.getStack(slot)) == allocation) return null;
                }
            }
            if ((instruction.getOpcode() == Opcodes.PUTSTATIC || instruction.getOpcode() == Opcodes.PUTFIELD
                    || instruction.getOpcode() == Opcodes.AASTORE) && frame.getStackSize() > 0
                    && single(frame.getStack(frame.getStackSize() - 1)) == allocation) return null;
            if (instruction.getOpcode() != Opcodes.AASTORE || frame.getStackSize() < 3) continue;
            if (single(frame.getStack(frame.getStackSize() - 3)) != allocation) continue;
            Object index = constant(method, frames, frame.getStack(frame.getStackSize() - 2), 0);
            if (!(index instanceof Integer slot) || slot < 0 || slot >= count || values[slot] != null) return null;
            values[slot] = frame.getStack(frame.getStackSize() - 1);
        }
        for (SourceValue value : values) if (value == null) return null;
        return values;
    }

    private static Object constant(MethodNode method, Frame<SourceValue>[] frames, SourceValue value, int depth) {
        if (depth > 4) return UNKNOWN;
        AbstractInsnNode instruction = single(value);
        if (instruction == null) return UNKNOWN;
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if (opcode == Opcodes.ACONST_NULL) return null;
        if (instruction instanceof IntInsnNode integer && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return integer.operand;
        if (instruction instanceof LdcInsnNode ldc && (ldc.cst instanceof String || ldc.cst instanceof Number)) return ldc.cst;
        if (instruction instanceof FieldInsnNode field && opcode == Opcodes.GETSTATIC)
            return new StaticArgument(field.owner, field.name, field.desc, null);
        if (opcode == Opcodes.AALOAD) {
            Frame<SourceValue> frame = frames[method.instructions.indexOf(instruction)];
            if (frame == null || frame.getStackSize() < 2) return UNKNOWN;
            Object container = constant(method, frames, frame.getStack(frame.getStackSize() - 2), depth + 1);
            Object index = constant(method, frames, frame.getStack(frame.getStackSize() - 1), depth + 1);
            if (container instanceof StaticArgument field && field.index() == null && index instanceof Integer slot)
                return new StaticArgument(field.owner(), field.name(), field.desc(), slot);
        }
        return UNKNOWN;
    }

    private static AbstractInsnNode single(SourceValue value) {
        return value != null && value.insns.size() == 1 ? value.insns.iterator().next() : null;
    }
}
