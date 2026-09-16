package net.eca.pro;

import forgevm.jvm.AgentFilter;
import forgevm.jvm.InterceptionRule;
import forgevm.jvm.JvmtiFilter;
import forgevm.jvm.NativeFilter;
import forgevm.jvm.ProcessFilter;
import forgevm.jvm.ThreadFilter;
import net.eca.agent.AgentLoader;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

final class EcaProWhitelist {
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
        List<InterceptionRule> rules = rules(config.nativeRules);
        return NativeFilter.Whitelist(rules.get(0), rest(rules));
    }

    static ProcessFilter process(EcaProConfig config) {
        List<InterceptionRule> rules = rules(config.processRules);
        return ProcessFilter.Whitelist(rules.get(0), rest(rules));
    }

    static ThreadFilter thread(EcaProConfig config) {
        List<InterceptionRule> rules = rules(config.threadRules);
        return ThreadFilter.Whitelist(rules.get(0), rest(rules));
    }

    private static List<InterceptionRule> rules(EcaProConfig.RuleEntries configured) {
        ArrayList<InterceptionRule> rules = new ArrayList<>();
        ownSources().forEach(source -> rules.add(InterceptionRule.Source(source)));
        configured.names.forEach(name -> rules.add(InterceptionRule.Name(name)));
        configured.sources.forEach(source -> rules.add(InterceptionRule.Source(source)));
        addPairs(rules, configured.nameAndSources, InterceptionRule::NameAndSource);
        return List.copyOf(rules);
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

    private static void addPairs(List<InterceptionRule> rules, List<String> pairs,
                                 BiFunction<String, String, InterceptionRule> factory) {
        for (String pair : pairs) {
            int separator = pair.indexOf("=>");
            if (separator <= 0 || separator >= pair.length() - 2) continue;
            String name = pair.substring(0, separator).trim();
            String source = pair.substring(separator + 2).trim();
            if (!name.isEmpty() && !source.isEmpty()) rules.add(factory.apply(name, source));
        }
    }

    private static InterceptionRule[] rest(List<InterceptionRule> rules) {
        return rules.subList(1, rules.size()).toArray(InterceptionRule[]::new);
    }

    private static void addPath(List<String> paths, Path path) {
        if (path != null) paths.add(path.toAbsolutePath().normalize().toString());
    }
}
