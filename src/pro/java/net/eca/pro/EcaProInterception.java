package net.eca.pro;

import forgevm.core.ForgeVM;
import forgevm.jvm.RelaunchSpec;
import net.eca.agent.AgentLogWriter;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

final class EcaProInterception {
    private EcaProInterception() {
    }

    static void applyToRelaunch(RelaunchSpec.Builder builder, EcaProConfig config, Path trustedAgent) {
        if (config.javaAgent) builder.agentFilter(EcaProWhitelist.agent(config, trustedAgent));
        if (config.jvmti) builder.jvmtiFilter(EcaProWhitelist.jvmti(config));
        if (config.nativeLoad) builder.nativeFilter(EcaProWhitelist.nativeLoad(config));
        if (config.processCreate) builder.processFilter(EcaProWhitelist.process(config));
        if (config.threadCreate) builder.threadFilter(EcaProWhitelist.thread(config));
    }

    static void install(EcaProConfig config, Path trustedAgent) {
        if (config.javaAgent) invoke("Java agent whitelist",
                () -> ForgeVM.banJavaAgent(EcaProWhitelist.agent(config, trustedAgent)));
        if (config.jvmti) invoke("JVMTI whitelist",
                () -> ForgeVM.banJvmti(EcaProWhitelist.jvmti(config)));
        if (config.nativeLoad) invoke("Native load whitelist",
                () -> ForgeVM.banNativeLoad(EcaProWhitelist.nativeLoad(config)));
        if (config.processCreate) invoke("Process creation whitelist",
                () -> ForgeVM.banProcessCreate(EcaProWhitelist.process(config)));
        if (config.threadCreate) invoke("Thread creation whitelist",
                () -> ForgeVM.banThreadCreate(EcaProWhitelist.thread(config)));
    }

    static void refreshSourceFilters(EcaProConfig config) {
        if (config.nativeLoad) invoke("Native load whitelist refresh",
                () -> ForgeVM.banNativeLoad(EcaProWhitelist.nativeLoad(config)));
        if (config.processCreate) invoke("Process creation whitelist refresh",
                () -> ForgeVM.banProcessCreate(EcaProWhitelist.process(config)));
        if (config.threadCreate) invoke("Thread creation whitelist refresh",
                () -> ForgeVM.banThreadCreate(EcaProWhitelist.thread(config)));
    }

    private static void invoke(String name, BooleanSupplier operation) {
        try {
            boolean success = operation.getAsBoolean();
            String reason = ForgeVM.lastInterceptionResult() == null
                    ? "no_result" : ForgeVM.lastInterceptionResult().reason();
            AgentLogWriter.info("[EcaPro] " + name + " " + (success ? "active" : "failed")
                    + ": " + reason);
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaPro] " + name + " failed: " + t);
        }
    }
}
