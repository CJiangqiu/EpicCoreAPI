package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.EcaAgent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 动态分析探针：retransform 时给目标类的每个字段/数组读取(GETFIELD/GETSTATIC/xALOAD)后面
 * 插一条 AccessTrace 记录调用，捕获 (容器对象, 槽位, 读到的值)。只对 setTargets 指定的类生效，
 * 用完 restoreAll 即可撤桩。只改方法体、不改 klass 指针。
 */
public final class AccessProbeTransformer implements ClassFileTransformer {

    private static final String TRACE = "net/eca/coremod/AccessTrace";

    private static volatile boolean registered = false;
    //当前激活探针的目标类(内部名)。空集 = 不插桩任何类
    private static final Set<String> TARGETS = ConcurrentHashMap.newKeySet();
    //全局：所有曾被本探针实际插桩、尚未确认还原的类(内部名)。撤桩以此为准，杜绝残桩
    private static final Set<String> INSTRUMENTED = ConcurrentHashMap.newKeySet();

    private AccessProbeTransformer() {}

    public static void ensureRegistered() {
        if (registered) return;
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            AgentLogWriter.warn("[AccessProbe] No Instrumentation, dynamic analysis unavailable");
            return;
        }
        inst.addTransformer(new AccessProbeTransformer(), true);
        registered = true;
        AgentLogWriter.info("[AccessProbe] Registered as retransform-capable transformer");
    }

    public static void setTargets(Set<String> internalNames) {
        TARGETS.clear();
        TARGETS.addAll(internalNames);
    }

    public static void clearTargets() {
        TARGETS.clear();
    }

    /* 健壮撤桩：清空目标后，对每个曾插桩的类逐个 retransform 还原(各自 try/catch，
       单类失败不影响其余)，杜绝残留的 AccessTrace 调用在 tick 时抛 NoClassDefFoundError。 */
    public static void restoreAll(Instrumentation inst) {
        TARGETS.clear();
        if (inst == null || INSTRUMENTED.isEmpty()) { INSTRUMENTED.clear(); return; }

        java.util.Set<String> pending = new java.util.HashSet<>(INSTRUMENTED);
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            String in = clazz.getName().replace('.', '/');
            if (!pending.contains(in)) continue;
            try {
                if (inst.isModifiableClass(clazz)) {
                    inst.retransformClasses(clazz);   // TARGETS 已空 → 本探针返回 null → 还原为纯 ECA-hook 版本
                }
                INSTRUMENTED.remove(in);
            } catch (Throwable t) {
                AgentLogWriter.error("[AccessProbe] Failed to restore: " + in, t);
            }
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain pd, byte[] buf) {
        if (className == null || !TARGETS.contains(className)) return null;
        try {
            ClassReader cr = new ClassReader(buf);
            ClassWriter cw = new EcaClassTransformer.SafeClassWriter(cr,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cr.accept(new ProbeClassVisitor(cw, className), ClassReader.EXPAND_FRAMES);
            INSTRUMENTED.add(className);
            AgentLogWriter.info("[AccessProbe] Instrumented: " + className);
            return cw.toByteArray();
        } catch (Throwable t) {
            AgentLogWriter.error("[AccessProbe] Failed to instrument: " + className, t);
            return null;
        }
    }

    private static final class ProbeClassVisitor extends ClassVisitor {
        private final String owner;

        ProbeClassVisitor(ClassWriter cw, String owner) {
            super(Opcodes.ASM9, cw);
            this.owner = owner;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            // 跳过构造器/类初始化：未初始化 this 不能传给方法，且 init 顺序敏感；abstract/native mv 已无方法体
            if (mv == null || name.equals("<clinit>") || name.equals("<init>")) return mv;
            return new ProbeMethodVisitor(mv, owner + "#" + name, access, desc);
        }
    }

    private static final class ProbeMethodVisitor extends MethodVisitor {
        private final String where;
        private final int access;
        private final String methodDesc;

        ProbeMethodVisitor(MethodVisitor mv, String where, int access, String methodDesc) {
            super(Opcodes.ASM9, mv);
            this.where = where;
            this.access = access;
            this.methodDesc = methodDesc;
        }

        //方法入口：记录 (site, receiver, 装箱后的实参[])，供通用求解器 seed 分析
        @Override
        public void visitCode() {
            super.visitCode();
            Type[] params = Type.getArgumentTypes(methodDesc);
            boolean instance = (access & Opcodes.ACC_STATIC) == 0;

            super.visitLdcInsn(where);                                    // site
            if (instance) super.visitVarInsn(Opcodes.ALOAD, 0);          // receiver
            else super.visitInsn(Opcodes.ACONST_NULL);
            super.visitLdcInsn(params.length);                            // 构造 Object[args]
            super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");

            int slot = instance ? 1 : 0;
            for (int i = 0; i < params.length; i++) {
                super.visitInsn(Opcodes.DUP);
                super.visitLdcInsn(i);
                loadBoxed(params[i], slot);
                super.visitInsn(Opcodes.AASTORE);
                slot += params[i].getSize();
            }
            super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "enter",
                    "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)V", false);
        }

        //把第 slot 个局部变量(类型 t)加载并装箱为 Object 压栈
        private void loadBoxed(Type t, int slot) {
            switch (t.getSort()) {
                case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                    super.visitVarInsn(Opcodes.ILOAD, slot);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                            "(I)Ljava/lang/Integer;", false);
                }
                case Type.LONG -> {
                    super.visitVarInsn(Opcodes.LLOAD, slot);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                            "(J)Ljava/lang/Long;", false);
                }
                case Type.FLOAT -> {
                    super.visitVarInsn(Opcodes.FLOAD, slot);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                            "(F)Ljava/lang/Float;", false);
                }
                case Type.DOUBLE -> {
                    super.visitVarInsn(Opcodes.DLOAD, slot);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                            "(D)Ljava/lang/Double;", false);
                }
                default -> super.visitVarInsn(Opcodes.ALOAD, slot);  // 对象/数组
            }
        }

        @Override
        public void visitFieldInsn(int op, String fo, String fn, String fd) {
            char vt = valType(fd);
            boolean cat2 = vt == 'J' || vt == 'D';
            String site = where + " " + fo + "." + fn + ":" + fd;

            if (op == Opcodes.GETFIELD) {
                // [c] -> DUP [c,c] -> GETFIELD [c,v] -> DUPx [v,c,v] -> LDC -> record -> [v]
                super.visitInsn(Opcodes.DUP);
                super.visitFieldInsn(op, fo, fn, fd);
                super.visitInsn(cat2 ? Opcodes.DUP2_X1 : Opcodes.DUP_X1);
                super.visitLdcInsn(site);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "field" + vt, fieldDesc(vt), false);
            } else if (op == Opcodes.GETSTATIC) {
                // [] -> GETSTATIC [v] -> DUPx [v,v] -> LDC -> record -> [v]
                super.visitFieldInsn(op, fo, fn, fd);
                super.visitInsn(cat2 ? Opcodes.DUP2 : Opcodes.DUP);
                super.visitLdcInsn(site);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "static" + vt, staticDesc(vt), false);
            } else {
                // PUTFIELD / PUTSTATIC：不追踪
                super.visitFieldInsn(op, fo, fn, fd);
            }
        }

        @Override
        public void visitInsn(int op) {
            char vt;
            switch (op) {
                case Opcodes.IALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD -> vt = 'I';
                case Opcodes.FALOAD -> vt = 'F';
                case Opcodes.AALOAD -> vt = 'O';
                case Opcodes.LALOAD -> vt = 'J';
                case Opcodes.DALOAD -> vt = 'D';
                default -> {
                    super.visitInsn(op);
                    return;
                }
            }
            boolean cat2 = vt == 'J' || vt == 'D';
            // [arr,idx] -> DUP2 [arr,idx,arr,idx] -> xALOAD [arr,idx,v] -> DUPx [v,arr,idx,v] -> LDC -> record -> [v]
            super.visitInsn(Opcodes.DUP2);
            super.visitInsn(op);
            super.visitInsn(cat2 ? Opcodes.DUP2_X2 : Opcodes.DUP_X2);
            super.visitLdcInsn(where + " []");
            super.visitMethodInsn(Opcodes.INVOKESTATIC, TRACE, "arr" + vt, arrDesc(vt), false);
        }
    }

    //字段/数组元素描述符首字符 → 记录器值类型字母（I 涵盖 short/byte/char/boolean，它们在栈上都是 int）
    private static char valType(String desc) {
        char c = desc.charAt(0);
        if (c == 'L' || c == '[') return 'O';
        if (c == 'J') return 'J';
        if (c == 'F') return 'F';
        if (c == 'D') return 'D';
        return 'I';
    }

    private static String fieldDesc(char vt) {
        return switch (vt) {
            case 'O' -> "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V";
            case 'J' -> "(Ljava/lang/Object;JLjava/lang/String;)V";
            case 'F' -> "(Ljava/lang/Object;FLjava/lang/String;)V";
            case 'D' -> "(Ljava/lang/Object;DLjava/lang/String;)V";
            default  -> "(Ljava/lang/Object;ILjava/lang/String;)V";
        };
    }

    private static String staticDesc(char vt) {
        return switch (vt) {
            case 'O' -> "(Ljava/lang/Object;Ljava/lang/String;)V";
            case 'J' -> "(JLjava/lang/String;)V";
            case 'F' -> "(FLjava/lang/String;)V";
            case 'D' -> "(DLjava/lang/String;)V";
            default  -> "(ILjava/lang/String;)V";
        };
    }

    private static String arrDesc(char vt) {
        return switch (vt) {
            case 'O' -> "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/String;)V";
            case 'J' -> "(Ljava/lang/Object;IJLjava/lang/String;)V";
            case 'F' -> "(Ljava/lang/Object;IFLjava/lang/String;)V";
            case 'D' -> "(Ljava/lang/Object;IDLjava/lang/String;)V";
            default  -> "(Ljava/lang/Object;IILjava/lang/String;)V";
        };
    }
}
