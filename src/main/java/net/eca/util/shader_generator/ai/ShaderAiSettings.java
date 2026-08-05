package net.eca.util.shader_generator.ai;

import java.util.List;
import java.util.Map;

public record ShaderAiSettings(
    int version,
    String activeProfile,
    List<Profile> profiles,
    AssistantOptions assistant
) {

    public ShaderAiSettings {
        version = Math.max(1, version);
        activeProfile = activeProfile == null ? "default" : activeProfile;
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        assistant = assistant == null ? AssistantOptions.DEFAULT : assistant;
    }

    public static ShaderAiSettings defaults() {
        return new ShaderAiSettings(
            2,
            "openai-responses",
            List.of(
                new Profile(
                    "openai-responses",
                    ShaderAiProtocol.OPENAI_RESPONSES,
                    "https://api.openai.com/v1",
                    "",
                    "OPENAI_API_KEY",
                    "",
                    Map.of(),
                    120
                ),
                new Profile(
                    "openai-chat-compatible",
                    ShaderAiProtocol.OPENAI_CHAT,
                    "https://api.openai.com/v1",
                    "",
                    "OPENAI_API_KEY",
                    "",
                    Map.of(),
                    120
                ),
                new Profile(
                    "anthropic-messages",
                    ShaderAiProtocol.ANTHROPIC_MESSAGES,
                    "https://api.anthropic.com/v1",
                    "",
                    "ANTHROPIC_API_KEY",
                    "",
                    Map.of(),
                    120
                )
            ),
            AssistantOptions.DEFAULT
        );
    }

    public Profile selectedProfile() {
        return profiles.stream()
            .filter(profile -> profile.id().equals(activeProfile))
            .findFirst()
            .orElse(profiles.isEmpty() ? null : profiles.get(0));
    }

    public record Profile(
        String id,
        ShaderAiProtocol protocol,
        String baseUrl,
        String apiKey,
        String apiKeyEnv,
        String model,
        Map<String, String> customHeaders,
        int timeoutSeconds
    ) {

        public Profile {
            id = id == null || id.isBlank() ? "default" : id;
            protocol = protocol == null ? ShaderAiProtocol.OPENAI_RESPONSES : protocol;
            baseUrl = baseUrl == null ? "" : baseUrl.strip();
            apiKey = apiKey == null ? "" : apiKey.strip();
            apiKeyEnv = apiKeyEnv == null ? "" : apiKeyEnv.strip();
            model = model == null ? "" : model.strip();
            customHeaders = customHeaders == null ? Map.of() : Map.copyOf(customHeaders);
            timeoutSeconds = Math.max(10, Math.min(600, timeoutSeconds));
        }

        public String resolvedApiKey() {
            if (!apiKey.isBlank()) return apiKey;
            if (apiKeyEnv.isBlank()) return "";
            String environmentValue = System.getenv(apiKeyEnv);
            return environmentValue == null ? "" : environmentValue.strip();
        }

        public boolean isUsable() {
            return !baseUrl.isBlank() && !model.isBlank() && !resolvedApiKey().isBlank();
        }
    }

    public record AssistantOptions(
        boolean autoCompile,
        boolean autoApply,
        boolean sendPreviewImages,
        int maxToolRounds,
        int maxAutoFixRounds,
        boolean storeRemoteConversation
    ) {

        public static final AssistantOptions DEFAULT = new AssistantOptions(
            true, true, true, 24, 3, false
        );

        public AssistantOptions {
            maxToolRounds = Math.max(0, Math.min(64, maxToolRounds));
            maxAutoFixRounds = Math.max(0, Math.min(10, maxAutoFixRounds));
        }
    }
}
