package net.eca.coremod;

import net.eca.coremod.EarlyLogWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.*;

import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * Replaces vanilla MC container instantiations with ECA protected containers.
 * Targets 7 specific MC classes to intercept entity removal operations.
 */
final class ContainerReplacementTransformer {

    private static final String ECA = "net/eca/coremod/EcaContainers$";

    static final Set<String> TARGET_CLASSES = Set.of(
        "net/minecraft/world/level/entity/EntityTickList",
        "net/minecraft/world/level/entity/EntityLookup",
        "net/minecraft/util/ClassInstanceMultiMap",
        "net/minecraft/server/level/ChunkMap",
        "net/minecraft/world/level/entity/PersistentEntitySectionManager",
        "net/minecraft/world/level/entity/EntitySectionStorage",
        "net/minecraft/server/level/ServerLevel"
    );

    static boolean isTarget(String internalClassName) {
        return TARGET_CLASSES.contains(internalClassName);
    }

    static boolean verify(String className, byte[] bytes) {
        if (!isTarget(className) || bytes == null) return false;
        ClassNode node = new ClassNode(ASM9);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        boolean replaced = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>") && !method.name.equals("<clinit>")) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == NEW
                        && type.desc.startsWith(ECA)) replaced = true;
            }
        }
        // 原始构造点仍可被替换时，说明返回的字节码并未完成容器保护。
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>") && !method.name.equals("<clinit>")) continue;
            for (Replacement replacement : replacements(className)) {
                if (replacement.apply(method) > 0) return false;
            }
        }
        return replaced;
    }

    static byte[] transform(String className, byte[] classfileBuffer) {
        try {
            return replace(classfileBuffer, className, replacements(className));
        } catch (Throwable t) {
            EarlyLogWriter.error("[ContainerReplacementTransformer] Failed: " + className, t);
            return null;
        }
    }


    private static Replacement[] replacements(String className) {
            return switch (className) {
                case "net/minecraft/world/level/entity/EntityTickList" ->
                    new Replacement[]{
                        newInst("it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap", ECA + "EcaInt2ObjectLinkedOpenHashMap")
                    };
                case "net/minecraft/world/level/entity/EntityLookup" ->
                    new Replacement[]{
                        newInst("it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap", ECA + "EcaInt2ObjectLinkedOpenHashMap"),
                        guava("com/google/common/collect/Maps", "newHashMap", ECA + "EcaHashMap")
                    };
                case "net/minecraft/util/ClassInstanceMultiMap" ->
                    new Replacement[]{
                        guava("com/google/common/collect/Maps", "newHashMap", ECA + "EcaHashMap"),
                        guava("com/google/common/collect/Lists", "newArrayList", ECA + "EcaArrayList")
                    };
                case "net/minecraft/server/level/ChunkMap" ->
                    new Replacement[]{
                        newInst("it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap", ECA + "EcaInt2ObjectOpenHashMap")
                    };
                case "net/minecraft/world/level/entity/PersistentEntitySectionManager" ->
                    new Replacement[]{
                        guava("com/google/common/collect/Sets", "newHashSet", ECA + "EcaHashSet"),
                        guava("com/google/common/collect/Queues", "newConcurrentLinkedQueue", ECA + "EcaConcurrentLinkedQueue")
                    };
                case "net/minecraft/world/level/entity/EntitySectionStorage" ->
                    new Replacement[]{
                        newInst("it/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap", ECA + "EcaLong2ObjectOpenHashMap"),
                        newInst("it/unimi/dsi/fastutil/longs/LongAVLTreeSet", ECA + "EcaLongAVLTreeSet")
                    };
                case "net/minecraft/server/level/ServerLevel" ->
                    new Replacement[]{
                        guava("com/google/common/collect/Lists", "newArrayList", ECA + "EcaArrayList"),
                        newInst("it/unimi/dsi/fastutil/objects/ObjectOpenHashSet", ECA + "EcaHashSet")
                    };
                default -> new Replacement[0];
            };
    }

    // ==================== 替换引擎 ====================

    private static byte[] replace(byte[] classBytes, String simpleName, Replacement[] replacements) {
        ClassNode cn = new ClassNode(ASM9);
        new ClassReader(classBytes).accept(cn, ClassReader.EXPAND_FRAMES);

        int total = 0;
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals("<init>") && !mn.name.equals("<clinit>")) continue;
            for (Replacement r : replacements) {
                total += r.apply(mn);
            }
        }

        if (total == 0) return null;

        EarlyLogWriter.info("[ContainerReplacementTransformer] Replaced " + total + " containers in " + simpleName);
        ClassWriter cw = new EcaClassTransformer.SafeClassWriter(new ClassReader(classBytes), ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    // ==================== 替换策略 ====================

    private sealed interface Replacement {
        int apply(MethodNode mn);
    }

    private static NewInstReplacement newInst(String original, String replacement) {
        return new NewInstReplacement(original, replacement);
    }

    private static GuavaFactoryReplacement guava(String owner, String method, String replacement) {
        return new GuavaFactoryReplacement(owner, method, replacement);
    }

    //替换 NEW + INVOKESPECIAL <init> 模式
    private record NewInstReplacement(String original, String replacement) implements Replacement {
        @Override
        public int apply(MethodNode mn) {
            int count = 0;
            AbstractInsnNode[] insns = mn.instructions.toArray();
            for (int i = 0; i < insns.length; i++) {
                if (insns[i].getOpcode() != NEW) continue;
                TypeInsnNode typeInsn = (TypeInsnNode) insns[i];
                if (!typeInsn.desc.equals(original)) continue;

                typeInsn.desc = replacement;
                for (int j = i + 1; j < Math.min(insns.length, i + 10); j++) {
                    if (insns[j].getOpcode() == INVOKESPECIAL) {
                        MethodInsnNode methodInsn = (MethodInsnNode) insns[j];
                        if (methodInsn.owner.equals(original) && methodInsn.name.equals("<init>")) {
                            methodInsn.owner = replacement;
                            count++;
                            break;
                        }
                    }
                }
            }
            return count;
        }
    }

    //替换 INVOKESTATIC guavaFactory() 为 NEW + DUP + INVOKESPECIAL
    private record GuavaFactoryReplacement(String owner, String method, String replacement) implements Replacement {
        @Override
        public int apply(MethodNode mn) {
            int count = 0;
            AbstractInsnNode[] insns = mn.instructions.toArray();
            for (AbstractInsnNode insn : insns) {
                if (insn.getOpcode() != INVOKESTATIC) continue;
                MethodInsnNode methodInsn = (MethodInsnNode) insn;
                if (!methodInsn.owner.equals(owner) || !methodInsn.name.equals(method)) continue;
                if (!methodInsn.desc.startsWith("()")) continue;

                InsnList rep = new InsnList();
                rep.add(new TypeInsnNode(NEW, replacement));
                rep.add(new InsnNode(DUP));
                rep.add(new MethodInsnNode(INVOKESPECIAL, replacement, "<init>", "()V", false));

                mn.instructions.insert(insn, rep);
                mn.instructions.remove(insn);
                count++;
            }
            return count;
        }
    }

    private ContainerReplacementTransformer() {}
}
