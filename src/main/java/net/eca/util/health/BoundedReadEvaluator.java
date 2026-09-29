package net.eca.util.health;

import net.eca.util.EcaLogger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.function.Function;

/*
 * 有界只读字节码求值器：为查找和数值循环提供运行期求值，避免符号展开循环时表达式膨胀。
 * 使用分析器提供的字节码与类解析入口解释受支持的指令，不直接调用任意应用方法，
 * 并通过指令步数、递归深度和时间预算限制工作量；无法安全解释时返回未解析结果。
 * 读取过程中发现的对象交给 HealthMutationContext，供后续数值反演复用实际读取路径。
 */
final class BoundedReadEvaluator {
    private final Function<Class<?>, ClassNode> bytecode;
    private final Function<String, Class<?>> classes;

    BoundedReadEvaluator(Function<Class<?>, ClassNode> bytecode, Function<String, Class<?>> classes) {
        this.bytecode = bytecode;
        this.classes = classes;
    }

    boolean isLoop(Class<?> owner, String name, String desc) {
        if (owner == null || owner.getName().startsWith("java.")) return false;
        MethodNode method = method(owner, name, desc);
        if (method == null || method.instructions.size() > 2048
                || (method.access & (Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE)) != 0) return false;
        boolean loop = false;
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC
                    || opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE
                    || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) return false;
            if (instruction instanceof JumpInsnNode jump
                    && method.instructions.indexOf(jump.label) <= method.instructions.indexOf(jump)) loop = true;
        }
        return loop && readable(owner, name, desc, new HashSet<>(), new int[]{2048}, 0);
    }

    private boolean readable(Class<?> owner, String name, String desc, Set<String> visited, int[] remaining, int depth) {
        if (owner == null || depth > 12) return false;
        String key = owner.getName() + "#" + name + desc;
        if (!visited.add(key)) return true;
        if (owner.getName().startsWith("java.")) {
            return name.equals("equals") && desc.equals("(Ljava/lang/Object;)Z")
                    || Number.class.isAssignableFrom(owner) && (name.equals("intValue") && desc.equals("()I")
                        || name.equals("doubleValue") && desc.equals("()D") || name.equals("floatValue") && desc.equals("()F"))
                    || owner == Math.class && name.equals("pow") && desc.equals("(DD)D")
                    || Throwable.class.isAssignableFrom(owner) && name.equals("<init>");
        }
        MethodNode method = method(owner, name, desc);
        if (method == null || (method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (--remaining[0] < 0) return false;
            int op = instruction.getOpcode();
            if (instruction instanceof MethodInsnNode call) {
                if (!readable(classes.apply(call.owner), call.name, call.desc, visited, remaining, depth + 1)) return false;
            } else if (op == Opcodes.NEW) {
                Class<?> type = classes.apply(((TypeInsnNode) instruction).desc);
                if (type == null || !Throwable.class.isAssignableFrom(type)) return false;
            } else if (!(op < 0 || op == Opcodes.NOP || op == Opcodes.ACONST_NULL
                    || op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5
                    || op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1
                    || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH || op == Opcodes.LDC
                    || op >= Opcodes.ILOAD && op <= Opcodes.ALOAD || op >= Opcodes.ISTORE && op <= Opcodes.ASTORE
                    || op == Opcodes.IINC || op == Opcodes.GETFIELD || op == Opcodes.CHECKCAST
                    || op == Opcodes.POP || op == Opcodes.DUP || op == Opcodes.I2D || op == Opcodes.D2F
                    || op == Opcodes.IADD || op == Opcodes.ISUB || op == Opcodes.IMUL
                    || op == Opcodes.DADD || op == Opcodes.DSUB || op == Opcodes.DMUL || op == Opcodes.DDIV
                    || op >= Opcodes.IFEQ && op <= Opcodes.GOTO || op == Opcodes.IFNULL || op == Opcodes.IFNONNULL
                    || op >= Opcodes.IRETURN && op <= Opcodes.RETURN || op == Opcodes.ATHROW
                    || op == Opcodes.INVOKEDYNAMIC)) return false;
        }
        return true;
    }

    Object evaluate(Class<?> owner, String name, String desc, Object receiver, Object[] arguments) {
        Budget budget = new Budget();
        try {
            Object result = run(owner, name, desc, receiver, arguments, budget, 0);
            if (budget.failed) return null;
            HealthMutationContext.recordReadObjects(receiver, budget.readObjects);
            return result;
        } catch (ReflectiveOperationException | SecurityException exception) {
            EcaLogger.info("[HealthRead] field lookup failed: {}", exception.toString());
            return null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private MethodNode method(Class<?> owner, String name, String desc) {
        for (Class<?> current = owner; current != null && current != Object.class; current = current.getSuperclass()) {
            ClassNode node = bytecode.apply(current);
            if (node == null) continue;
            for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static final class Budget {
        int remaining = 16384;
        final long deadline = System.nanoTime() + 10_000_000L;
        boolean failed;
        final Set<Object> readObjects = Collections.newSetFromMap(new IdentityHashMap<>());
        Object fail() { failed = true; return null; }
    }

    private Object run(Class<?> owner, String name, String desc, Object receiver, Object[] arguments,
                       Budget budget, int depth) throws ReflectiveOperationException {
        if (budget.failed || depth > 12 || System.nanoTime() > budget.deadline) return budget.fail();
        // Only final JDK value types may execute equality or unboxing directly.
        if (receiver != null && name.equals("equals") && desc.equals("(Ljava/lang/Object;)Z")) {
            if (receiver instanceof UUID || receiver instanceof String || receiver instanceof Integer
                    || receiver instanceof Long || receiver instanceof Boolean) return receiver.equals(arguments[0]) ? 1 : 0;
            return budget.fail();
        }
        if (receiver instanceof Integer || receiver instanceof Long || receiver instanceof Float || receiver instanceof Double) {
            Number number = (Number) receiver;
            if (name.equals("intValue") && desc.equals("()I")) return number.intValue();
            if (name.equals("doubleValue") && desc.equals("()D")) return number.doubleValue();
            if (name.equals("floatValue") && desc.equals("()F")) return number.floatValue();
        }
        if (owner == Math.class && name.equals("pow") && desc.equals("(DD)D")) {
            return Math.pow(((Number) arguments[0]).doubleValue(), ((Number) arguments[1]).doubleValue());
        }
        if (owner == null || owner.getName().startsWith("java.")) return budget.fail();
        MethodNode method = method(owner, name, desc);
        if (method == null || (method.access & (Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE)) != 0) return budget.fail();
        Object[] locals = new Object[Math.max(1, method.maxLocals)];
        int local = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) locals[local++] = receiver;
        Type[] types = Type.getArgumentTypes(desc);
        for (int i = 0; i < types.length; i++) {
            locals[local] = arguments[i];
            local += types[i].getSize();
        }
        List<Object> stack = new ArrayList<>();
        AbstractInsnNode instruction = method.instructions.getFirst();
        while (instruction != null) {
            if (--budget.remaining < 0 || budget.failed || System.nanoTime() > budget.deadline) return budget.fail();
            int op = instruction.getOpcode();
            if (op < 0 || op == Opcodes.NOP) { instruction = instruction.getNext(); continue; }
            if (instruction instanceof VarInsnNode variable) {
                if (op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) stack.add(locals[variable.var]);
                else if (op >= Opcodes.ISTORE && op <= Opcodes.ASTORE) locals[variable.var] = pop(stack);
                else return budget.fail();
            } else if (instruction instanceof IincInsnNode increment) {
                locals[increment.var] = ((Number) locals[increment.var]).intValue() + increment.incr;
            } else if (instruction instanceof LdcInsnNode constant) {
                if (!(constant.cst instanceof Number) && !(constant.cst instanceof String)) return budget.fail();
                stack.add(constant.cst);
            } else if (instruction instanceof IntInsnNode integer) {
                if (op != Opcodes.BIPUSH && op != Opcodes.SIPUSH) return budget.fail();
                stack.add(integer.operand);
            } else if (instruction instanceof FieldInsnNode field) {
                // Static roots are resolved outside the interpreter; never initialize a class here.
                if (op != Opcodes.GETFIELD) return budget.fail();
                Object object = pop(stack);
                if (object == null) return budget.fail();
                if (budget.readObjects.size() >= 256 && !budget.readObjects.contains(object)) return budget.fail();
                budget.readObjects.add(object);
                Class<?> declaring = classes.apply(field.owner);
                if (declaring == null) return budget.fail();
                Field reflected = declaring.getDeclaredField(field.name);
                reflected.setAccessible(true);
                Object value = reflected.get(object);
                stack.add(value instanceof Boolean flag ? (flag ? 1 : 0) : value);
            } else if (instruction instanceof MethodInsnNode call) {
                if (call.name.startsWith("<")) return budget.fail();
                Type[] parameters = Type.getArgumentTypes(call.desc);
                Object[] values = new Object[parameters.length];
                for (int i = values.length - 1; i >= 0; i--) values[i] = pop(stack);
                Object target = op == Opcodes.INVOKESTATIC ? null : pop(stack);
                if (op != Opcodes.INVOKESTATIC && target == null) return budget.fail();
                Class<?> targetClass = target == null || op == Opcodes.INVOKESPECIAL
                        ? classes.apply(call.owner) : target.getClass();
                Object value = run(targetClass, call.name, call.desc, target, values, budget, depth + 1);
                if (budget.failed) return null;
                if (Type.getReturnType(call.desc).getSort() != Type.VOID) stack.add(value);
            } else if (instruction instanceof JumpInsnNode jump) {
                boolean take;
                if (op == Opcodes.GOTO) take = true;
                else if (op == Opcodes.IFNULL || op == Opcodes.IFNONNULL) {
                    Object value = pop(stack);
                    take = op == Opcodes.IFNULL ? value == null : value != null;
                } else if (op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) {
                    Object right = pop(stack), left = pop(stack);
                    take = op == Opcodes.IF_ACMPEQ ? left == right : left != right;
                } else {
                    int right = op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ICMPLE ? ((Number) pop(stack)).intValue() : 0;
                    int left = ((Number) pop(stack)).intValue();
                    int comparison = op >= Opcodes.IF_ICMPEQ ? op - Opcodes.IF_ICMPEQ : op - Opcodes.IFEQ;
                    take = switch (comparison) {
                        case 0 -> left == right; case 1 -> left != right; case 2 -> left < right;
                        case 3 -> left >= right; case 4 -> left > right; case 5 -> left <= right;
                        default -> false;
                    };
                }
                if (take) { instruction = jump.label; continue; }
            } else if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) stack.add(op - Opcodes.ICONST_0);
            else if (op == Opcodes.ACONST_NULL) stack.add(null);
            else if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) stack.add((double) (op - Opcodes.DCONST_0));
            else if (op == Opcodes.POP) pop(stack);
            else if (op == Opcodes.DUP) stack.add(stack.get(stack.size() - 1));
            else if (op == Opcodes.CHECKCAST) {
                Object value = stack.get(stack.size() - 1);
                Class<?> type = classes.apply(((TypeInsnNode) instruction).desc);
                if (type == null || value != null && !type.isInstance(value)) return budget.fail();
            }
            else if (op == Opcodes.I2D) stack.add(((Number) pop(stack)).doubleValue());
            else if (op == Opcodes.D2F) stack.add(((Number) pop(stack)).floatValue());
            else if (op == Opcodes.IADD || op == Opcodes.ISUB || op == Opcodes.IMUL) {
                int right = ((Number) pop(stack)).intValue(), left = ((Number) pop(stack)).intValue();
                stack.add(op == Opcodes.IADD ? left + right : op == Opcodes.ISUB ? left - right : left * right);
            } else if (op == Opcodes.DADD || op == Opcodes.DSUB || op == Opcodes.DMUL || op == Opcodes.DDIV) {
                double right = ((Number) pop(stack)).doubleValue(), left = ((Number) pop(stack)).doubleValue();
                stack.add(op == Opcodes.DADD ? left + right : op == Opcodes.DSUB ? left - right : op == Opcodes.DMUL ? left * right : left / right);
            } else if (op >= Opcodes.IRETURN && op <= Opcodes.ARETURN) return pop(stack);
            else if (op == Opcodes.RETURN) return null;
            else return budget.fail();
            instruction = instruction.getNext();
        }
        return budget.fail();
    }

    private static Object pop(List<Object> stack) { return stack.remove(stack.size() - 1); }
}
