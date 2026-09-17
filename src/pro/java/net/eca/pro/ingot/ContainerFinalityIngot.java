package net.eca.pro.ingot;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.List;
import java.util.Set;

final class ContainerFinalityIngot implements ProIngot {
    private static final String HOOK_OWNER = "net/eca/pro/ingot/ProContainerHooks";
    private static final String NORMALIZE_FIELD = "normalizeFieldValue";
    private static final String NORMALIZE_FIELD_DESC =
            "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;";
    private static final String NORMALIZE_GRAPH = "normalizeServerGraph";
    private static final String NORMALIZE_GRAPH_DESC = "(Ljava/lang/Object;)V";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final Set<String> TARGETS = Set.of(
            "net/minecraft/world/level/entity/EntityTickList",
            "net/minecraft/world/level/entity/EntityLookup",
            "net/minecraft/util/ClassInstanceMultiMap",
            "net/minecraft/server/level/ChunkMap",
            "net/minecraft/world/level/entity/PersistentEntitySectionManager",
            "net/minecraft/world/level/entity/EntitySectionStorage",
            SERVER_LEVEL
    );
    private static final List<FieldRule> FIELD_RULES = List.of(
            rule("net/minecraft/world/level/entity/EntityTickList", "int_linked",
                    "active", "f_156903_", "passive", "f_156904_"),
            rule("net/minecraft/world/level/entity/EntityLookup", "int_linked",
                    "byId", "f_156807_"),
            rule("net/minecraft/world/level/entity/EntityLookup", "map",
                    "byUuid", "f_156808_"),
            rule("net/minecraft/util/ClassInstanceMultiMap", "map",
                    "byClass", "f_13527_"),
            rule("net/minecraft/util/ClassInstanceMultiMap", "list",
                    "allInstances", "f_13529_"),
            rule("net/minecraft/server/level/ChunkMap", "int_open",
                    "entityMap", "f_140150_"),
            rule("net/minecraft/server/level/ChunkMap", "long_set",
                    "entitiesInLevel", "f_140132_"),
            rule("net/minecraft/world/level/entity/PersistentEntitySectionManager", "set",
                    "knownUuids", "f_157491_"),
            rule("net/minecraft/world/level/entity/PersistentEntitySectionManager", "queue",
                    "loadingInbox", "f_157500_"),
            rule("net/minecraft/world/level/entity/PersistentEntitySectionManager", "long_map",
                    "chunkVisibility", "f_157497_", "chunkLoadStatuses", "f_157498_"),
            rule("net/minecraft/world/level/entity/PersistentEntitySectionManager", "long_set",
                    "chunksToUnload", "f_157499_"),
            rule("net/minecraft/world/level/entity/EntitySectionStorage", "long_map",
                    "sections", "f_156852_"),
            rule("net/minecraft/world/level/entity/EntitySectionStorage", "long_function",
                    "intialSectionVisibility", "f_156851_"),
            rule(SERVER_LEVEL, "list", "players", "f_8546_"),
            rule(SERVER_LEVEL, "set", "navigatingMobs", "f_143246_")
    );

    @Override
    public String name() {
        return "container_finality";
    }

    @Override
    public Set<String> targets() {
        return TARGETS;
    }

    @Override
    public byte[] transform(String internalName, byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        boolean changed = normalizeAssignments(node);
        if (SERVER_LEVEL.equals(internalName)) changed |= injectGraphNormalization(node);
        if (!changed) return classBytes;
        ClassWriter writer = new SafeClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    @Override
    public boolean verify(String internalName, byte[] classBytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && findRule(field) != null
                        && !hasNormalizerBefore(field)) {
                    return false;
                }
            }
        }
        return !SERVER_LEVEL.equals(internalName) || hasGraphNormalization(node);
    }

    private static boolean normalizeAssignments(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTFIELD) continue;
                FieldRule rule = findRule(field);
                if (rule == null || hasNormalizerBefore(field)) continue;
                Type fieldType = Type.getType(field.desc);
                if (fieldType.getSort() != Type.OBJECT && fieldType.getSort() != Type.ARRAY) continue;
                InsnList hook = new InsnList();
                hook.add(new LdcInsnNode(rule.kind()));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER,
                        NORMALIZE_FIELD, NORMALIZE_FIELD_DESC, false));
                hook.add(new TypeInsnNode(Opcodes.CHECKCAST, fieldType.getInternalName()));
                method.instructions.insertBefore(field, hook);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean injectGraphNormalization(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!("tick".equals(method.name) || "m_8793_".equals(method.name))
                    || !"(Ljava/util/function/BooleanSupplier;)V".equals(method.desc)) continue;
            if (hasCall(method, NORMALIZE_GRAPH)) return false;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER,
                    NORMALIZE_GRAPH, NORMALIZE_GRAPH_DESC, false));
            method.instructions.insert(hook);
            return true;
        }
        return false;
    }

    private static boolean hasGraphNormalization(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (("tick".equals(method.name) || "m_8793_".equals(method.name))
                    && "(Ljava/util/function/BooleanSupplier;)V".equals(method.desc)) {
                return hasCall(method, NORMALIZE_GRAPH);
            }
        }
        return false;
    }

    private static boolean hasCall(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && HOOK_OWNER.equals(call.owner) && name.equals(call.name)) return true;
        }
        return false;
    }

    private static boolean hasNormalizerBefore(FieldInsnNode field) {
        AbstractInsnNode current = field.getPrevious();
        for (int remaining = 4; current != null && remaining-- > 0; current = current.getPrevious()) {
            if (current instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && HOOK_OWNER.equals(call.owner) && NORMALIZE_FIELD.equals(call.name)) return true;
        }
        return false;
    }

    private static FieldRule findRule(FieldInsnNode field) {
        for (FieldRule rule : FIELD_RULES) {
            if (rule.owner().equals(field.owner) && rule.names().contains(field.name)) return rule;
        }
        return null;
    }

    private static FieldRule rule(String owner, String kind, String... names) {
        return new FieldRule(owner, Set.of(names), kind);
    }

    private record FieldRule(String owner, Set<String> names, String kind) {
    }
}
