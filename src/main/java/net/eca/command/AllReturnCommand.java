package net.eca.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.eca.agent.EcaAgent;
import net.eca.agent.ReturnToggle;
import net.eca.api.EcaAPI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.eca.util.EcaLogger;

import java.lang.instrument.Instrumentation;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/**
 * AllReturn Command - Apply AllReturn transformation to target entity's mod classes.
 *
 * DANGER! This feature may cause unexpected crashes!
 * Requires "Enable Radical Logic" in Attack config to be enabled.
 * Will return all void and boolean methods of the attacked entity's mod.
 *
 * 危险！这个功能可能带来意想不到的崩溃风险！
 * 必须在开启激进攻击配置之后才能使用！
 * 会return所有被攻击实体所属Mod的void和boolean方法。
 */
public class AllReturnCommand {
    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("allReturn")
            .then(Commands.argument("targets", EntityArgument.entities())
                .executes(AllReturnCommand::applyAllReturnToTargets)
            )
            .then(Commands.literal("off")
                .executes(AllReturnCommand::disableAllReturn)
            )
            .then(Commands.literal("global")
                .then(Commands.argument("enable", BoolArgumentType.bool())
                    .executes(AllReturnCommand::setGlobalAllReturn)
                )
            );
    }

    private static int setGlobalAllReturn(CommandContext<CommandSourceStack> context) {
        boolean enable = BoolArgumentType.getBool(context, "enable");
        boolean success = EcaAPI.setGlobalAllReturn(enable);

        if (success) {
            if (enable) {
                context.getSource().sendSuccess(() -> Component.literal(
                    "§aGlobal AllReturn enabled (all non-whitelisted mods)"
                ), true);
            } else {
                context.getSource().sendSuccess(() -> Component.literal(
                    "§aGlobal AllReturn disabled"
                ), true);
            }
            return 1;
        } else {
            context.getSource().sendFailure(Component.literal(
                "§cFailed to enable global AllReturn. " +
                "Check if Attack Radical Logic is enabled and Agent is initialized."
            ));
            return 0;
        }
    }

    private static int disableAllReturn(CommandContext<CommandSourceStack> context) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        setAllReturnEnabled(inst, false);
        clearAllTargets(inst);
        context.getSource().sendSuccess(() -> Component.literal(
            "§aAllReturn disabled and targets cleared"
        ), true);
        return 1;
    }

    private static int applyAllReturnToTargets(CommandContext<CommandSourceStack> context) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null) {
            context.getSource().sendFailure(Component.literal(
                "§cAgent is not initialized. AllReturn cannot work."
            ));
            return 0;
        }

        Collection<? extends Entity> targets;
        try {
            targets = EntityArgument.getEntities(context, "targets");
        } catch (CommandSyntaxException e) {
            context.getSource().sendFailure(Component.literal(
                "§cFailed to resolve targets: " + e.getMessage()
            ));
            return 0;
        }
        if (targets.isEmpty()) {
            context.getSource().sendFailure(Component.literal("§cNo targets found."));
            return 0;
        }

        // 启用 AllReturn
        setAllReturnEnabled(inst, true);

        // 收集目标实体的包名前缀并添加到 allowedPackagePrefixes
        Set<String> targetPrefixes = new HashSet<>();
        for (Entity entity : targets) {
            Class<?> entityClass = entity.getClass();
            String binaryName = entityClass.getName();
            if (ReturnToggle.isExcludedBinaryName(binaryName)) {
                continue;
            }

            String packagePrefix = getPackagePrefix(binaryName);
            String internalPrefix = packagePrefix != null ? packagePrefix.replace('.', '/') : null;
            if (internalPrefix != null) {
                targetPrefixes.add(internalPrefix);
                addAllowedPackagePrefix(inst, internalPrefix);
            }
        }

        if (targetPrefixes.isEmpty()) {
            context.getSource().sendFailure(Component.literal("§cNo valid target packages found."));
            return 0;
        }

        // 即时生效（无需 retransform）
        context.getSource().sendSuccess(() -> Component.literal(
            "§aAllReturn enabled for " + targetPrefixes.size() + " package(s) (instant effect)"
        ), true);
        EcaLogger.info("AllReturn enabled for packages: {}", targetPrefixes);
        return 1;
    }

    private static String getPackagePrefix(String binaryName) {
        int lastDot = binaryName.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        return binaryName.substring(0, lastDot + 1);
    }

    private static void setAllReturnEnabled(Instrumentation inst, boolean enabled) {
        // 总是设置本地的（mod ClassLoader 中的）
        ReturnToggle.setAllReturnEnabled(enabled);
        // 也尝试设置 agent ClassLoader 中的
        if (!invokeReturnToggle(inst, "setAllReturnEnabled", new Class<?>[] { boolean.class }, enabled)) {
            EcaLogger.warn("AllReturn: agent ReturnToggle not found, using local only.");
        }
    }

    private static void clearAllTargets(Instrumentation inst) {
        // 总是清除本地的
        ReturnToggle.clearAllTargets();
        // 也尝试清除 agent 的
        if (!invokeReturnToggle(inst, "clearAllTargets", new Class<?>[0])) {
            EcaLogger.warn("AllReturn: agent ReturnToggle not found, cleared local only.");
        }
    }

    private static void addAllowedPackagePrefix(Instrumentation inst, String internalPrefix) {
        if (internalPrefix == null) {
            return;
        }
        // 总是添加到本地的
        ReturnToggle.addAllowedPackagePrefix(internalPrefix);
        // 也尝试添加到 agent 的
        invokeReturnToggle(inst, "addAllowedPackagePrefix", new Class<?>[] { String.class }, internalPrefix);
    }

    private static boolean invokeReturnToggle(Instrumentation inst, String method, Class<?>[] paramTypes, Object... args) {
        Class<?> toggleClass = findAgentReturnToggle(inst);
        if (toggleClass == null) {
            return false;
        }
        try {
            toggleClass.getMethod(method, paramTypes).invoke(null, args);
            return true;
        } catch (ReflectiveOperationException e) {
            EcaLogger.warn("AllReturn failed to invoke agent ReturnToggle.{}: {}", method, e.getMessage());
            return false;
        }
    }

    private static Class<?> findAgentReturnToggle(Instrumentation inst) {
        if (inst == null) {
            return null;
        }
        ClassLoader localLoader = AllReturnCommand.class.getClassLoader();
        Class<?> fallback = null;
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (!"net.eca.agent.ReturnToggle".equals(clazz.getName())) {
                continue;
            }
            ClassLoader loader = clazz.getClassLoader();
            if (loader != localLoader) {
                return clazz;
            }
            fallback = clazz;
        }
        return fallback;
    }
}
