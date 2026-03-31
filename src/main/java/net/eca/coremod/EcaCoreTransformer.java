package net.eca.coremod;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import net.eca.agent.AgentLogWriter;
import org.objectweb.asm.tree.*;

import java.util.HashSet;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/**
 * CoreMod-level ITransformer for ModLauncher pipeline.
 * Transforms classes at first load time — no Agent required.
 *
 * Handles:
 * - LivingEntity: getHealth, getMaxHealth, isDeadOrDying, isAlive hook injection
 * - Entity: isRemoved hook injection
 * - Container replacement (7 MC classes)
 */
public class EcaCoreTransformer implements ITransformer<ClassNode> {

    // ==================== SRG 方法名 ====================

    private static final String GET_HEALTH       = "m_21223_";
    private static final String GET_MAX_HEALTH   = "m_21233_";
    private static final String IS_DEAD_OR_DYING = "m_21224_";
    private static final String IS_ALIVE         = "m_6084_";
    private static final String IS_REMOVED       = "m_213877_";

    // ==================== Hook 类路径 ====================

    private static final String LIVING_HOOK  = "net/eca/coremod/LivingEntityHook";
    private static final String ENTITY_HOOK  = "net/eca/coremod/EntityHook";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String ENTITY        = "net/minecraft/world/entity/Entity";

    // ==================== 容器替换前缀 ====================

    private static final String ECA_CONTAINER = "net/eca/coremod/EcaContainers$";

    // ==================== ITransformer ====================

    @Override
    public ClassNode transform(ClassNode classNode, ITransformerVotingContext context) {
        String name = classNode.name;
        try {
            if (LIVING_ENTITY.equals(name)) {
                transformLivingEntity(classNode);
            } else if (ENTITY.equals(name)) {
                transformEntity(classNode);
            } else {
                transformContainer(name, classNode);
            }
        } catch (Throwable t) {
            AgentLogWriter.error("[EcaCoreTransformer] Failed to transform: " + name, t);
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
        // 容器替换目标
        targets.add(Target.targetClass("net.minecraft.world.level.entity.EntityTickList"));
        targets.add(Target.targetClass("net.minecraft.world.level.entity.EntityLookup"));
        targets.add(Target.targetClass("net.minecraft.util.ClassInstanceMultiMap"));
        targets.add(Target.targetClass("net.minecraft.server.level.ChunkMap"));
        targets.add(Target.targetClass("net.minecraft.world.level.entity.PersistentEntitySectionManager"));
        targets.add(Target.targetClass("net.minecraft.world.level.entity.EntitySectionStorage"));
        targets.add(Target.targetClass("net.minecraft.server.level.ServerLevel"));
        return targets;
    }

    @Override
    public String[] labels() {
        return new String[]{"eca_core"};
    }

    // ==================== LivingEntity 转换 ====================

    private void transformLivingEntity(ClassNode cn) {
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.desc.equals("()F")) {
                if (mn.name.equals(GET_HEALTH)) {
                    injectFloatHook(mn, LIVING_HOOK, "processGetHealth",
                        "(Lnet/minecraft/world/entity/LivingEntity;)F", LIVING_ENTITY);
                    count++;
                } else if (mn.name.equals(GET_MAX_HEALTH)) {
                    injectFloatHook(mn, LIVING_HOOK, "processGetMaxHealth",
                        "(Lnet/minecraft/world/entity/LivingEntity;)F", LIVING_ENTITY);
                    count++;
                }
            } else if (mn.desc.equals("()Z")) {
                if (mn.name.equals(IS_DEAD_OR_DYING)) {
                    injectBooleanHook(mn, LIVING_HOOK, "processIsDeadOrDying",
                        "(Lnet/minecraft/world/entity/LivingEntity;)I", LIVING_ENTITY);
                    count++;
                } else if (mn.name.equals(IS_ALIVE)) {
                    injectBooleanHook(mn, LIVING_HOOK, "processIsAlive",
                        "(Lnet/minecraft/world/entity/LivingEntity;)I", LIVING_ENTITY);
                    count++;
                }
            }
        }
        if (count > 0) {
            AgentLogWriter.info("[EcaCoreTransformer] LivingEntity: injected " + count + " hooks");
        }
    }

    // ==================== Entity 转换 ====================

    private void transformEntity(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(IS_REMOVED) && mn.desc.equals("()Z")) {
                injectBooleanHook(mn, ENTITY_HOOK, "processIsRemoved",
                    "(Lnet/minecraft/world/entity/Entity;)I", ENTITY);
                AgentLogWriter.info("[EcaCoreTransformer] Entity: injected isRemoved hook");
                return;
            }
        }
    }

    // ==================== 容器替换 ====================

    private void transformContainer(String className, ClassNode cn) {
        int count = 0;
        switch (className) {
            case "net/minecraft/world/level/entity/EntityTickList":
                count = replaceNewInst(cn, "it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap", ECA_CONTAINER + "EcaInt2ObjectLinkedOpenHashMap");
                break;
            case "net/minecraft/world/level/entity/EntityLookup":
                count = replaceNewInst(cn, "it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap", ECA_CONTAINER + "EcaInt2ObjectLinkedOpenHashMap");
                count += replaceGuava(cn, "com/google/common/collect/Maps", "newHashMap", ECA_CONTAINER + "EcaHashMap");
                break;
            case "net/minecraft/util/ClassInstanceMultiMap":
                count = replaceGuava(cn, "com/google/common/collect/Maps", "newHashMap", ECA_CONTAINER + "EcaHashMap");
                count += replaceGuava(cn, "com/google/common/collect/Lists", "newArrayList", ECA_CONTAINER + "EcaArrayList");
                break;
            case "net/minecraft/server/level/ChunkMap":
                count = replaceNewInst(cn, "it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap", ECA_CONTAINER + "EcaInt2ObjectOpenHashMap");
                break;
            case "net/minecraft/world/level/entity/PersistentEntitySectionManager":
                count = replaceGuava(cn, "com/google/common/collect/Sets", "newHashSet", ECA_CONTAINER + "EcaHashSet");
                count += replaceGuava(cn, "com/google/common/collect/Queues", "newConcurrentLinkedQueue", ECA_CONTAINER + "EcaConcurrentLinkedQueue");
                break;
            case "net/minecraft/world/level/entity/EntitySectionStorage":
                count = replaceNewInst(cn, "it/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap", ECA_CONTAINER + "EcaLong2ObjectOpenHashMap");
                count += replaceNewInst(cn, "it/unimi/dsi/fastutil/longs/LongAVLTreeSet", ECA_CONTAINER + "EcaLongAVLTreeSet");
                break;
            case "net/minecraft/server/level/ServerLevel":
                count = replaceGuava(cn, "com/google/common/collect/Lists", "newArrayList", ECA_CONTAINER + "EcaArrayList");
                count += replaceNewInst(cn, "it/unimi/dsi/fastutil/objects/ObjectOpenHashSet", ECA_CONTAINER + "EcaHashSet");
                break;
        }
        if (count > 0) {
            AgentLogWriter.info("[EcaCoreTransformer] " + className + ": replaced " + count + " containers");
        }
    }

    // ==================== Hook 注入（Tree API）====================

    // float 方法 HEAD hook：非 NaN 则 FRETURN，NaN 则 fall through
    private void injectFloatHook(MethodNode mn, String hookClass, String hookMethod, String hookDesc, String castType) {
        InsnList hook = new InsnList();
        LabelNode passthrough = new LabelNode();

        hook.add(new VarInsnNode(ALOAD, 0));
        hook.add(new TypeInsnNode(CHECKCAST, castType));
        hook.add(new MethodInsnNode(INVOKESTATIC, hookClass, hookMethod, hookDesc, false));
        hook.add(new InsnNode(DUP));
        hook.add(new InsnNode(DUP));
        hook.add(new InsnNode(FCMPL));
        hook.add(new JumpInsnNode(IFLT, passthrough));
        hook.add(new InsnNode(FRETURN));
        hook.add(passthrough);
        hook.add(new InsnNode(POP));

        mn.instructions.insert(hook);
    }

    // boolean 方法 HEAD hook：-1 = passthrough，0/1 = IRETURN
    private void injectBooleanHook(MethodNode mn, String hookClass, String hookMethod, String hookDesc, String castType) {
        InsnList hook = new InsnList();
        LabelNode passthrough = new LabelNode();

        hook.add(new VarInsnNode(ALOAD, 0));
        hook.add(new TypeInsnNode(CHECKCAST, castType));
        hook.add(new MethodInsnNode(INVOKESTATIC, hookClass, hookMethod, hookDesc, false));
        hook.add(new InsnNode(DUP));
        hook.add(new InsnNode(ICONST_M1));
        hook.add(new JumpInsnNode(IF_ICMPEQ, passthrough));
        hook.add(new InsnNode(IRETURN));
        hook.add(passthrough);
        hook.add(new InsnNode(POP));

        mn.instructions.insert(hook);
    }

    // ==================== 容器替换引擎（无内部类，避免双加载后 NoClassDefFoundError）====================

    //替换 NEW + INVOKESPECIAL 模式（在构造函数中）
    private static int replaceNewInst(ClassNode cn, String original, String replacement) {
        int total = 0;
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals("<init>") && !mn.name.equals("<clinit>")) continue;
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
                            total++;
                            break;
                        }
                    }
                }
            }
        }
        return total;
    }

    //替换 Guava 工厂方法为 ECA 容器实例化（在构造函数中）
    private static int replaceGuava(ClassNode cn, String owner, String method, String replacement) {
        int total = 0;
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals("<init>") && !mn.name.equals("<clinit>")) continue;
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
                total++;
            }
        }
        return total;
    }
}
