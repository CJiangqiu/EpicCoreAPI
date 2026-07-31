package net.eca.util;

import net.eca.config.EcaConfiguration;
import net.eca.util.reflect.ObfuscationMapping;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.CodeSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

// 独立逆向实体生命周期布尔门控，避免死亡语义依赖血量分析器
public final class DeathGateAnalyzer {

    private static final String BOOLEAN_METHOD_DESCRIPTOR = "()Z";
    private static final MethodTarget IS_DEAD_OR_DYING = new MethodTarget(
            "LivingEntity.isDeadOrDying", "isDeadOrDying", true);
    private static final MethodTarget IS_ALIVE = new MethodTarget(
            "LivingEntity.isAlive", "isAlive", false);
    private static final DeathGate NO_GATE = new DeathGate(null, null, null, false);
    private static final Map<Class<?>, DeathGate> CACHE = new ConcurrentHashMap<>();

    private DeathGateAnalyzer() {}

    // 将实体自身的编码死亡门控切换到死亡态，无法确认时保持原值
    public static boolean unlock(LivingEntity entity) {
        if (entity == null || !EcaConfiguration.getAttackEnableRadicalLogicSafely()) return false;
        DeathGate gate = analyze(entity.getClass());
        if (gate == null) return false;
        try {
            Object snapshot = gate.field().get(entity);
            Object encoded = gate.encoder().invoke(null, gate.deathValue());
            gate.field().set(entity, encoded);
            boolean decoded = Boolean.TRUE.equals(gate.decoder().invoke(null, gate.field().get(entity)));
            if (decoded == gate.deathValue()) {
                EcaLogger.info("[DeathGate] unlocked entity={} field={} deathValue={}",
                        entity.getClass().getName(), gate.field().getName(), gate.deathValue());
                return true;
            }
            gate.field().set(entity, snapshot);
            return false;
        } catch (Throwable throwable) {
            if (throwable instanceof VirtualMachineError error) throw error;
            EcaLogger.info("[DeathGate] unlock failed entity={} msg={}",
                    entity.getClass().getName(), throwable.getMessage());
            return false;
        }
    }

    private static DeathGate analyze(Class<?> entityClass) {
        DeathGate cached = CACHE.get(entityClass);
        if (cached != null) return cached == NO_GATE ? null : cached;
        DeathGate gate = detect(entityClass);
        CACHE.put(entityClass, gate == null ? NO_GATE : gate);
        return gate;
    }

    private static DeathGate detect(Class<?> entityClass) {
        DeathGate gate = detectMethod(entityClass, IS_DEAD_OR_DYING);
        return gate != null ? gate : detectMethod(entityClass, IS_ALIVE);
    }

    private static DeathGate detectMethod(Class<?> entityClass, MethodTarget method) {
        ClassAndMethod target = findMethodOwner(entityClass, method);
        if (target == null) return null;
        try {
            ClassNode classNode = readClassNode(target.owner());
            MethodNode methodNode = findMethod(classNode, target.name());
            if (methodNode == null) return null;
            for (AbstractInsnNode instruction : methodNode.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
                Type returnType = Type.getReturnType(call.desc);
                Type[] argumentTypes = Type.getArgumentTypes(call.desc);
                if (returnType.getSort() != Type.BOOLEAN || argumentTypes.length != 1
                        || argumentTypes[0].getSort() != Type.OBJECT) continue;
                FieldInsnNode fieldRead = findFieldInput(call);
                if (fieldRead == null || Type.getType(fieldRead.desc).getSort() != Type.OBJECT) continue;
                Class<?> fieldOwner = loadClass(fieldRead.owner);
                Class<?> codecOwner = loadClass(call.owner);
                Class<?> encodedType = typeToClass(argumentTypes[0]);
                if (fieldOwner == null || codecOwner == null || encodedType == null) continue;
                Field field = findField(fieldOwner, fieldRead.name);
                Method decoder = findDecoder(codecOwner, call.name, encodedType);
                Method encoder = findEncoder(codecOwner, encodedType);
                if (field == null || decoder == null || encoder == null) continue;
                field.setAccessible(true);
                return new DeathGate(field, encoder, decoder, method.deathValue());
            }
        } catch (Throwable throwable) {
            if (throwable instanceof VirtualMachineError error) throw error;
            EcaLogger.info("[DeathGate] analysis failed entity={} method={} msg={}",
                    entityClass.getName(), target.name(), throwable.getMessage());
        }
        return null;
    }

    // 仅跨越不改变对象来源的指令，防止把同一方法中的其他字段误认成解码器输入
    private static FieldInsnNode findFieldInput(MethodInsnNode call) {
        AbstractInsnNode current = call.getPrevious();
        int remaining = 8;
        while (current != null && remaining-- > 0) {
            int opcode = current.getOpcode();
            if (opcode == Opcodes.GETFIELD) {
                FieldInsnNode field = (FieldInsnNode) current;
                AbstractInsnNode receiver = previousMeaningful(field.getPrevious());
                return receiver != null && receiver.getOpcode() == Opcodes.ALOAD
                        && ((VarInsnNode) receiver).var == 0 ? field : null;
            }
            if (opcode == Opcodes.ALOAD || opcode == Opcodes.DUP || opcode == Opcodes.DUP_X1
                    || opcode == Opcodes.DUP2 || opcode == Opcodes.CHECKCAST || opcode == -1) {
                current = current.getPrevious();
                continue;
            }
            return null;
        }
        return null;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode current) {
        while (current != null && current.getOpcode() == -1) current = current.getPrevious();
        return current;
    }

    private static ClassAndMethod findMethodOwner(Class<?> entityClass, MethodTarget method) {
        String mappedName = ObfuscationMapping.getMethodMapping(method.mappingKey());
        for (Class<?> current = entityClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (mappedName != null && definesMethod(current, mappedName)) {
                return new ClassAndMethod(current, mappedName);
            }
            if (definesMethod(current, method.deobfuscatedName())) {
                return new ClassAndMethod(current, method.deobfuscatedName());
            }
        }
        return null;
    }

    private static boolean definesMethod(Class<?> owner, String name) {
        ClassNode classNode = readClassNode(owner);
        return findMethod(classNode, name) != null;
    }

    private static MethodNode findMethod(ClassNode classNode, String name) {
        if (classNode == null) return null;
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(name) && method.desc.equals(BOOLEAN_METHOD_DESCRIPTOR)) return method;
        }
        return null;
    }

    private static ClassNode readClassNode(Class<?> owner) {
        byte[] bytes = readClassBytes(owner);
        if (bytes == null) return null;
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(classNode, ClassReader.EXPAND_FRAMES);
        return classNode;
    }

    private static byte[] readClassBytes(Class<?> owner) {
        if (owner == null) return null;
        String path = internalName(owner) + ".class";
        ClassLoader classLoader = owner.getClassLoader();
        if (classLoader == null) classLoader = ClassLoader.getSystemClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(path)) {
            if (input != null) return input.readAllBytes();
        } catch (Throwable throwable) {
            if (throwable instanceof VirtualMachineError error) throw error;
        }
        try {
            CodeSource codeSource = owner.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) return null;
            try (JarFile jar = new JarFile(codeSource.getLocation().getPath())) {
                JarEntry entry = jar.getJarEntry(path);
                if (entry == null) return null;
                try (InputStream input = jar.getInputStream(entry)) {
                    return input.readAllBytes();
                }
            }
        } catch (Throwable throwable) {
            if (throwable instanceof VirtualMachineError error) throw error;
            return null;
        }
    }

    private static String internalName(Class<?> owner) {
        String name = owner.getName().replace('.', '/');
        int hiddenSuffix = name.indexOf("/0x");
        if (hiddenSuffix < 0) return name;
        String stripped = name.substring(0, hiddenSuffix);
        if (stripped.indexOf('/') < 0 && owner.getSuperclass() != null) {
            String superName = owner.getSuperclass().getName().replace('.', '/');
            int packageEnd = superName.lastIndexOf('/');
            if (packageEnd > 0) stripped = superName.substring(0, packageEnd + 1) + stripped;
        }
        return stripped;
    }

    private static Class<?> loadClass(String internalName) {
        String className = internalName.replace('/', '.');
        for (ClassLoader classLoader : new ClassLoader[]{
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                DeathGateAnalyzer.class.getClassLoader()
        }) {
            if (classLoader == null) continue;
            try {
                return Class.forName(className, false, classLoader);
            } catch (Throwable throwable) {
                if (throwable instanceof VirtualMachineError error) throw error;
            }
        }
        try {
            return Class.forName(className);
        } catch (Throwable throwable) {
            if (throwable instanceof VirtualMachineError error) throw error;
            return null;
        }
    }

    private static Field findField(Class<?> owner, String name) {
        for (Class<?> current = owner; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method findDecoder(Class<?> owner, String name, Class<?> encodedType) {
        for (Method method : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getName().equals(name)
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0] == encodedType
                    && method.getReturnType() == boolean.class) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Method findEncoder(Class<?> owner, Class<?> encodedType) {
        for (Method method : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == boolean.class && method.getReturnType() == encodedType) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Class<?> typeToClass(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> boolean.class;
            case Type.BYTE -> byte.class;
            case Type.CHAR -> char.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.LONG -> long.class;
            case Type.FLOAT -> float.class;
            case Type.DOUBLE -> double.class;
            default -> loadClass(type.getInternalName());
        };
    }

    private record MethodTarget(String mappingKey, String deobfuscatedName, boolean deathValue) {}

    private record ClassAndMethod(Class<?> owner, String name) {}

    private record DeathGate(Field field, Method encoder, Method decoder, boolean deathValue) {}
}
