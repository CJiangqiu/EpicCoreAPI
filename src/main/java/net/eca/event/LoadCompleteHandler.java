package net.eca.event;

import net.eca.EcaMod;
import net.eca.agent.AgentLogWriter;
import net.eca.agent.EcaAgent;
import net.eca.client.render.preset.ShaderPresetRegistry;
import net.eca.coremod.EcaClassTransformer;

import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.eca.util.health.EcaSetHealthManager;
import net.eca.util.health.HealthDataFlow;
import net.eca.util.bossshow.BossShowManager;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.item_extension.ItemExtensionManager;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;

/**
 * FMLLoadCompleteEvent handler.
 * Handles entity extension scanning and radical retransform on mod loading complete.
 */
public final class LoadCompleteHandler {

    private static volatile boolean hasDelayedRetransform = false;

    private static final String[][] RADICAL_METHODS = {
        {"m_21223_", "()F", "getHealth"},
        {"m_21233_", "()F", "getMaxHealth"},
        {"m_21224_", "()Z", "isDeadOrDying"},
        {"m_6084_", "()Z", "isAlive"},
        {"m_213877_", "()Z", "isRemoved"}
    };
    private static final String LIVING_ENTITY_INTERNAL_NAME = "net/minecraft/world/entity/LivingEntity";
    private static final String PLAYER_INTERNAL_NAME = "net/minecraft/world/entity/player/Player";
    private static final String ENTITY_INTERNAL_NAME = "net/minecraft/world/entity/Entity";

    public void onLoadComplete(FMLLoadCompleteEvent event) {
        EcaMod.setLoadComplete(true);

        // 扫描并注册 Entity Extensions、Item Extensions、BossShow
        event.enqueueWork(EntityExtensionManager::scanAndRegisterAll);
        // Item Extension 系统纯客户端：ItemExtensionManager 标记 @OnlyIn(Dist.CLIENT)，
        // 在专用服务端形成该方法引用会触发 RuntimeDistCleaner 崩溃
        if (FMLEnvironment.dist.isClient()) {
            event.enqueueWork(ItemExtensionManager::scanAndRegisterAll);
            // 着色器预设系统纯客户端：扫描入口同样受 dist 门控，避免在专用服务端形成客户端类引用
            event.enqueueWork(ShaderPresetRegistry::scanAndRegisterAll);
        }
        event.enqueueWork(BossShowManager::scanAndRegisterAll);

        // 实体健康 hook 收尾：普通与激进模式都在所有 mod 构造完成后施加，确保 ECA 排在其他 mod 的字节码处理之后
        event.enqueueWork(LoadCompleteHandler::applyLoadCompleteTransformers);

        // 数据流分析器注入 ECA 运行期字节码源与 hook 剥离配置，必须排在 warmup 之前
        event.enqueueWork(HealthDataFlow::init);
        // 数据流改血预热：排在所有 ECA 字节码处理之后，确保读到的是含 ECA hook 的最终字节码
        event.enqueueWork(EcaSetHealthManager::startWarmup);
    }

    // ── 激进模式：延迟 retransform ──

    private static void applyLoadCompleteTransformers() {
        if (hasDelayedRetransform) {
            return;
        }

        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            EcaLogger.warn("Cannot apply load-complete transformers: Instrumentation not available");
            return;
        }

        try {
            // 第二遍方法目标报告仅在激进模式下打印
            if (EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
                logRadicalSecondPassMethodTargets(inst);
            }

            // 触发 retransform：填充 KNOWN_* 并对已加载的 Entity/LivingEntity/子类/容器施加 hook
            EcaClassTransformer.init();

            hasDelayedRetransform = true;

        } catch (Throwable e) {
            EcaLogger.error("Delayed retransform failed: {}", e.getMessage());
        }
    }

    // ── 字节码日志 ──

    private static void logRadicalSecondPassMethodTargets(Instrumentation inst) {
        try {
            Class<?> entityClass = null;
            Class<?> livingClass = null;
            Class<?> playerClass = null;
            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                String name = clazz.getName();
                if (entityClass == null && "net.minecraft.world.entity.Entity".equals(name)) {
                    entityClass = clazz;
                } else if (livingClass == null && "net.minecraft.world.entity.LivingEntity".equals(name)) {
                    livingClass = clazz;
                } else if (playerClass == null && "net.minecraft.world.entity.player.Player".equals(name)) {
                    playerClass = clazz;
                }
                if (entityClass != null && livingClass != null && playerClass != null) {
                    break;
                }
            }

            AgentLogWriter.info("[EcaMod] Radical second-pass method report: Entity, LivingEntity and Player");
            logMethodReportForClass(entityClass, "Entity");
            logMethodReportForClass(livingClass, "LivingEntity");
            logMethodReportForClass(playerClass, "Player");
        } catch (Throwable t) {
            AgentLogWriter.warn("[EcaMod] Failed to write radical second-pass method report: " + t.getMessage());
        }
    }

    private static void logMethodReportForClass(Class<?> clazz, String simpleName) {
        if (clazz == null) {
            AgentLogWriter.warn("[EcaMod] " + simpleName + " class not loaded during radical second-pass");
            return;
        }
        for (String[] methodMeta : RADICAL_METHODS) {
            String srgName = methodMeta[0];
            String desc = methodMeta[1];
            String readableName = methodMeta[2];
            boolean declared = hasDeclaredMethod(clazz, srgName, desc);
            AgentLogWriter.info("[EcaMod] " + clazz.getName() + "#" + srgName + desc + " (" + readableName + ") declared=" + declared);
        }
    }

    private static ClassFileTransformer createMethodBytecodeCaptureTransformer(Map<String, byte[]> out) {
        return new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                captureClassBytes(out, className, classfileBuffer);
                return null;
            }

            @Override
            public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                captureClassBytes(out, className, classfileBuffer);
                return null;
            }
        };
    }

    private static void captureClassBytes(Map<String, byte[]> out, String className, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return;
        }
        if (!ENTITY_INTERNAL_NAME.equals(className)
                && !LIVING_ENTITY_INTERNAL_NAME.equals(className)
                && !PLAYER_INTERNAL_NAME.equals(className)) {
            return;
        }
        out.put(className, classfileBuffer.clone());
    }

    private static void logCapturedMethodBytecode(Map<String, byte[]> capturedBytecode) {
        logClassMethodsBytecode(ENTITY_INTERNAL_NAME, capturedBytecode.get(ENTITY_INTERNAL_NAME));
        logClassMethodsBytecode(LIVING_ENTITY_INTERNAL_NAME, capturedBytecode.get(LIVING_ENTITY_INTERNAL_NAME));
        logClassMethodsBytecode(PLAYER_INTERNAL_NAME, capturedBytecode.get(PLAYER_INTERNAL_NAME));
    }

    private static void logClassMethodsBytecode(String classInternalName, byte[] classBytes) {
        String className = classInternalName.replace('/', '.');
        if (classBytes == null) {
            AgentLogWriter.warn("[EcaMod] No captured bytecode for " + className + " during radical second-pass");
            return;
        }

        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(classBytes).accept(classNode, 0);

            for (String[] methodMeta : RADICAL_METHODS) {
                String methodName = methodMeta[0];
                String methodDesc = methodMeta[1];
                String readableName = methodMeta[2];

                MethodNode target = null;
                for (MethodNode method : classNode.methods) {
                    if (method.name.equals(methodName) && method.desc.equals(methodDesc)) {
                        target = method;
                        break;
                    }
                }

                if (target == null) {
                    AgentLogWriter.warn("[EcaMod] " + className + "#" + methodName + methodDesc + " (" + readableName + ") not found in captured bytecode");
                    continue;
                }

                AgentLogWriter.info("[EcaMod] Bytecode dump start: " + className + "#" + methodName + methodDesc + " (" + readableName + ")");
                int index = 0;
                for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    AgentLogWriter.info("[EcaMod]   " + index + ": " + formatInstruction(insn));
                    index++;
                }
                AgentLogWriter.info("[EcaMod] Bytecode dump end: " + className + "#" + methodName + methodDesc);
            }
        } catch (Throwable t) {
            AgentLogWriter.warn("[EcaMod] Failed to dump method bytecode for " + className + ": " + t.getMessage());
        }
    }

    // ── 工具方法 ──

    private static String formatInstruction(AbstractInsnNode insn) {
        if (insn == null) {
            return "null";
        }
        int opcode = insn.getOpcode();
        String op = opcode >= 0 ? String.valueOf(opcode) : "-";

        if (insn instanceof MethodInsnNode node) {
            return "opcode=" + op + " METHOD " + node.owner + "." + node.name + node.desc;
        }
        if (insn instanceof FieldInsnNode node) {
            return "opcode=" + op + " FIELD " + node.owner + "." + node.name + " " + node.desc;
        }
        if (insn instanceof VarInsnNode node) {
            return "opcode=" + op + " VAR index=" + node.var;
        }
        if (insn instanceof TypeInsnNode node) {
            return "opcode=" + op + " TYPE " + node.desc;
        }
        if (insn instanceof JumpInsnNode) {
            return "opcode=" + op + " JUMP";
        }
        if (insn instanceof LdcInsnNode node) {
            return "opcode=" + op + " LDC " + node.cst;
        }
        if (insn instanceof IntInsnNode node) {
            return "opcode=" + op + " INT " + node.operand;
        }
        if (insn instanceof IincInsnNode node) {
            return "opcode=" + op + " IINC var=" + node.var + " inc=" + node.incr;
        }
        if (insn instanceof InvokeDynamicInsnNode node) {
            return "opcode=" + op + " INVOKEDYNAMIC " + node.name + node.desc;
        }
        if (insn instanceof MultiANewArrayInsnNode node) {
            return "opcode=" + op + " MULTIANEWARRAY " + node.desc + " dims=" + node.dims;
        }
        return "opcode=" + op + " type=" + insn.getType();
    }

    private static boolean hasDeclaredMethod(Class<?> clazz, String methodName, String descriptor) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            if (descriptor.equals(getMethodDescriptor(method))) {
                return true;
            }
        }
        return false;
    }

    private static String getMethodDescriptor(Method method) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> paramType : method.getParameterTypes()) {
            sb.append(getTypeDescriptor(paramType));
        }
        sb.append(")");
        sb.append(getTypeDescriptor(method.getReturnType()));
        return sb.toString();
    }

    private static String getTypeDescriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) {
            return "[" + getTypeDescriptor(type.getComponentType());
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }
}
