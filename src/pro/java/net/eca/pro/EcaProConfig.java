package net.eca.pro;

import net.eca.agent.AgentLogWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

final class EcaProConfig {
    private static final Path PATH = Path.of("config", "eca_pro.toml");
    private static final String DEFAULTS = """
            # ECA Pro startup options. Restart the game after changing this file.
            [general]
            enabled = true
            status_window = true

            [intro]
            enabled = true

            [jvm_guard]
            lock_jvm = false
            relaunch = false
            drop_existing_agents = false
            sanitize_environment = true
            reject_argument_files = true

            [interception]
            java_agent = false
            jvmti = false
            native_load = false
            process_create = false
            thread_create = false

            [whitelist.java_agent]
            paths = []

            [whitelist.jvmti]
            modules = []

            [whitelist.native_load]
            names = []
            sources = []
            name_and_sources = []

            [whitelist.process_create]
            names = []
            sources = []
            name_and_sources = []

            [whitelist.thread_create]
            names = []
            sources = []
            name_and_sources = []

            [transformer_fallback]
            enabled = false
            failure_threshold = 3
            """;

    boolean enabled = true;
    boolean statusWindow = true;
    boolean introEnabled = true;
    boolean lockJvm;
    boolean relaunch;
    boolean dropExistingAgents;
    boolean sanitizeEnvironment = true;
    boolean rejectArgumentFiles = true;
    boolean javaAgent;
    boolean jvmti;
    boolean nativeLoad;
    boolean processCreate;
    boolean threadCreate;
    boolean fallback;
    int failureThreshold = 3;
    final List<String> javaAgentPaths = new ArrayList<>();
    final List<String> jvmtiModules = new ArrayList<>();
    final RuleEntries nativeRules = new RuleEntries();
    final RuleEntries processRules = new RuleEntries();
    final RuleEntries threadRules = new RuleEntries();

    static EcaProConfig load() {
        EcaProConfig config = new EcaProConfig();
        try {
            if (!Files.isRegularFile(PATH)) {
                Files.createDirectories(PATH.getParent());
                Files.writeString(PATH, DEFAULTS, StandardCharsets.UTF_8);
            }
            List<String> lines = Files.readAllLines(PATH, StandardCharsets.UTF_8);
            config.read(lines);
            if (!hasOption(lines, "intro", "enabled")) {
                Files.writeString(PATH, "\n[intro]\nenabled = true\n", StandardCharsets.UTF_8,
                        StandardOpenOption.APPEND);
            }
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaProConfig] Configuration read failed: " + t.getMessage());
        }
        return config;
    }

    private void read(List<String> lines) {
        String section = "";
        for (int index = 0; index < lines.size(); index++) {
            String raw = lines.get(index);
            String line = stripComment(raw).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 0) continue;
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (value.startsWith("[") && !value.endsWith("]")) {
                StringBuilder joined = new StringBuilder(value);
                while (++index < lines.size()) {
                    String continuation = stripComment(lines.get(index)).trim();
                    joined.append(continuation);
                    if (continuation.endsWith("]")) break;
                }
                value = joined.toString();
            }
            apply(section, key, value);
        }
    }

    private void apply(String section, String key, String value) {
        if ("general".equals(section)) {
            if ("enabled".equals(key)) enabled = bool(value);
            else if ("status_window".equals(key)) statusWindow = bool(value);
            return;
        }
        if ("intro".equals(section)) {
            if ("enabled".equals(key)) introEnabled = bool(value);
            return;
        }
        if ("jvm_guard".equals(section)) {
            if ("lock_jvm".equals(key)) lockJvm = bool(value);
            else if ("relaunch".equals(key)) relaunch = bool(value);
            else if ("drop_existing_agents".equals(key)) dropExistingAgents = bool(value);
            else if ("sanitize_environment".equals(key)) sanitizeEnvironment = bool(value);
            else if ("reject_argument_files".equals(key)) rejectArgumentFiles = bool(value);
            return;
        }
        if ("interception".equals(section)) {
            if ("java_agent".equals(key)) javaAgent = bool(value);
            else if ("jvmti".equals(key)) jvmti = bool(value);
            else if ("native_load".equals(key)) nativeLoad = bool(value);
            else if ("process_create".equals(key)) processCreate = bool(value);
            else if ("thread_create".equals(key)) threadCreate = bool(value);
            return;
        }
        if ("transformer_fallback".equals(section)) {
            if ("enabled".equals(key)) fallback = bool(value);
            else if ("failure_threshold".equals(key)) failureThreshold = integer(value, 3, 1, 20);
            return;
        }
        if ("whitelist.java_agent".equals(section) && "paths".equals(key)) {
            javaAgentPaths.addAll(array(value));
            return;
        }
        if ("whitelist.jvmti".equals(section) && "modules".equals(key)) {
            jvmtiModules.addAll(array(value));
            return;
        }
        RuleEntries entries = switch (section) {
            case "whitelist.native_load" -> nativeRules;
            case "whitelist.process_create" -> processRules;
            case "whitelist.thread_create" -> threadRules;
            default -> null;
        };
        if (entries == null) return;
        if ("names".equals(key)) entries.names.addAll(array(value));
        else if ("sources".equals(key)) entries.sources.addAll(array(value));
        else if ("name_and_sources".equals(key)) entries.nameAndSources.addAll(array(value));
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) quoted = !quoted;
            if (c == '#' && !quoted) return line.substring(0, i);
        }
        return line;
    }

    private static boolean hasOption(List<String> lines, String targetSection, String targetKey) {
        String section = "";
        for (String raw : lines) {
            String line = stripComment(raw).trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int separator = line.indexOf('=');
            if (separator > 0 && targetSection.equals(section)
                    && targetKey.equals(line.substring(0, separator).trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean bool(String value) {
        return Boolean.parseBoolean(value.trim());
    }

    private static int integer(String value, int fallback, int minimum, int maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(value.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<String> array(String value) {
        String text = value.trim();
        if (!text.startsWith("[") || !text.endsWith("]")) return List.of();
        text = text.substring(1, text.length() - 1).trim();
        if (text.isEmpty()) return List.of();
        ArrayList<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                quoted = !quoted;
                continue;
            }
            if (c == ',' && !quoted) {
                addValue(values, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addValue(values, current);
        return List.copyOf(values);
    }

    private static void addValue(List<String> values, StringBuilder current) {
        String value = current.toString().trim().replace("\\\"", "\"").replace("\\\\", "\\");
        if (!value.isEmpty()) values.add(value);
    }

    static final class RuleEntries {
        final List<String> names = new ArrayList<>();
        final List<String> sources = new ArrayList<>();
        final List<String> nameAndSources = new ArrayList<>();
    }
}
