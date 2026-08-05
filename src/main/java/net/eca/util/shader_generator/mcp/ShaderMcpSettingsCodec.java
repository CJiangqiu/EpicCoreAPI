package net.eca.util.shader_generator.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.util.EcaLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ShaderMcpSettingsCodec {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SETTINGS_PATH = Path.of(
        "config", "eca", "shadergenerator", "mcp_settings.json"
    );

    public static ShaderMcpSettings load() {
        if (!Files.isRegularFile(SETTINGS_PATH)) {
            ShaderMcpSettings settings = ShaderMcpSettings.defaults();
            save(settings);
            return settings;
        }
        try {
            JsonObject root = JsonParser.parseString(
                Files.readString(SETTINGS_PATH, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            int port = root.has("port")
                ? root.get("port").getAsInt() : ShaderMcpSettings.DEFAULT_PORT;
            ShaderMcpSettings settings = new ShaderMcpSettings(port);
            if (root.has("token") || !root.has("version")
                || root.get("version").getAsInt() != 2) {
                save(settings);
            }
            return settings;
        } catch (IOException | RuntimeException exception) {
            EcaLogger.error("[ShaderMCP] failed to load settings path={} reason={}",
                SETTINGS_PATH.toAbsolutePath().normalize(), exception.getMessage());
            return ShaderMcpSettings.defaults();
        }
    }

    public static boolean save(ShaderMcpSettings settings) {
        if (settings == null) return false;
        Path temporary = SETTINGS_PATH.resolveSibling("mcp_settings.json.tmp");
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        root.addProperty("port", settings.port());
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            Files.writeString(
                temporary,
                GSON.toJson(root) + "\n",
                StandardCharsets.UTF_8
            );
            try {
                Files.move(
                    temporary,
                    SETTINGS_PATH,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException exception) {
                Files.move(temporary, SETTINGS_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 下一次保存会覆盖遗留的临时文件。
            }
            EcaLogger.error("[ShaderMCP] failed to save settings path={} reason={}",
                SETTINGS_PATH.toAbsolutePath().normalize(), exception.getMessage());
            return false;
        }
    }

    public static Path path() {
        return SETTINGS_PATH.toAbsolutePath().normalize();
    }

    private ShaderMcpSettingsCodec() {}
}
