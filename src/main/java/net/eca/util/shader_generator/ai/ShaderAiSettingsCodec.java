package net.eca.util.shader_generator.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.util.EcaLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ShaderAiSettingsCodec {

    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create();
    private static final Path SETTINGS_PATH = Path.of(
        "config", "eca", "shadergenerator", "settings.json"
    );

    public static ShaderAiSettings load() {
        if (!Files.isRegularFile(SETTINGS_PATH)) {
            ShaderAiSettings settings = ShaderAiSettings.defaults();
            save(settings);
            return settings;
        }
        try {
            JsonObject root = JsonParser.parseString(
                Files.readString(SETTINGS_PATH, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : 1;
            String active = root.has("active_profile")
                ? root.get("active_profile").getAsString() : "default";
            List<ShaderAiSettings.Profile> profiles = readProfiles(root);
            ShaderAiSettings.AssistantOptions assistant = readAssistant(root, version);
            return new ShaderAiSettings(Math.max(2, version), active, profiles, assistant);
        } catch (IOException | RuntimeException exception) {
            EcaLogger.error("[ShaderAI] failed to load settings path={} reason={}",
                SETTINGS_PATH.toAbsolutePath().normalize(), exception.getMessage());
            return ShaderAiSettings.defaults();
        }
    }

    public static boolean save(ShaderAiSettings settings) {
        if (settings == null) return false;
        Path temporary = SETTINGS_PATH.resolveSibling("settings.json.tmp");
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            Files.writeString(
                temporary,
                GSON.toJson(writeSettings(settings)) + "\n",
                StandardCharsets.UTF_8
            );
            Files.move(
                temporary,
                SETTINGS_PATH,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
            return true;
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 临时配置会在下次保存时覆盖。
            }
            EcaLogger.error("[ShaderAI] failed to save settings path={} reason={}",
                SETTINGS_PATH.toAbsolutePath().normalize(), exception.getMessage());
            return false;
        }
    }

    public static Path path() {
        return SETTINGS_PATH.toAbsolutePath().normalize();
    }

    private static List<ShaderAiSettings.Profile> readProfiles(JsonObject root) {
        List<ShaderAiSettings.Profile> profiles = new ArrayList<>();
        if (!root.has("profiles") || !root.get("profiles").isJsonArray()) return profiles;
        for (var element : root.getAsJsonArray("profiles")) {
            if (!element.isJsonObject()) continue;
            JsonObject profile = element.getAsJsonObject();
            Map<String, String> headers = new LinkedHashMap<>();
            if (profile.has("custom_headers") && profile.get("custom_headers").isJsonObject()) {
                profile.getAsJsonObject("custom_headers").entrySet().forEach(entry ->
                    headers.put(entry.getKey(), entry.getValue().getAsString())
                );
            }
            profiles.add(new ShaderAiSettings.Profile(
                string(profile, "id", "default"),
                ShaderAiProtocol.fromName(string(profile, "protocol", "openai_responses")),
                string(profile, "base_url", ""),
                string(profile, "api_key", ""),
                string(profile, "api_key_env", ""),
                string(profile, "model", ""),
                headers,
                integer(profile, "timeout_seconds", 120)
            ));
        }
        return profiles;
    }

    private static ShaderAiSettings.AssistantOptions readAssistant(JsonObject root, int version) {
        if (!root.has("assistant") || !root.get("assistant").isJsonObject()) {
            return ShaderAiSettings.AssistantOptions.DEFAULT;
        }
        JsonObject assistant = root.getAsJsonObject("assistant");
        int maxToolRounds = integer(assistant, "max_tool_rounds", 24);
        if (version < 2 && maxToolRounds == 8) maxToolRounds = 24;
        return new ShaderAiSettings.AssistantOptions(
            bool(assistant, "auto_compile", true),
            bool(assistant, "auto_apply", true),
            bool(assistant, "send_preview_images", true),
            maxToolRounds,
            integer(assistant, "max_auto_fix_rounds", 3),
            bool(assistant, "store_remote_conversation", false)
        );
    }

    private static JsonObject writeSettings(ShaderAiSettings settings) {
        JsonObject root = new JsonObject();
        root.addProperty("version", settings.version());
        root.addProperty("active_profile", settings.activeProfile());
        JsonArray profiles = new JsonArray();
        for (ShaderAiSettings.Profile profile : settings.profiles()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", profile.id());
            object.addProperty("protocol", profile.protocol().serializedName());
            object.addProperty("base_url", profile.baseUrl());
            object.addProperty("api_key", profile.apiKey());
            object.addProperty("api_key_env", profile.apiKeyEnv());
            object.addProperty("model", profile.model());
            JsonObject headers = new JsonObject();
            profile.customHeaders().forEach(headers::addProperty);
            object.add("custom_headers", headers);
            object.addProperty("timeout_seconds", profile.timeoutSeconds());
            profiles.add(object);
        }
        root.add("profiles", profiles);
        JsonObject assistant = new JsonObject();
        assistant.addProperty("auto_compile", settings.assistant().autoCompile());
        assistant.addProperty("auto_apply", settings.assistant().autoApply());
        assistant.addProperty("send_preview_images", settings.assistant().sendPreviewImages());
        assistant.addProperty("max_tool_rounds", settings.assistant().maxToolRounds());
        assistant.addProperty("max_auto_fix_rounds", settings.assistant().maxAutoFixRounds());
        assistant.addProperty("store_remote_conversation", settings.assistant().storeRemoteConversation());
        root.add("assistant", assistant);
        return root;
    }

    private static String string(JsonObject source, String key, String fallback) {
        return source.has(key) && source.get(key).isJsonPrimitive()
            ? source.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject source, String key, int fallback) {
        return source.has(key) && source.get(key).isJsonPrimitive()
            ? source.get(key).getAsInt() : fallback;
    }

    private static boolean bool(JsonObject source, String key, boolean fallback) {
        return source.has(key) && source.get(key).isJsonPrimitive()
            ? source.get(key).getAsBoolean() : fallback;
    }

    private ShaderAiSettingsCodec() {}
}
