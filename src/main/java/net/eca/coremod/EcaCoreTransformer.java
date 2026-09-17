package net.eca.coremod;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import net.eca.agent.AgentLogWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashSet;
import java.util.Set;

public final class EcaCoreTransformer implements ITransformer<ClassNode> {

    private static final String GET_HEALTH = "m_21223_";
    private static final String GET_MAX_HEALTH = "m_21233_";
    private static final String IS_DEAD_OR_DYING = "m_21224_";
    private static final String IS_ALIVE = "m_6084_";
    private static final String IS_REMOVED = "m_213877_";
    private static final String LIVING_HOOK = "net/eca/coremod/LivingEntityHook";
    private static final String ENTITY_HOOK = "net/eca/coremod/EntityHook";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String ECA_CONTAINER = "net/eca/coremod/EcaContainers$";

    @Override
    public ClassNode transform(ClassNode classNode, ITransformerVotingContext context) {
        try {
            if (RuntimeExtensionBridge.hasEarlyDisplayTransformer()
                    && RuntimeExtensionBridge.EARLY_DISPLAY_TARGET.equals(classNode.name)) {
                return transformLoadingScreen(classNode);
            } else if (LIVING_ENTITY.equals(classNode.name)) {
                transformLivingEntity(classNode);
            } else if (ENTITY.equals(classNode.name)) {
                transformEntity(classNode);
            } else {
                transformContainer(classNode);
            }
        } catch (Throwable t) {
            AgentLogWriter.error("[EcaCoreTransformer] Load-time transformation failed: " + classNode.name, t);
        }
        return classNode;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public Set<Target> targets() {
        Set<Target> targets = new HashSet<>();
        targets.add(Target.targetClass("net.minecraft.world.entity.LivingEntity"));
        targets.add(Target.targetClass("net.minecraft.world.entity.Entity"));
        targets.add(Target.targetClass("net.minecraft.world.level.entity.EntityTickList"));
        targets.add(Target.targetClass("net.minecraft.world.level.entity.EntityLookup"));
        targets.add(Target.targetClass("net.minecraft.util.ClassInstanceMultiMap"));
        targets.add(Target.targetClass("net.minecraft.server.level.ChunkMap"));
        targets.add(Target.targetClass("net.minecraft.world.level.entity.PersistentEntitySectionManager"));
        targets.add(Target.targetClass("net.minecraft.world.level.entity.EntitySectionStorage"));
        targets.add(Target.targetClass("net.minecraft.server.level.ServerLevel"));
        if (RuntimeExtensionBridge.hasEarlyDisplayTransformer()) {
            targets.add(Target.targetClass(RuntimeExtensionBridge.EARLY_DISPLAY_TARGET.replace('/', '.')));
        }
        return targets;
    }

    @Override
    public String[] labels() {
        return new String[]{"eca_core"};
    }

    private static void transformLivingEntity(ClassNode classNode) {
        int transformed = 0;
        for (MethodNode method : classNode.methods) {
            if (GET_HEALTH.equals(method.name) && "()F".equals(method.desc)) {
                if (!hasHook(method, LIVING_HOOK, "processGetHealth")) {
                    injectFloatHead(method, LIVING_HOOK, "processGetHealth",
                            "(Lnet/minecraft/world/entity/LivingEntity;)F", LIVING_ENTITY);
                    transformed++;
                }
                if (!hasHook(method, LIVING_HOOK, "processGetHealthResult")) {
                    injectFloatResult(method, LIVING_HOOK, "processGetHealthResult",
                            "(Lnet/minecraft/world/entity/LivingEntity;F)F");
                    transformed++;
                }
            } else if (GET_MAX_HEALTH.equals(method.name) && "()F".equals(method.desc)) {
                if (!hasHook(method, LIVING_HOOK, "processGetMaxHealth")) {
                    injectFloatHead(method, LIVING_HOOK, "processGetMaxHealth",
                            "(Lnet/minecraft/world/entity/LivingEntity;)F", LIVING_ENTITY);
                    transformed++;
                }
            } else if (IS_DEAD_OR_DYING.equals(method.name) && "()Z".equals(method.desc)) {
                if (!hasHook(method, LIVING_HOOK, "processIsDeadOrDying")) {
                    injectBooleanHead(method, LIVING_HOOK, "processIsDeadOrDying",
                            "(Lnet/minecraft/world/entity/LivingEntity;)I", LIVING_ENTITY);
                    transformed++;
                }
            } else if (IS_ALIVE.equals(method.name) && "()Z".equals(method.desc)
                    && !hasHook(method, LIVING_HOOK, "processIsAlive")) {
                injectBooleanHead(method, LIVING_HOOK, "processIsAlive",
                        "(Lnet/minecraft/world/entity/LivingEntity;)I", LIVING_ENTITY);
                transformed++;
            }
        }
        if (transformed > 0) {
            AgentLogWriter.info("[EcaCoreTransformer] Installed base living hooks=" + transformed);
        }
    }

    private static ClassNode transformLoadingScreen(ClassNode classNode) {
        ClassWriter inputWriter = new SafeClassWriter(0);
        classNode.accept(inputWriter);
        byte[] transformed = RuntimeExtensionBridge.transformEarlyDisplay(inputWriter.toByteArray());
        if (transformed == null) return classNode;
        ClassNode result = new ClassNode();
        new ClassReader(transformed).accept(result, ClassReader.EXPAND_FRAMES);
        return result;
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }

    private static void transformEntity(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if (!IS_REMOVED.equals(method.name) || !"()Z".equals(method.desc)
                    || hasHook(method, ENTITY_HOOK, "processIsRemoved")) continue;
            injectBooleanHead(method, ENTITY_HOOK, "processIsRemoved",
                    "(Lnet/minecraft/world/entity/Entity;)I", ENTITY);
            AgentLogWriter.info("[EcaCoreTransformer] Installed base removal hook");
            return;
        }
    }

    private static boolean hasHook(MethodNode method, String owner, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && owner.equals(call.owner) && name.equals(call.name)) {
                return true;
            }
        }
        return false;
    }

    private static void injectFloatHead(MethodNode method, String hookOwner, String hookName,
                                        String hookDescriptor, String castType) {
        InsnList hook = new InsnList();
        LabelNode passthrough = new LabelNode();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, castType));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDescriptor, false));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new InsnNode(Opcodes.FCMPL));
        hook.add(new JumpInsnNode(Opcodes.IFLT, passthrough));
        hook.add(new InsnNode(Opcodes.FRETURN));
        hook.add(passthrough);
        hook.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(hook);
    }

    private static void injectFloatResult(MethodNode method, String hookOwner, String hookName,
                                          String hookDescriptor) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.FRETURN) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new InsnNode(Opcodes.SWAP));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDescriptor, false));
            method.instructions.insertBefore(instruction, hook);
        }
    }

    private static void injectBooleanHead(MethodNode method, String hookOwner, String hookName,
                                          String hookDescriptor, String castType) {
        InsnList hook = new InsnList();
        LabelNode passthrough = new LabelNode();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new TypeInsnNode(Opcodes.CHECKCAST, castType));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hookOwner, hookName, hookDescriptor, false));
        hook.add(new InsnNode(Opcodes.DUP));
        hook.add(new InsnNode(Opcodes.ICONST_M1));
        hook.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, passthrough));
        hook.add(new InsnNode(Opcodes.IRETURN));
        hook.add(passthrough);
        hook.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(hook);
    }

    private static void transformContainer(ClassNode classNode) {
        int transformed = switch (classNode.name) {
            case "net/minecraft/world/level/entity/EntityTickList" ->
                    replaceNew(classNode, "it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap",
                            ECA_CONTAINER + "EcaInt2ObjectLinkedOpenHashMap");
            case "net/minecraft/world/level/entity/EntityLookup" ->
                    replaceNew(classNode, "it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap",
                            ECA_CONTAINER + "EcaInt2ObjectLinkedOpenHashMap")
                            + replaceFactory(classNode, "com/google/common/collect/Maps", "newHashMap",
                            ECA_CONTAINER + "EcaHashMap");
            case "net/minecraft/util/ClassInstanceMultiMap" ->
                    replaceFactory(classNode, "com/google/common/collect/Maps", "newHashMap",
                            ECA_CONTAINER + "EcaHashMap")
                            + replaceFactory(classNode, "com/google/common/collect/Lists", "newArrayList",
                            ECA_CONTAINER + "EcaArrayList");
            case "net/minecraft/server/level/ChunkMap" ->
                    replaceNew(classNode, "it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap",
                            ECA_CONTAINER + "EcaInt2ObjectOpenHashMap");
            case "net/minecraft/world/level/entity/PersistentEntitySectionManager" ->
                    replaceFactory(classNode, "com/google/common/collect/Sets", "newHashSet",
                            ECA_CONTAINER + "EcaHashSet")
                            + replaceFactory(classNode, "com/google/common/collect/Queues", "newConcurrentLinkedQueue",
                            ECA_CONTAINER + "EcaConcurrentLinkedQueue");
            case "net/minecraft/world/level/entity/EntitySectionStorage" ->
                    replaceNew(classNode, "it/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap",
                            ECA_CONTAINER + "EcaLong2ObjectOpenHashMap")
                            + replaceNew(classNode, "it/unimi/dsi/fastutil/longs/LongAVLTreeSet",
                            ECA_CONTAINER + "EcaLongAVLTreeSet");
            case "net/minecraft/server/level/ServerLevel" ->
                    replaceFactory(classNode, "com/google/common/collect/Lists", "newArrayList",
                            ECA_CONTAINER + "EcaArrayList")
                            + replaceNew(classNode, "it/unimi/dsi/fastutil/objects/ObjectOpenHashSet",
                            ECA_CONTAINER + "EcaHashSet");
            default -> 0;
        };
        if (transformed > 0) {
            AgentLogWriter.info("[EcaCoreTransformer] Installed protected containers=" + transformed
                    + " target=" + classNode.name);
        }
    }

    private static int replaceNew(ClassNode classNode, String original, String replacement) {
        int transformed = 0;
        for (MethodNode method : classNode.methods) {
            if (!"<init>".equals(method.name) && !"<clinit>".equals(method.name)) continue;
            AbstractInsnNode[] instructions = method.instructions.toArray();
            for (int index = 0; index < instructions.length; index++) {
                if (!(instructions[index] instanceof TypeInsnNode type)
                        || type.getOpcode() != Opcodes.NEW || !original.equals(type.desc)) continue;
                type.desc = replacement;
                for (int next = index + 1; next < Math.min(instructions.length, index + 10); next++) {
                    if (!(instructions[next] instanceof MethodInsnNode call)
                            || call.getOpcode() != Opcodes.INVOKESPECIAL
                            || !original.equals(call.owner) || !"<init>".equals(call.name)) continue;
                    call.owner = replacement;
                    transformed++;
                    break;
                }
            }
        }
        return transformed;
    }

    private static int replaceFactory(ClassNode classNode, String owner, String name, String replacement) {
        int transformed = 0;
        for (MethodNode method : classNode.methods) {
            if (!"<init>".equals(method.name) && !"<clinit>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !owner.equals(call.owner) || !name.equals(call.name)
                        || !call.desc.startsWith("()")) continue;
                InsnList replacementInstructions = new InsnList();
                replacementInstructions.add(new TypeInsnNode(Opcodes.NEW, replacement));
                replacementInstructions.add(new InsnNode(Opcodes.DUP));
                replacementInstructions.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, replacement, "<init>", "()V", false));
                method.instructions.insert(instruction, replacementInstructions);
                method.instructions.remove(instruction);
                transformed++;
            }
        }
        return transformed;
    }
}
