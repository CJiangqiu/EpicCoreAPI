package net.eca.pro.ingot;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

final class TargetContractIngot implements ProIngot {
    private static final String ECA_PREFIX = "net/eca/";
    private static final String SPAWN_HOOK = "net/eca/util/spawn_ban/SpawnBanHook";
    private static final String SPAWN_CHECK = "shouldBlockSpawn";
    private static final String SPAWN_CHECK_DESC = "(Ljava/lang/Object;)Z";
    private static final Map<String, List<GuardedMethod>> GUARDED_METHODS = Map.of(
            "net/minecraft/world/level/entity/EntityLookup", List.of(
                    method("(Lnet/minecraft/world/level/entity/EntityAccess;)V", "add", "m_156814_")),
            "net/minecraft/world/level/entity/EntitySection", List.of(
                    method("(Lnet/minecraft/world/level/entity/EntityAccess;)V", "add", "m_188346_")),
            "net/minecraft/world/level/entity/EntityTickList", List.of(
                    method("(Lnet/minecraft/world/entity/Entity;)V", "add", "m_156908_")),
            "net/minecraft/util/ClassInstanceMultiMap", List.of(
                    method("(Ljava/lang/Object;)Z", "add")),
            "net/minecraft/world/level/entity/PersistentEntitySectionManager", List.of(
                    method("(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", "addEntity", "m_157538_"),
                    method("(Lnet/minecraft/world/level/entity/EntityAccess;)Z", "addNewEntity", "m_157533_"),
                    method("(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", "addEntityWithoutEvent"),
                    method("(Lnet/minecraft/world/level/entity/EntityAccess;)Z", "addNewEntityWithoutEvent")),
            "net/minecraft/world/level/entity/TransientEntitySectionManager", List.of(
                    method("(Lnet/minecraft/world/level/entity/EntityAccess;)V", "addEntity", "m_157653_")),
            "net/minecraft/server/level/ChunkMap", List.of(
                    method("(Lnet/minecraft/world/entity/Entity;)V", "addEntity", "m_140199_")),
            "net/minecraft/server/level/ServerLevel", List.of(
                    method("(Lnet/minecraft/world/entity/Entity;)Z", "addEntity", "m_8872_"),
                    method("(Lnet/minecraft/world/entity/Entity;)Z", "addFreshEntity", "m_7967_"),
                    method("(Lnet/minecraft/world/entity/Entity;)Z", "addWithUUID", "m_8847_"),
                    method("(Lnet/minecraft/world/entity/Entity;)V", "addDuringTeleport", "m_143334_"))
    );
    private final String name;
    private final Set<String> targets;

    TargetContractIngot(String name, String target) {
        this.name = name;
        this.targets = Set.of(target);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Set<String> targets() {
        return targets;
    }

    @Override
    public byte[] transform(String internalName, byte[] classBytes) {
        List<GuardedMethod> guardedMethods = GUARDED_METHODS.get(internalName);
        if (guardedMethods == null || guardedMethods.isEmpty()) return classBytes;
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new SafeClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        GuardInjector injector = new GuardInjector(writer, guardedMethods);
        reader.accept(injector, ClassReader.EXPAND_FRAMES);
        return injector.changed ? writer.toByteArray() : classBytes;
    }

    @Override
    public boolean verify(String internalName, byte[] classBytes) {
        List<GuardedMethod> guardedMethods = GUARDED_METHODS.getOrDefault(internalName, List.of());
        EcaCallScanner scanner = new EcaCallScanner(guardedMethods);
        new ClassReader(classBytes).accept(scanner, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!scanner.found) return false;
        if (guardedMethods.isEmpty()) return true;
        return scanner.guardedMethods.containsAll(guardedMethods);
    }

    private static GuardedMethod method(String descriptor, String... names) {
        return new GuardedMethod(Set.of(names), descriptor);
    }

    private static final class EcaCallScanner extends ClassVisitor {
        private final List<GuardedMethod> expectedMethods;
        private boolean found;
        private final Set<GuardedMethod> guardedMethods = new HashSet<>();

        private EcaCallScanner(List<GuardedMethod> expectedMethods) {
            super(Opcodes.ASM9);
            this.expectedMethods = expectedMethods;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            GuardedMethod guardedMethod = findGuardedMethod(name, descriptor);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName,
                                            String methodDescriptor, boolean isInterface) {
                    if (owner.startsWith(ECA_PREFIX)) found = true;
                    if (guardedMethod != null && opcode == Opcodes.INVOKESTATIC
                            && SPAWN_HOOK.equals(owner) && SPAWN_CHECK.equals(methodName)
                            && SPAWN_CHECK_DESC.equals(methodDescriptor)) {
                        guardedMethods.add(guardedMethod);
                    }
                }
            };
        }

        private GuardedMethod findGuardedMethod(String methodName, String descriptor) {
            for (GuardedMethod method : expectedMethods) {
                if (method.matches(methodName, descriptor)) return method;
            }
            return null;
        }
    }

    private static final class GuardInjector extends ClassVisitor {
        private final List<GuardedMethod> guardedMethods;
        private boolean changed;

        private GuardInjector(ClassVisitor visitor, List<GuardedMethod> guardedMethods) {
            super(Opcodes.ASM9, visitor);
            this.guardedMethods = guardedMethods;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
            GuardedMethod guardedMethod = findGuardedMethod(name, descriptor);
            if (guardedMethod == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return visitor;
            }
            changed = true;
            return new MethodVisitor(Opcodes.ASM9, visitor) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    Label passthrough = new Label();
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, SPAWN_HOOK,
                            SPAWN_CHECK, SPAWN_CHECK_DESC, false);
                    mv.visitJumpInsn(Opcodes.IFEQ, passthrough);
                    Type returnType = Type.getReturnType(descriptor);
                    if (returnType.getSort() == Type.VOID) {
                        mv.visitInsn(Opcodes.RETURN);
                    } else {
                        mv.visitInsn(Opcodes.ICONST_0);
                        mv.visitInsn(Opcodes.IRETURN);
                    }
                    mv.visitLabel(passthrough);
                }
            };
        }

        private GuardedMethod findGuardedMethod(String methodName, String descriptor) {
            for (GuardedMethod method : guardedMethods) {
                if (method.matches(methodName, descriptor)) return method;
            }
            return null;
        }
    }

    private record GuardedMethod(Set<String> names, String descriptor) {
        private boolean matches(String methodName, String methodDescriptor) {
            return names.contains(methodName) && descriptor.equals(methodDescriptor);
        }
    }
}
