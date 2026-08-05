package net.eca.util.shader_generator.ai;

import net.eca.util.EcaLogger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ShaderAiAgent {

    private static final String SYSTEM_PROMPT = """
        You are the ECA shader editor assistant. Work only on the current ECA shader project.
        Inspect the project and reusable ECA module definitions with tools before editing.
        The visual graph, manual five-file source, and final compiled output are separate spaces.
        Visual tools, manual source, and hybrid editing are equal choices. Select the space that best
        expresses the requested effect and preserves useful existing work. Use visual tools for layers,
        elements, images, parameters, deletion, and reset when modules fit the request. Use direct
        GLSL/JSON for continuous procedural effects or precision that modules cannot express. Never
        imitate a visual mutation by deleting or rewriting generated GLSL. Preserve existing visual
        work unless the user explicitly requested source-only editing.
        Match the requested visual motifs exactly. Do not add an unrequested focal shape merely because
        a module exists: basic_star is a visible five-point star and supernova is a large focal burst.
        For ordinary star fields, prefer dot_star and cross_star unless the user requests other shapes.
        Element size is relative to the full preview. Keep ordinary dots and cross stars small, normally
        at or below 0.08, and reserve values above 0.10 for deliberately prominent subjects.
        spread_x and spread_y are maximum signed offsets from the center, so about 0.45 to 0.50 fills
        a frame centered at 0.5; values near 1.0 place many instances outside the preview. A positive
        repeat_interval creates fully invisible downtime for the entire module group. Use zero for a
        continuously visible twinkle unless the user explicitly requests stars that disappear in groups.
        Configure every layer deliberately. Non-background layers must normally use color_a=0 so they
        do not erase lower layers. Use opaque alpha only for an intentional background, and choose a
        suitable blend_mode instead of stacking opaque NORMAL layers.
        For existing source, prefer search_shader_file and
        read_shader_file_range followed by incremental edit tools. Pass the returned version to edits,
        and reread after a version conflict. Use replace_shader_file only for intentional full rewrites.
        Work in coherent phases: inspect relevant definitions; build the foundation as one batch;
        compile and capture a preview; then refine as another batch or use the hybrid source fallback.
        In each phase, issue every independent tool call whose inputs are known, including multiple
        add_layer and add_element calls in execution order. Do not spread one element at a time across
        responses, but do not commit an entire complex scene before its first preview either. The host
        compiles once after each mutation batch, so inspect that diagnostic before the next phase.
        Clear-and-rebuild requests mean reset_to_empty_visual_project followed by batched add calls;
        do not ask the user to choose an architecture when that intent is explicit. Both manual source
        and visual overlay are supported together. Use set_editing_mode rather than treating them as
        mutually exclusive. Use capture_preview when visual inspection is useful and image input is
        available. Never invent file names, module IDs, parameters, source text, versions, or compiler
        results. Keep changes focused and do not repeat successful calls.
        """;

    private final ShaderAiHttpClient client = new ShaderAiHttpClient();

    public Result run(
        ShaderAiSettings settings,
        List<ShaderAiExchange> previousConversation,
        String userMessage,
        ShaderAiToolContext toolContext,
        Consumer<String> progress
    ) throws IOException, InterruptedException {
        ShaderAiSettings.Profile profile = settings.selectedProfile();
        if (profile == null || !profile.isUsable()) {
            throw new IOException("Configure the active profile in " + ShaderAiSettingsCodec.path());
        }
        EcaLogger.info("[ShaderAI] request started profile={} protocol={} model={}",
            profile.id(), profile.protocol().serializedName(), profile.model());
        List<ShaderAiExchange> exchanges = new ArrayList<>();
        exchanges.add(ShaderAiExchange.system(SYSTEM_PROMPT));
        if (previousConversation != null) exchanges.addAll(previousConversation);
        exchanges.add(ShaderAiExchange.user(userMessage));
        String finalText = "";
        int failedAutoFixes = 0;
        boolean previewImagesAvailable = settings.assistant().sendPreviewImages();
        Set<String> successfulMutationCalls = new HashSet<>();
        int maximumRounds = settings.assistant().maxToolRounds();
        for (int round = 0; maximumRounds == 0 || round < maximumRounds; round++) {
            progress.accept("Requesting AI response " + (round + 1));
            ShaderAiResponse response;
            try {
                response = complete(settings, profile, exchanges);
            } catch (IOException exception) {
                if (!containsPreviewImages(exchanges)) throw exception;
                EcaLogger.warn(
                    "[ShaderAI] preview request failed; retrying without images profile={} reason={}",
                    profile.id(), conciseLog(exception.getMessage())
                );
                progress.accept("Preview request failed; retrying without images");
                exchanges.removeIf(exchange -> !exchange.images().isEmpty());
                exchanges.add(ShaderAiExchange.user(
                    "The preview image could not be delivered to this API. Continue from the "
                        + "current project summary and compile diagnostics without image input."
                ));
                previewImagesAvailable = false;
                response = complete(settings, profile, exchanges);
            }
            if (!response.text().isBlank()) {
                exchanges.add(ShaderAiExchange.assistant(response.text()));
                finalText = response.text();
            }
            if (response.toolCalls().isEmpty()) {
                return new Result(withoutSystem(exchanges), finalText, false);
            }
            for (ShaderAiResponse.ToolCall call : response.toolCalls()) {
                exchanges.add(ShaderAiExchange.toolCall(call.id(), call.name(), call.input()));
            }
            progress.accept("Running ECA tool batch: " + batchLabel(response.toolCalls()));
            List<ToolOutcome> outcomes = new ArrayList<>();
            List<String> successfulCallsInBatch = new ArrayList<>();
            int lastSuccessfulMutation = -1;
            for (ShaderAiResponse.ToolCall call : response.toolCalls()) {
                EcaLogger.info(
                    "[ShaderAI] executing tool={} round={} input={}",
                    call.name(), round + 1, conciseLog(call.input().toString())
                );
                ShaderAiToolResult result;
                boolean mutationApplied = false;
                boolean writeApplied = false;
                String mutationKey = mutationKey(call);
                if (requiresAutoApply(call.name())
                        && successfulMutationCalls.contains(mutationKey)) {
                    result = ShaderAiToolResult.text(
                        "This exact write already succeeded in an earlier round. "
                            + "Use its previous result and do not repeat it."
                    );
                } else if (requiresAutoApply(call.name()) && !settings.assistant().autoApply()) {
                    result = ShaderAiToolResult.text(
                        "Mutation blocked because assistant.auto_apply is false. "
                            + "Explain the proposed change to the user or ask them to enable it."
                    );
                } else if (isProjectMutation(call.name())
                        && failedAutoFixes > 0
                        && failedAutoFixes >= settings.assistant().maxAutoFixRounds()) {
                    result = ShaderAiToolResult.text(
                        "Mutation blocked because the automatic compile-repair limit was reached. "
                            + "Explain the remaining compiler error to the user."
                    );
                } else {
                    result = ShaderAiToolRegistry.execute(call.name(), call.input(), toolContext);
                    boolean succeeded = !result.text().startsWith("Tool failed:");
                    writeApplied = requiresAutoApply(call.name()) && succeeded;
                    mutationApplied = isProjectMutation(call.name()) && succeeded;
                }
                outcomes.add(new ToolOutcome(call, result));
                EcaLogger.info(
                    "[ShaderAI] tool result name={} round={} result={}",
                    call.name(), round + 1, conciseLog(result.text())
                );
                if (writeApplied) {
                    successfulCallsInBatch.add(mutationKey);
                }
                if (mutationApplied) {
                    lastSuccessfulMutation = outcomes.size() - 1;
                }
            }
            successfulMutationCalls.addAll(successfulCallsInBatch);
            if (lastSuccessfulMutation >= 0 && settings.assistant().autoCompile()) {
                String compileResult = toolContext.compilePreview();
                failedAutoFixes = compileResult.startsWith("success=true")
                    ? 0 : failedAutoFixes + 1;
                ToolOutcome previous = outcomes.get(lastSuccessfulMutation);
                outcomes.set(lastSuccessfulMutation, new ToolOutcome(
                    previous.call(),
                    new ShaderAiToolResult(
                        previous.result().text() + "\nBatch compile result:\n" + compileResult,
                        previous.result().images()
                    )
                ));
            }
            for (ToolOutcome outcome : outcomes) {
                exchanges.add(ShaderAiExchange.toolResult(
                    outcome.call().id(), outcome.call().name(), outcome.result().text()
                ));
                if (previewImagesAvailable && !outcome.result().images().isEmpty()) {
                    exchanges.add(ShaderAiExchange.userWithImages(
                        "Current ECA preview captured after tool " + outcome.call().name() + ".",
                        outcome.result().images()
                    ));
                }
            }
        }
        EcaLogger.warn("[ShaderAI] tool round limit reached profile={} limit={}",
            profile.id(), maximumRounds);
        String pausedText = "已达到工具轮次上限，当前修改和会话已保存。发送“继续”可接着完成。";
        exchanges.add(ShaderAiExchange.assistant(pausedText));
        return new Result(withoutSystem(exchanges), pausedText, true);
    }

    private ShaderAiResponse complete(
        ShaderAiSettings settings,
        ShaderAiSettings.Profile profile,
        List<ShaderAiExchange> exchanges
    ) throws IOException, InterruptedException {
        return client.complete(
            profile,
            exchanges,
            ShaderAiToolRegistry.definitions(),
            settings.assistant().storeRemoteConversation()
        );
    }

    private static boolean containsPreviewImages(List<ShaderAiExchange> exchanges) {
        return exchanges.stream().anyMatch(exchange -> !exchange.images().isEmpty());
    }

    private static String conciseLog(String value) {
        if (value == null || value.isBlank()) return "<empty>";
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000) + "...";
    }

    private static String batchLabel(List<ShaderAiResponse.ToolCall> calls) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ShaderAiResponse.ToolCall call : calls) {
            counts.merge(call.name(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .map(entry -> entry.getKey() + (entry.getValue() > 1 ? " ×" + entry.getValue() : ""))
            .reduce((left, right) -> left + ", " + right)
            .orElse("none");
    }

    private static String mutationKey(ShaderAiResponse.ToolCall call) {
        return call.name() + "\n" + call.input();
    }

    private static boolean requiresAutoApply(String toolName) {
        return isProjectMutation(toolName)
            || "save_project".equals(toolName)
            || "export_shader_files".equals(toolName);
    }

    private static boolean isProjectMutation(String toolName) {
        return "replace_shader_file".equals(toolName)
            || "replace_shader_range".equals(toolName)
            || "replace_shader_text".equals(toolName)
            || "insert_shader_text".equals(toolName)
            || "add_layer".equals(toolName)
            || "update_layer".equals(toolName)
            || "add_element".equals(toolName)
            || "update_element_parameters".equals(toolName)
            || "set_layer_background_image".equals(toolName)
            || "set_element_image".equals(toolName)
            || "remove_element".equals(toolName)
            || "remove_layer".equals(toolName)
            || "clear_layer_background".equals(toolName)
            || "clear_element_image".equals(toolName)
            || "reset_to_empty_visual_project".equals(toolName)
            || "set_editing_mode".equals(toolName)
            || "undo_ai_transaction".equals(toolName)
            || "redo_ai_transaction".equals(toolName);
    }

    private static List<ShaderAiExchange> withoutSystem(List<ShaderAiExchange> exchanges) {
        return exchanges.stream()
            .filter(exchange -> exchange.kind() != ShaderAiExchange.Kind.SYSTEM_TEXT)
            .toList();
    }

    private record ToolOutcome(ShaderAiResponse.ToolCall call, ShaderAiToolResult result) {}

    public record Result(List<ShaderAiExchange> conversation, String text, boolean paused) {

        public Result {
            conversation = conversation == null ? List.of() : List.copyOf(conversation);
            text = text == null ? "" : text;
        }
    }
}
