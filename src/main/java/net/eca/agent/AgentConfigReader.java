package net.eca.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class AgentConfigReader {

    private static final String CONFIG_FILE = "eca.toml";
    private static final String DEFENCE_SECTION = "Defence";
    private static final String GENERAL_SECTION = "";
    private static final String RADICAL_KEY = "Enable Radical Logic";
    private static final String LOADING_BG_KEY = "Enable Custom Loading Background";

    public static boolean isDefenceRadicalEnabled() {
        return readBoolean(DEFENCE_SECTION, RADICAL_KEY, false);
    }

    public static boolean isCustomLoadingBackgroundEnabled() {
        return readBoolean(GENERAL_SECTION, LOADING_BG_KEY, true);
    }

    private static boolean readBoolean(String targetSection, String targetKey, boolean defaultValue) {
        Path configPath = Paths.get("config", CONFIG_FILE);
        try {
            if (!Files.exists(configPath)) {
                return defaultValue;
            }

            List<String> lines = Files.readAllLines(configPath);
            String currentSection = "";
            for (String rawLine : lines) {
                String line = rawLine;
                int commentStart = line.indexOf('#');
                if (commentStart >= 0) {
                    line = line.substring(0, commentStart);
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).trim();
                    continue;
                }

                if (!line.contains("=")) {
                    continue;
                }
                if (!targetSection.equals(currentSection)) {
                    continue;
                }

                int eq = line.indexOf('=');
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

                if (key.startsWith("\"") && key.endsWith("\"") && key.length() >= 2) {
                    key = key.substring(1, key.length() - 1);
                }

                if (!targetKey.equals(key)) {
                    continue;
                }

                if ("true".equalsIgnoreCase(value)) {
                    return true;
                }
                if ("false".equalsIgnoreCase(value)) {
                    return false;
                }
                AgentLogWriter.warn("[AgentConfigReader] Invalid boolean value for " + targetKey + ": " + value + ", use default=" + defaultValue);
                return defaultValue;
            }
        } catch (Throwable t) {
            AgentLogWriter.warn("[AgentConfigReader] Failed reading config/eca.toml key=" + targetKey + ": " + t.getMessage());
        }
        return defaultValue;
    }

    private AgentConfigReader() {}
}
