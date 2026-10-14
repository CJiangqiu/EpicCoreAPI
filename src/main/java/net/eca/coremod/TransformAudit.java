package net.eca.coremod;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.IdentityHashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// 诊断保留玩家健康方法的执行顺序，外部符号用会话内编号表示。
final class TransformAudit {
    private static final Set<String> PLAYER_CHAIN = Set.of(
            "net.minecraft.world.entity.Entity", "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.entity.player.Player", "net.minecraft.server.level.ServerPlayer",
            "net.minecraft.client.player.AbstractClientPlayer", "net.minecraft.client.player.LocalPlayer");
    private static final Set<String> HEALTH_METHODS = Set.of(
            "m_21223_", "m_21233_", "m_21224_", "m_6084_", "m_213877_",
            "getHealth", "getMaxHealth", "isDeadOrDying", "isAlive", "isRemoved");
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final Map<Object, Integer> IDS = new ConcurrentHashMap<>();

    private static final Set<String> CORE_TARGETS = Set.of("net.minecraft.world.entity.Entity",
            "net.minecraft.world.entity.LivingEntity", "net.minecraft.world.entity.player.Player");
    private record StageKey(Class<?> type, String phase, String result) {}
    private static final Map<StageKey, byte[]> STAGE_OUTPUTS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, byte[]> NATIVE_INPUTS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, byte[]> REJECTED_OUTPUTS = new ConcurrentHashMap<>();
    private record NativeStage(String phase, byte[] bytes) {}
    private static final ThreadLocal<List<NativeStage>> ACTIVE_NATIVE_STAGES = new ThreadLocal<>();
    private static final Map<Class<?>, List<NativeStage>> NATIVE_STAGES = new ConcurrentHashMap<>();

    private TransformAudit() {}

    static void captureNativeInput(Class<?> type, byte[] bytes) {
        if (type != null && "net.minecraft.world.entity.Entity".equals(type.getName()) && bytes != null) {
            NATIVE_INPUTS.put(type, bytes.clone());
            List<NativeStage> stages = new ArrayList<>();
            NATIVE_STAGES.put(type, stages);
            ACTIVE_NATIVE_STAGES.set(stages);
        }
    }

    // 回调内只保留快照，解析与日志输出留到 JVM 返回之后。
    static void captureNativeStage(String name, String phase, byte[] bytes) {
        if (!"net/minecraft/world/entity/Entity".equals(name)) return;
        List<NativeStage> stages = ACTIVE_NATIVE_STAGES.get();
        if (stages != null) stages.add(new NativeStage(phase, bytes == null ? null : bytes.clone()));
    }

    static void endNativeCapture() {
        ACTIVE_NATIVE_STAGES.remove();
    }

    // 单类请求被拒绝后才输出诊断，避免在转换回调持锁期间解析帧。
    static void reportRejected(Class<?> type, byte[] output) {
        byte[] input = NATIVE_INPUTS.get(type);
        if (input == null || output == null) return;
        byte[] previous = REJECTED_OUTPUTS.put(type, output.clone());
        if (Arrays.equals(previous, output)) return;
        try {
            report(type, "native-input", "BEFORE_REJECTED_TRANSFORM", input);
            report(type, "native-rejected", "REQUEST_REJECTED", output);
            reportFrameChanges("native-total", input, output);
            byte[] previousStage = input;
            for (NativeStage stage : NATIVE_STAGES.getOrDefault(type, List.of())) {
                byte[] next = stage.bytes() == null ? previousStage : stage.bytes();
                reportStageFrames(stage.phase(), previousStage, next);
                previousStage = next;
            }
            reportStageFrames("native-observer", previousStage, output);
        } catch (RuntimeException failure) {
            EarlyLogWriter.info("[TransformAudit] Rejected output diagnosis failed: "
                    + failure.getClass().getSimpleName());
        }
    }

    private static void reportStageFrames(String phase, byte[] input, byte[] output) {
        boolean identical = Arrays.equals(input, output);
        EarlyLogWriter.info("[TransformAudit] Native stage=" + phase
                + " bytesChanged=" + !identical + " inputBytes=" + input.length + " outputBytes=" + output.length);
        if (!identical) reportFrameChanges(phase, input, output);
    }

    private static void reportFrameChanges(String phase, byte[] input, byte[] output) {
        ClassNode before = new ClassNode(Opcodes.ASM9);
        ClassNode after = new ClassNode(Opcodes.ASM9);
        new ClassReader(input).accept(before, ClassReader.EXPAND_FRAMES);
        new ClassReader(output).accept(after, ClassReader.EXPAND_FRAMES);
        for (MethodNode original : before.methods) {
            for (MethodNode transformed : after.methods) {
                if (!original.name.equals(transformed.name) || !original.desc.equals(transformed.desc)) continue;
                List<FrameNode> oldFrames = frames(original);
                List<FrameNode> newFrames = frames(transformed);
                // 只比较指令形状未变的方法；新增 Hook 的帧位置不一定能直接对应。
                if (!opcodes(original).equals(opcodes(transformed)) || oldFrames.size() != newFrames.size()) break;
                for (int i = 0; i < oldFrames.size(); i++) {
                    reportWidening(phase, original, i, "local", oldFrames.get(i).local, newFrames.get(i).local);
                    reportWidening(phase, original, i, "stack", oldFrames.get(i).stack, newFrames.get(i).stack);
                }
                break;
            }
        }
    }

    private static List<FrameNode> frames(MethodNode method) {
        List<FrameNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FrameNode frame) result.add(frame);
        }
        return result;
    }

    private static List<Integer> opcodes(MethodNode method) {
        List<Integer> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) result.add(instruction.getOpcode());
        }
        return result;
    }

    private static void reportWidening(String phase, MethodNode method, int frame, String kind,
                                       List<Object> before, List<Object> after) {
        if (before == null || after == null || before.size() != after.size()) return;
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i) instanceof String type && !"java/lang/Object".equals(type)
                    && "java/lang/Object".equals(after.get(i))) {
                EarlyLogWriter.info("[TransformAudit] Frame widening candidate method="
                        + safeMethod("net/minecraft/world/entity/Entity", method.name) + descriptor(method.desc)
                        + " frame=" + frame + " " + kind + "=" + i + " from=" + symbol(type)
                        + " to=java/lang/Object stage=" + phase);
            }
        }
    }

    static boolean isCoreTarget(Class<?> type) {
        return type != null && CORE_TARGETS.contains(type.getName());
    }

    static void reportStage(Class<?> type, String phase, String result, byte[] bytes) {
        if (!isCoreTarget(type) || bytes == null) return;
        StageKey key = new StageKey(type, phase, result);
        byte[] previous = STAGE_OUTPUTS.put(key, bytes.clone());
        if (Arrays.equals(previous, bytes)) return;
        try {
            report(type, phase, result, bytes);
        } catch (RuntimeException failure) {
            EarlyLogWriter.info("[TransformAudit] Stage dump failed: " + failure.getClass().getSimpleName());
        }
    }

    private static String opaque(Object value) {
        return "external#" + IDS.computeIfAbsent(value, ignored -> NEXT_ID.incrementAndGet());
    }

    static void report(Class<?> type, String phase, String result, byte[] bytes) {
        String name = type.getName();
        String target = name.startsWith("net.minecraft.") || name.startsWith("net.eca.") ? name : opaque(type);
        EarlyLogWriter.info("[TransformAudit] phase=" + phase + " target=" + target + " result=" + result);
        if (bytes == null || !PLAYER_CHAIN.contains(name)) return;
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        StringBuilder text = new StringBuilder("[PlayerBytecode] phase=").append(phase)
                .append(" class=").append(name).append(" bytes=").append(bytes.length).append('\n');
        int count = 0;
        for (MethodNode method : node.methods) {
            if (!HEALTH_METHODS.contains(method.name)) continue;
            count++;
            text.append(method.name).append(method.desc).append(" access=").append(method.access).append('\n');
            Map<LabelNode, Integer> labels = new IdentityHashMap<>();
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LabelNode label) labels.put(label, labels.size());
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LabelNode label) {
                    text.append(" L").append(labels.get(label)).append(':').append('\n');
                    continue;
                }
                if (instruction.getOpcode() < 0) continue;
                text.append("  ").append(opcode(instruction.getOpcode()));
                if (instruction instanceof MethodInsnNode call) {
                    text.append(' ').append(symbol(call.owner)).append('.')
                            .append(safeMethod(call.owner, call.name)).append(descriptor(call.desc));
                } else if (instruction instanceof FieldInsnNode field) {
                    text.append(' ').append(symbol(field.owner)).append('.')
                            .append(safeMethod(field.owner, field.name)).append(' ').append(descriptor(field.desc));
                } else if (instruction instanceof TypeInsnNode typeInsn) {
                    text.append(' ').append(symbol(typeInsn.desc));
                } else if (instruction instanceof VarInsnNode var) {
                    text.append(' ').append(var.var);
                } else if (instruction instanceof JumpInsnNode jump) {
                    text.append(" L").append(labels.get(jump.label));
                } else if (instruction instanceof IntInsnNode integer) {
                    text.append(' ').append(integer.operand);
                } else if (instruction instanceof LdcInsnNode constant) {
                    text.append(' ').append(constant.cst instanceof Number ? constant.cst : opaque(constant.cst));
                }
                text.append('\n');
            }
        }
        if (count == 0) text.append("No declared health methods; inherited contracts checked separately.\n");
        EarlyLogWriter.info(text.toString());
    }

    private static String safeMethod(String owner, String name) {
        return (owner.startsWith("net/eca/") || owner.startsWith("java/")
                || name.matches("[mf]_[0-9]+_") || HEALTH_METHODS.contains(name) || name.equals("<init>"))
                ? name : opaque(owner + '.' + name);
    }

    private static String symbol(String name) {
        return name.startsWith("net/minecraft/") || name.startsWith("net/eca/") || name.startsWith("java/")
                ? name : opaque(name);
    }

    private static String descriptor(String descriptor) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < descriptor.length(); index++) {
            char character = descriptor.charAt(index);
            result.append(character);
            if (character == 'L') {
                int end = descriptor.indexOf(';', index);
                if (end < 0) return "invalid-descriptor";
                result.append(symbol(descriptor.substring(index + 1, end))).append(';');
                index = end;
            }
        }
        return result.toString();
    }

    private static String opcode(int code) {
        return switch (code) {
            case Opcodes.ALOAD -> "ALOAD";
            case Opcodes.FLOAD -> "FLOAD";
            case Opcodes.FRETURN -> "FRETURN";
            case Opcodes.IRETURN -> "IRETURN";
            case Opcodes.CHECKCAST -> "CHECKCAST";
            case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
            case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
            case Opcodes.DUP -> "DUP";
            case Opcodes.SWAP -> "SWAP";
            case Opcodes.POP -> "POP";
            case Opcodes.FCMPL -> "FCMPL";
            case Opcodes.IFLT -> "IFLT";
            case Opcodes.IF_ICMPEQ -> "IF_ICMPEQ";
            default -> "OP_" + code;
        };
    }
}
