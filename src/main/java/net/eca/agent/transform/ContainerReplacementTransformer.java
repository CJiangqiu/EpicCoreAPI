package net.eca.agent.transform;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.List;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/**
 * 容器替换Transformer
 * 将MC原版的底层容器替换为ECA自定义容器
 *
 * 替换策略：
 * 1. EntityTickList: Int2ObjectLinkedOpenHashMap → EcaInt2ObjectLinkedOpenHashMap
 * 2. EntityLookup: Int2ObjectLinkedOpenHashMap → EcaInt2ObjectOpenHashMap (byId)
 *                  HashMap → EcaHashMap (byUuid)
 * 3. ClassInstanceMultiMap: HashMap → EcaHashMap (byClass)
 *                           ArrayList → EcaArrayList (allInstances)
 * 4. ChunkMap: Int2ObjectOpenHashMap → EcaInt2ObjectOpenHashMap (entityMap)
 */
public class ContainerReplacementTransformer implements ITransformModule {

    // 标签字段名（用于验证转换是否被拦截）
    private static final String MARK_FIELD_NAME = "__ECA_CONTAINER_MARK__";

    // 第一个被转换的类名（用于验证）
    private static volatile String firstTransformedClass = null;

    /**
     * 获取第一个被转换的类名
     * @return 第一个被转换的类名，如果没有则返回null
     */
    public static String getFirstTransformed() {
        return firstTransformedClass;
    }

    @Override
    public String getName() {
        return "ContainerReplacementTransformer";
    }

    @Override
    public String getMarkFieldName() {
        return MARK_FIELD_NAME;
    }

    @Override
    public String getFirstTransformedClass() {
        return firstTransformedClass;
    }

    /**
     * 为ClassNode注入标签字段（如果是第一个转换的类）
     * @param cn ClassNode
     * @param className 类名
     * @return true如果注入了标签
     */
    private boolean tryInjectMarkField(ClassNode cn, String className) {
        if (firstTransformedClass == null) {
            // 注入标签字段: public static final boolean MARK_FIELD_NAME = true
            FieldNode markField = new FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                MARK_FIELD_NAME,
                "Z",
                null,
                Boolean.TRUE
            );
            cn.fields.add(markField);

            firstTransformedClass = className.replace('/', '.');
            AgentLogWriter.info("[ContainerReplacementTransformer] First transformed class: " + firstTransformedClass + " (mark field injected)");
            return true;
        }
        return false;
    }

    @Override
    public List<String> getTargetClassNames() {
        return List.of(
            "net.minecraft.world.level.entity.EntityTickList",
            "net.minecraft.world.level.entity.EntityLookup",
            "net.minecraft.util.ClassInstanceMultiMap",
            "net.minecraft.server.level.ChunkMap"
        );
    }

    @Override
    public boolean shouldTransform(String className, ClassLoader classLoader) {
        // 只转换这4个MC原版类
        return className.equals("net/minecraft/world/level/entity/EntityTickList") ||
               className.equals("net/minecraft/world/level/entity/EntityLookup") ||
               className.equals("net/minecraft/util/ClassInstanceMultiMap") ||
               className.equals("net/minecraft/server/level/ChunkMap");
    }

    @Override
    public byte[] transform(String className, byte[] classfileBuffer) {
        try {
            // EntityTickList: 替换 active, passive
            if (className.equals("net/minecraft/world/level/entity/EntityTickList")) {
                return replaceEntityTickListContainers(classfileBuffer);
            }

            // EntityLookup: 替换 byId, byUuid
            if (className.equals("net/minecraft/world/level/entity/EntityLookup")) {
                return replaceEntityLookupContainers(classfileBuffer);
            }

            // ClassInstanceMultiMap: 替换 byClass, allInstances
            if (className.equals("net/minecraft/util/ClassInstanceMultiMap")) {
                return replaceClassInstanceMultiMapContainers(classfileBuffer);
            }

            // ChunkMap: 替换 entityMap
            if (className.equals("net/minecraft/server/level/ChunkMap")) {
                return replaceChunkMapContainers(classfileBuffer);
            }
        } catch (Exception e) {
            AgentLogWriter.error("[ContainerReplacementTransformer] Failed to transform " + className, e);
        }

        return null;
    }

    /**
     * 替换EntityTickList的容器
     * active, passive: Int2ObjectLinkedOpenHashMap → EcaInt2ObjectLinkedOpenHashMap
     */
    private byte[] replaceEntityTickListContainers(byte[] classBytes) {
        AgentLogWriter.info("[ContainerReplacementTransformer] Transforming EntityTickList");

        ClassNode cn = new ClassNode();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(cn, 0);

        int replacedCount = 0;

        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                replacedCount += replaceContainerInstantiation(
                    mn,
                    "it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap",
                    "net/eca/agent/container/EcaInt2ObjectLinkedOpenHashMap"
                );
            }
        }

        // 尝试注入标签字段
        tryInjectMarkField(cn, "net/minecraft/world/level/entity/EntityTickList");

        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + replacedCount + " containers in EntityTickList");

        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * 替换EntityLookup的容器
     * byId: Int2ObjectLinkedOpenHashMap → EcaInt2ObjectLinkedOpenHashMap
     * byUuid: HashMap → EcaHashMap
     */
    private byte[] replaceEntityLookupContainers(byte[] classBytes) {
        AgentLogWriter.info("[ContainerReplacementTransformer] Transforming EntityLookup");

        ClassNode cn = new ClassNode();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(cn, 0);

        int replacedCount = 0;

        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                // 替换 Int2ObjectLinkedOpenHashMap
                replacedCount += replaceContainerInstantiation(
                    mn,
                    "it/unimi/dsi/fastutil/ints/Int2ObjectLinkedOpenHashMap",
                    "net/eca/agent/container/EcaInt2ObjectLinkedOpenHashMap"
                );

                // 替换 HashMap（通过Guava的Maps.newHashMap创建的）
                replacedCount += replaceGuavaHashMap(mn);
            }
        }

        // 尝试注入标签字段
        tryInjectMarkField(cn, "net/minecraft/world/level/entity/EntityLookup");

        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + replacedCount + " containers in EntityLookup");

        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * 替换ClassInstanceMultiMap的容器
     * byClass: HashMap → EcaHashMap
     * allInstances: ArrayList → EcaArrayList
     */
    private byte[] replaceClassInstanceMultiMapContainers(byte[] classBytes) {
        AgentLogWriter.info("[ContainerReplacementTransformer] Transforming ClassInstanceMultiMap");

        ClassNode cn = new ClassNode();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(cn, 0);

        int replacedCount = 0;

        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                // 替换 HashMap
                replacedCount += replaceGuavaHashMap(mn);

                // 替换 ArrayList
                replacedCount += replaceGuavaArrayList(mn);
            }
        }

        // 尝试注入标签字段
        tryInjectMarkField(cn, "net/minecraft/util/ClassInstanceMultiMap");

        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + replacedCount + " containers in ClassInstanceMultiMap");

        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * 替换ChunkMap的容器
     * entityMap: Int2ObjectOpenHashMap → EcaInt2ObjectOpenHashMap
     */
    private byte[] replaceChunkMapContainers(byte[] classBytes) {
        AgentLogWriter.info("[ContainerReplacementTransformer] Transforming ChunkMap");

        ClassNode cn = new ClassNode();
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(cn, 0);

        int replacedCount = 0;

        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
                replacedCount += replaceContainerInstantiation(
                    mn,
                    "it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap",
                    "net/eca/agent/container/EcaInt2ObjectOpenHashMap"
                );
            }
        }

        // 尝试注入标签字段
        tryInjectMarkField(cn, "net/minecraft/server/level/ChunkMap");

        AgentLogWriter.info("[ContainerReplacementTransformer] Replaced " + replacedCount + " containers in ChunkMap");

        ClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * 替换容器实例化指令
     * @param mn 方法节点
     * @param originalClass 原始类名（内部格式，如 "java/util/HashMap"）
     * @param replacementClass 替换类名（内部格式）
     * @return 替换的数量
     */
    private int replaceContainerInstantiation(MethodNode mn, String originalClass, String replacementClass) {
        int count = 0;
        AbstractInsnNode[] insns = mn.instructions.toArray();

        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];

            // 查找 NEW 指令
            if (insn.getOpcode() == NEW) {
                TypeInsnNode typeInsn = (TypeInsnNode) insn;

                if (typeInsn.desc.equals(originalClass)) {
                    // 替换 NEW 指令的类型
                    typeInsn.desc = replacementClass;

                    // 查找对应的 INVOKESPECIAL <init> 指令并替换
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
     * 替换通过Guava Maps.newHashMap()创建的HashMap
     * 将 Maps.newHashMap() 替换为 new EcaHashMap()
     */
    private int replaceGuavaHashMap(MethodNode mn) {
        int count = 0;
        AbstractInsnNode[] insns = mn.instructions.toArray();

        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];

            // 查找 INVOKESTATIC com/google/common/collect/Maps.newHashMap
            if (insn.getOpcode() == INVOKESTATIC) {
                MethodInsnNode methodInsn = (MethodInsnNode) insn;

                if (methodInsn.owner.equals("com/google/common/collect/Maps") &&
                    methodInsn.name.equals("newHashMap")) {

                    // 替换为 new EcaHashMap()
                    InsnList replacement = new InsnList();
                    replacement.add(new TypeInsnNode(NEW, "net/eca/agent/container/EcaHashMap"));
                    replacement.add(new InsnNode(DUP));
                    replacement.add(new MethodInsnNode(INVOKESPECIAL,
                        "net/eca/agent/container/EcaHashMap",
                        "<init>",
                        "()V",
                        false));

                    mn.instructions.insert(insn, replacement);
                    mn.instructions.remove(insn);
                    count++;
                }
            }
        }

        return count;
    }

    /**
     * 替换通过Guava Lists.newArrayList()创建的ArrayList
     * 将 Lists.newArrayList() 替换为 new EcaArrayList()
     */
    private int replaceGuavaArrayList(MethodNode mn) {
        int count = 0;
        AbstractInsnNode[] insns = mn.instructions.toArray();

        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];

            // 查找 INVOKESTATIC com/google/common/collect/Lists.newArrayList
            if (insn.getOpcode() == INVOKESTATIC) {
                MethodInsnNode methodInsn = (MethodInsnNode) insn;

                if (methodInsn.owner.equals("com/google/common/collect/Lists") &&
                    methodInsn.name.equals("newArrayList")) {

                    // 替换为 new EcaArrayList()
                    InsnList replacement = new InsnList();
                    replacement.add(new TypeInsnNode(NEW, "net/eca/agent/container/EcaArrayList"));
                    replacement.add(new InsnNode(DUP));
                    replacement.add(new MethodInsnNode(INVOKESPECIAL,
                        "net/eca/agent/container/EcaArrayList",
                        "<init>",
                        "()V",
                        false));

                    mn.instructions.insert(insn, replacement);
                    mn.instructions.remove(insn);
                    count++;
                }
            }
        }

        return count;
    }
}
