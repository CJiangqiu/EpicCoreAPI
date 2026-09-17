package net.eca.pro;

import forgevm.jvm.AgentFilter;
import forgevm.jvm.InterceptionRule;
import forgevm.jvm.JvmtiFilter;
import forgevm.jvm.NativeFilter;
import forgevm.jvm.ProcessFilter;
import forgevm.jvm.ThreadFilter;
import net.eca.agent.AgentLoader;
import net.eca.coremod.TransformerWhitelist;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class EcaProWhitelist {
    private static final List<String> PLATFORM_PACKAGES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "net.minecraft.", "com.mojang.", "net.minecraftforge.", "cpw.mods.", "net.minecrell.",
            "org.lwjgl.", "org.spongepowered.", "org.objectweb.asm.", "io.netty.", "com.google.",
            "it.unimi.", "org.slf4j.", "org.apache.", "org.joml.", "com.electronwill."
    );

    private EcaProWhitelist() {
    }

    static AgentFilter agent(EcaProConfig config, Path trustedAgent) {
        ArrayList<String> paths = new ArrayList<>();
        addPath(paths, trustedAgent);
        addPath(paths, AgentLoader.getLoadedAgentPath());
        paths.addAll(config.javaAgentPaths);
        if (paths.isEmpty()) paths.add("*\\ECA\\runtime\\pro\\eca-agent.jar");
        return AgentFilter.Whitelist(paths.toArray(String[]::new));
    }

    static JvmtiFilter jvmti(EcaProConfig config) {
        ArrayList<String> modules = new ArrayList<>(config.jvmtiModules);
        if (modules.isEmpty()) modules.add("module:<eca-no-jvmti-source>");
        return JvmtiFilter.Whitelist(modules.toArray(String[]::new));
    }

    static NativeFilter nativeLoad(EcaProConfig config) {
        List<InterceptionRule> rules = sourceRules(config.nativePackages);
        return NativeFilter.Whitelist(rules.get(0), rest(rules));
    }

    static ProcessFilter process(EcaProConfig config) {
        List<InterceptionRule> rules = sourceRules(config.processPackages);
        return ProcessFilter.Whitelist(rules.get(0), rest(rules));
    }

    static ThreadFilter thread(EcaProConfig config) {
        List<InterceptionRule> rules = sourceRules(config.threadPackages);
        return ThreadFilter.Whitelist(rules.get(0), rest(rules));
    }

    private static List<InterceptionRule> sourceRules(List<String> configuredPackages) {
        Set<String> sources = new LinkedHashSet<>(ownSources());
        addPackageSources(sources, PLATFORM_PACKAGES);
        addPackageSources(sources, TransformerWhitelist.getCustomAllReturn());
        addPackageSources(sources, configuredPackages);
        ArrayList<InterceptionRule> rules = new ArrayList<>();
        sources.forEach(source -> rules.add(InterceptionRule.Source(source)));
        return List.copyOf(rules);
    }

    private static void addPackageSources(Set<String> sources, Iterable<String> packages) {
        for (String packagePrefix : packages) {
            String normalized = normalizePackage(packagePrefix);
            if (normalized == null) continue;
            sources.add("class:" + normalized + "*");
            sources.add("initiator-class:" + normalized + "*");
        }
    }

    private static String normalizePackage(String packagePrefix) {
        if (packagePrefix == null) return null;
        String normalized = packagePrefix.trim().replace('/', '.');
        if (normalized.isEmpty() || normalized.indexOf(':') >= 0) return null;
        while (normalized.endsWith("*")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) return null;
        if (!normalized.endsWith(".")) normalized += ".";
        return normalized;
    }

    private static List<String> ownSources() {
        ArrayList<String> sources = new ArrayList<>();
        sources.add("class:net.eca.*");
        sources.add("initiator-class:net.eca.*");
        try {
            URI location = EcaProRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            String value = location.toString();
            sources.add("code:" + value);
            sources.add("initiator-code:" + value);
        } catch (Throwable ignored) {
        }
        return sources;
    }

    private static InterceptionRule[] rest(List<InterceptionRule> rules) {
        return rules.subList(1, rules.size()).toArray(InterceptionRule[]::new);
    }

    private static void addPath(List<String> paths, Path path) {
        if (path != null) paths.add(path.toAbsolutePath().normalize().toString());
    }
}
