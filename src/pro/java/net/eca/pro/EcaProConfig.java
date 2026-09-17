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
    private static final String COREMOD_DEFAULTS = """

            [coremod]
            # Block transformation services whose provider packages are not trusted.
            # Requires enable_fvm = true and jvm_guard.relaunch = true.
            ban = false
            # Allowed transformation-service provider package prefixes.
            # Platform services and ECA's verified archive are added automatically.
            # Example: whitelist = ["com.example.bootstrap."]
            whitelist = []
            """;
    private static final String DEFAULTS = """
            # ECA Pro configuration. Restart the game after changing this file.
            # Master switch for the FVM controller. JVM guards, relaunch and interception
            # require this option to be true. The intro is independent.
            enable_fvm = true
            # Show the FVM status window. Requires enable_fvm = true.
            status_window = true

            [intro]
            # Play the ECA Pro intro before the normal loading window. Does not require FVM.
            enabled = true

            [jvm_guard]
            # Prevent unauthorized attempts to terminate or detach the protected JVM.
            # Requires enable_fvm = true.
            lock_jvm = false
            # Restart the game under a controlled JVM with the policy applied before startup.
            # Requires enable_fvm = true.
            relaunch = false
            # Remove Java agents already present on the original command line during relaunch.
            # Requires relaunch = true.
            drop_existing_agents = false
            # Remove injection-related environment variables during relaunch.
            # Requires relaunch = true.
            sanitize_environment = true
            # Reject @argument files so hidden JVM arguments cannot bypass relaunch filtering.
            # Requires relaunch = true.
            reject_argument_files = true

            [java_agent]
            # Block Java agents not matched by this whitelist. Requires enable_fvm = true.
            ban = false
            # Allowed Agent JAR path patterns. Effective only when ban = true.
            # Example: whitelist = ["*trusted-agent.jar"]
            whitelist = []

            [jvmti]
            # Block JVMTI access from native modules not matched by this whitelist.
            # Requires enable_fvm = true.
            ban = false
            # Allowed native module path patterns. Effective only when ban = true.
            # Example: whitelist = ["*trusted-native.dll"]
            whitelist = []

            [native_load]
            # Block native-library loads from Java packages outside this whitelist.
            # Requires enable_fvm = true.
            ban = false
            # Allowed Java package prefixes. Effective only when ban = true.
            # Example: whitelist = ["com.example.mod."]
            # Platform packages, ECA and registered AllReturn packages are added automatically.
            whitelist = []

            [process_create]
            # Block child-process creation from Java packages outside this whitelist.
            # Requires enable_fvm = true.
            ban = false
            # Allowed Java package prefixes. Effective only when ban = true.
            # Example: whitelist = ["com.example.mod."]
            # Platform packages, ECA and registered AllReturn packages are added automatically.
            whitelist = []

            [thread_create]
            # Block platform-thread creation from Java packages outside this whitelist.
            # Requires enable_fvm = true. Virtual threads are not controlled by this option.
            ban = false
            # Allowed Java package prefixes. Effective only when ban = true.
            # Example: whitelist = ["com.example.mod."]
            # Platform packages, ECA and registered AllReturn packages are added automatically.
            whitelist = []

            %s
            """.formatted(COREMOD_DEFAULTS.strip());

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
    boolean coremod;
    final List<String> javaAgentPaths = new ArrayList<>();
    final List<String> jvmtiModules = new ArrayList<>();
    final List<String> nativePackages = new ArrayList<>();
    final List<String> processPackages = new ArrayList<>();
    final List<String> threadPackages = new ArrayList<>();
    final List<String> coremodPackages = new ArrayList<>();

    static EcaProConfig load() {
        EcaProConfig config = new EcaProConfig();
        try {
            if (!Files.isRegularFile(PATH)) {
                Files.createDirectories(PATH.getParent());
                Files.writeString(PATH, DEFAULTS, StandardCharsets.UTF_8);
            }
            List<String> lines = Files.readAllLines(PATH, StandardCharsets.UTF_8);
            config.read(lines);
            if (!hasSection(lines, "coremod")) {
                Files.writeString(PATH, COREMOD_DEFAULTS, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaProConfig] Configuration read failed: " + t.getMessage());
        }
        return config;
    }

    private static boolean hasSection(List<String> lines, String expected) {
        for (String raw : lines) {
            String line = stripComment(raw).trim();
            if (line.equals("[" + expected + "]")) return true;
        }
        return false;
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
        if (section.isEmpty()) {
            if ("enable_fvm".equals(key)) enabled = bool(value);
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
        applyInterception(section, key, value);
    }

    private void applyInterception(String section, String key, String value) {
        if ("java_agent".equals(section)) {
            if ("ban".equals(key)) javaAgent = bool(value);
            else if ("whitelist".equals(key)) javaAgentPaths.addAll(array(value));
        } else if ("jvmti".equals(section)) {
            if ("ban".equals(key)) jvmti = bool(value);
            else if ("whitelist".equals(key)) jvmtiModules.addAll(array(value));
        } else if ("native_load".equals(section)) {
            if ("ban".equals(key)) nativeLoad = bool(value);
            else if ("whitelist".equals(key)) nativePackages.addAll(array(value));
        } else if ("process_create".equals(section)) {
            if ("ban".equals(key)) processCreate = bool(value);
            else if ("whitelist".equals(key)) processPackages.addAll(array(value));
        } else if ("thread_create".equals(section)) {
            if ("ban".equals(key)) threadCreate = bool(value);
            else if ("whitelist".equals(key)) threadPackages.addAll(array(value));
        } else if ("coremod".equals(section)) {
            if ("ban".equals(key)) coremod = bool(value);
            else if ("whitelist".equals(key)) coremodPackages.addAll(array(value));
        }
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

    private static boolean bool(String value) {
        return Boolean.parseBoolean(value.trim());
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

}
