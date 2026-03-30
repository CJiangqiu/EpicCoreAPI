package net.eca.agent.transform;

import net.eca.agent.AgentLogWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.List;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/**
 * 容器替换Transformer
 * 将MC原版的底层容器替换为ECA自定义容器
 * 替换策略：
 * 1. EntityTickList: Int2ObjectLinkedOpenHashMap -> EcaInt2ObjectLinkedOpenHashMap
 * 2. EntityLookup: Int2ObjectLinkedOpenHashMap -> EcaInt2ObjectOpenHashMap (byId)
 *                  HashMap -> EcaHashMap (byUuid)
 * 3. ClassInstanceMultiMap: HashMap -> EcaHashMap (byClass)
 *                           ArrayList -> EcaArrayList (allInstances)
 * 4. ChunkMap: Int2ObjectOpenHashMap -> EcaInt2ObjectOpenHashMap (entityMap)
 * 5. PersistentEntitySectionManager:
 *    Sets.newHashSet() -> EcaHashSet (knownUuids)
 *    Queues.newConcurrentLinkedQueue() -> EcaConcurrentLinkedQueue (loadingInbox)
 * 6. EntitySectionStorage:
 *    Long2ObjectOpenHashMap -> EcaLong2ObjectOpenHashMap (sections)
 *    LongAVLTreeSet -> EcaLongAVLTreeSet (sectionIds)
 * 7. ServerLevel:
 *    Lists.newArrayList() -> EcaArrayList (players)
 *    ObjectOpenHashSet -> EcaHashSet (navigatingMobs)
 */
public class ContainerReplacementTransformer implements ITransformModule {

    private static final String ECA = "net/eca/agent/transform/EcaContainers$";

    @Override
    public String getName() {
        return "ContainerReplacementTransformer";
    }

    @Override
    public List<String> getTargetClassNames() {
        return List.of(
            "net.minecraft.world.level.entity.EntityTickList",
            "net.minecraft.world.level.entity.EntityLookup",
            "net.minecraft.util.ClassInstanceMultiMap",
            "net.minecraft.server.level.ChunkMap",
            "net.minecraft.world.level.entity.PersistentEntitySectionManager",
            "net.minecraft.world.level.entity.EntitySectionStorage",
            "net.minecraft.server.level.ServerLevel"
        );
    }

    @Override
    public boolean shouldTransform(String className, ClassLoader classLoader) {
        return className.equals("net/minecraft/world/level/entity/EntityTickList") ||
               className.equals("net/minecraft/world/level/entity/EntityLookup") ||
               className.equals("net/minecraft/util/ClassInstanceMultiMap") ||
               className.equals("net/minecraft/server/level/ChunkMap") ||
               className.equals("net/minecraft/world/level/entity/PersistentEntitySectionManager") ||
               className.equals("net/minecraft/world/level/entity/EntitySectionStorage") ||
               className.equals("net/minecraft/server/level/ServerLevel");
    }

    @Override
    public byte[] transform(String className, byte[] classfileBuffer) {
        try {
            switch (className) {
                case "net/minecraft/world/level/entity/EntityTickList":
                    return replaceEntityTickListContainers(classfileBuffer);
                case "net/minecraft/world/level/entity/EntityLookup":
                    return replaceEntityLookupContainers(classfileBuffer);
                case "net/minecraft/util/ClassInstanceMultiMap":
                    return replaceClassInstanceMultiMapContainers(classfileBuffer);
                case "net/minecraft/server/level/ChunkMap":
                    return replaceChunkMapContainers(classfileBuffer);
                case "net/minecraft/world/level/entity/PersistentEntitySectionManager":
                    return replacePersistentEntitySectionManagerContainers(classfileBuffer);
                case "net/minecraft/world/level/entity/EntitySectionStorage":
                    return replaceEntitySectionStorageContainers(classfileBuffer);
                case "net/minecraft/server/level/ServerLevel":
                    return replaceServerLevelContainers(classfileBuffer);
                default:
                    return null;
            }
        } catch (Exception e) {
            AgentLogWriter.error("[ContainerReplacementTransformer] Failed to transform " + className, e);
        }
        return null;
    }

    private byte[] replaceEntityTickListContainers(byte[] classBytes) {
        AgentLogWriter.info("[ContainerReplacementTransformer] Transforming EntityTickList");
        ClassNode cn = readClassNode(classBytes);
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                count += replaceNewInstantiation(mn,
                    "it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap",
                    ECA + "EcaInt2ObjectLinkedOpenHashMap");
            }
        }
        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + count + " containers in EntityTickList");
        return writeClassNode(cn);
    }

    private byte[] replaceEntityLookupContainers(byte[] classBytes) {
        AgentLogWriter.info("[ContainerReplacementTransformer] Transforming EntityLookup");
        ClassNode cn = readClassNode(classBytes);
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                count += replaceNewInstantiation(mn,
                    "it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap",
                    ECA + "EcaInt2ObjectLinkedOpenHashMap");
                count += replaceGuavaFactoryCall(mn,
                    "com/google/common/collect/Maps", "newHashMap",
                    ECA + "EcaHashMap");
            }
        }
        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + count + " containers in EntityLookup");
        return writeClassNode(cn);
    }

    private byte[] replaceClassInstanceMultiMapContainers(byte[] classBytes) {
        AgentLogWriter.info("[ContainerReplacementTransformer] Transforming ClassInstanceMultiMap");
        ClassNode cn = readClassNode(classBytes);
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                count += replaceGuavaFactoryCall(mn,
                    "com/google/common/collect/Maps", "newHashMap",
                    ECA + "EcaHashMap");
                count += replaceGuavaFactoryCall(mn,
                    "com/google/common/collect/Lists", "newArrayList",
                    ECA + "EcaArrayList");
            }
        }
        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + count + " containers in ClassInstanceMultiMap");
        return writeClassNode(cn);
    }

    private byte[] replaceChunkMapContainers(byte[] classBytes) {
        AgentLogWriter.info("[ContainerReplacementTransformer] Transforming ChunkMap");
        ClassNode cn = readClassNode(classBytes);
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                count += replaceNewInstantiation(mn,
                    "it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap",
                    ECA + "EcaInt2ObjectOpenHashMap");
            }
        }
        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + count + " containers in ChunkMap");
        return writeClassNode(cn);
    }

    private byte[] replacePersistentEntitySectionManagerContainers(byte[] classBytes) {
        AgentLogWriter.info("[ContainerReplacementTransformer] Transforming PersistentEntitySectionManager");
        ClassNode cn = readClassNode(classBytes);
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                count += replaceGuavaFactoryCall(mn,
                    "com/google/common/collect/Sets", "newHashSet",
                    ECA + "EcaHashSet");
                count += replaceGuavaFactoryCall(mn,
                    "com/google/common/collect/Queues", "newConcurrentLinkedQueue",
                    ECA + "EcaConcurrentLinkedQueue");
            }
        }
        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + count + " containers in PersistentEntitySectionManager");
        return writeClassNode(cn);
    }

    private byte[] replaceEntitySectionStorageContainers(byte[] classBytes) {
        AgentLogWriter.info("[ContainerReplacementTransformer] Transforming EntitySectionStorage");
        ClassNode cn = readClassNode(classBytes);
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                count += replaceNewInstantiation(mn,
                    "it/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap",
                    ECA + "EcaLong2ObjectOpenHashMap");
                count += replaceNewInstantiation(mn,
                    "it/unimi/dsi/fastutil/longs/LongAVLTreeSet",
                    ECA + "EcaLongAVLTreeSet");
            }
        }
        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + count + " containers in EntitySectionStorage");
        return writeClassNode(cn);
    }

    private byte[] replaceServerLevelContainers(byte[] classBytes) {
        AgentLogWriter.info("[ContainerReplacementTransformer] Transforming ServerLevel");
        ClassNode cn = readClassNode(classBytes);
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                count += replaceGuavaFactoryCall(mn,
                    "com/google/common/collect/Lists", "newArrayList",
                    ECA + "EcaArrayList");
                count += replaceNewInstantiation(mn,
                    "it/unimi/dsi/fastutil/objects/ObjectOpenHashSet",
                    ECA + "EcaHashSet");
            }
        }
        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + count + " containers in ServerLevel");
        return writeClassNode(cn);
    }

    // ── 通用替换方法 ──

    /**
     * 替换 NEW + INVOKESPECIAL <init> 模式的容器实例化
     */
    private int replaceNewInstantiation(MethodNode mn, String originalClass, String replacementClass) {
        int count = 0;
        AbstractInsnNode[] insns = mn.instructions.toArray();

        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];

            if (insn.getOpcode() == NEW) {
                TypeInsnNode typeInsn = (TypeInsnNode) insn;

                if (typeInsn.desc.equals(originalClass)) {
                    typeInsn.desc = replacementClass;

                    for (int j = i + 1; j < Math.min(insns.length, i + 10); j++) {
                        if (insns[j].getOpcode() == INVOKESPECIAL) {
                            MethodInsnNode methodInsn = (MethodInsnNode) insns[j];
                            if (methodInsn.owner.equals(originalClass) && methodInsn.name.equals("<init>")) {
                                methodInsn.owner = replacementClass;
                                count++;
                                break;
                            }
                        }
                    }
                }
            }
        }

        return count;
    }

    /**
     * 替换 Guava 工厂方法调用为 ECA 容器实例化
     * 将 INVOKESTATIC guavaOwner.factoryMethod() 替换为 NEW + DUP + INVOKESPECIAL ecaReplacement.<init>()V
     */
    private int replaceGuavaFactoryCall(MethodNode mn, String guavaOwner, String factoryMethod, String ecaReplacement) {
        int count = 0;
        AbstractInsnNode[] insns = mn.instructions.toArray();

        for (AbstractInsnNode insn : insns) {
            if (insn.getOpcode() != INVOKESTATIC) continue;
            MethodInsnNode methodInsn = (MethodInsnNode) insn;

            if (!methodInsn.owner.equals(guavaOwner) || !methodInsn.name.equals(factoryMethod)) continue;
            if (!methodInsn.desc.startsWith("()")) continue;

            InsnList replacement = new InsnList();
            replacement.add(new TypeInsnNode(NEW, ecaReplacement));
            replacement.add(new InsnNode(DUP));
            replacement.add(new MethodInsnNode(INVOKESPECIAL, ecaReplacement, "<init>", "()V", false));

            mn.instructions.insert(insn, replacement);
            mn.instructions.remove(insn);
            count++;
        }

        return count;
    }

    // ── 工具方法 ──

    private ClassNode readClassNode(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        return cn;
    }

    private byte[] writeClassNode(ClassNode cn) {
        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
