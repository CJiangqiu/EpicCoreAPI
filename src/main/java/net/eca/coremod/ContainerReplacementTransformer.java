package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
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

    static byte[] transform(String className, byte[] classfileBuffer) {
        try {
            return switch (className) {
                case "net/minecraft/world/level/entity/EntityTickList" ->
                    replace(classfileBuffer, "EntityTickList", new Replacement[]{
                        newInst("it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap", ECA + "EcaInt2ObjectLinkedOpenHashMap")
                    });
                case "net/minecraft/world/level/entity/EntityLookup" ->
                    replace(classfileBuffer, "EntityLookup", new Replacement[]{
                        newInst("it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap", ECA + "EcaInt2ObjectLinkedOpenHashMap"),
                        guava("com/google/common/collect/Maps", "newHashMap", ECA + "EcaHashMap")
                    });
                case "net/minecraft/util/ClassInstanceMultiMap" ->
                    replace(classfileBuffer, "ClassInstanceMultiMap", new Replacement[]{
                        guava("com/google/common/collect/Maps", "newHashMap", ECA + "EcaHashMap"),
                        guava("com/google/common/collect/Lists", "newArrayList", ECA + "EcaArrayList")
                    });
                case "net/minecraft/server/level/ChunkMap" ->
                    replace(classfileBuffer, "ChunkMap", new Replacement[]{
                        newInst("it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap", ECA + "EcaInt2ObjectOpenHashMap")
                    });
                case "net/minecraft/world/level/entity/PersistentEntitySectionManager" ->
                    replace(classfileBuffer, "PersistentEntitySectionManager", new Replacement[]{
                        guava("com/google/common/collect/Sets", "newHashSet", ECA + "EcaHashSet"),
                        guava("com/google/common/collect/Queues", "newConcurrentLinkedQueue", ECA + "EcaConcurrentLinkedQueue")
                    });
                case "net/minecraft/world/level/entity/EntitySectionStorage" ->
                    replace(classfileBuffer, "EntitySectionStorage", new Replacement[]{
                        newInst("it/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap", ECA + "EcaLong2ObjectOpenHashMap"),
                        newInst("it/unimi/dsi/fastutil/longs/LongAVLTreeSet", ECA + "EcaLongAVLTreeSet")
                    });
                case "net/minecraft/server/level/ServerLevel" ->
                    replace(classfileBuffer, "ServerLevel", new Replacement[]{
                        guava("com/google/common/collect/Lists", "newArrayList", ECA + "EcaArrayList"),
                        newInst("it/unimi/dsi/fastutil/objects/ObjectOpenHashSet", ECA + "EcaHashSet")
                    });
                default -> null;
            };
        } catch (Throwable t) {
            AgentLogWriter.error("[ContainerReplacementTransformer] Failed: " + className, t);
            return null;
        }
    }

    // ==================== 替换引擎 ====================

    private static byte[] replace(byte[] classBytes, String simpleName, Replacement[] replacements) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);

        int total = 0;
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals("<init>") && !mn.name.equals("<clinit>")) continue;
            for (Replacement r : replacements) {
                total += r.apply(mn);
            }
        }

        if (total == 0) return null;

        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + total + " containers in " + simpleName);
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
