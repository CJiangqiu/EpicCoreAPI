package net.eca.client.gui;

import net.eca.util.EcaLogger;
import net.eca.util.shader_generator.ai.ShaderAiAgent;
import net.eca.util.shader_generator.ai.ShaderAiExchange;
import net.eca.util.shader_generator.ai.ShaderAiSettings;
import net.eca.util.shader_generator.ai.ShaderAiToolContext;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ShaderAiSession implements AutoCloseable {

    private final ShaderAiAgent agent = new ShaderAiAgent();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ECA-Shader-AI");
        thread.setDaemon(true);
        return thread;
    });
    private final List<ShaderAiExchange> conversation = new ArrayList<>();
    private final List<Message> messages = new ArrayList<>();
    private volatile boolean busy;
    private volatile boolean closed;

    boolean send(
        ShaderAiSettings settings,
        String userMessage,
        ShaderAiToolContext toolContext
    ) {
        if (closed || busy || userMessage == null || userMessage.isBlank()) return false;
        String message = userMessage.strip();
        List<ShaderAiExchange> history = List.copyOf(conversation);
        messages.add(new Message(Role.USER, message));
        busy = true;
        executor.execute(() -> run(settings, history, message, toolContext));
        return true;
    }

    private void run(
        ShaderAiSettings settings,
        List<ShaderAiExchange> history,
        String message,
        ShaderAiToolContext toolContext
    ) {
        try {
            ShaderAiAgent.Result result = agent.run(
                settings,
                history,
                message,
                toolContext,
                this::postStatus
            );
            postResult(result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            postStatus("AI 请求已取消");
        } catch (IOException | RuntimeException exception) {
            EcaLogger.error("[ShaderAI] request failed reason={}", safeMessage(exception));
            postStatus("AI 请求失败：" + safeMessage(exception));
        } finally {
            Minecraft.getInstance().execute(() -> {
                if (!closed) busy = false;
            });
        }
    }

    private void postResult(ShaderAiAgent.Result result) {
        Minecraft.getInstance().execute(() -> {
            if (closed) return;
            conversation.clear();
            conversation.addAll(result.conversation());
            messages.add(new Message(
                Role.ASSISTANT,
                result.text().isBlank() ? "已完成工具调用。" : result.text()
            ));
        });
    }

    void addStatus(String message) {
        if (closed || message == null || message.isBlank()) return;
        messages.add(new Message(Role.STATUS, message));
    }

    private void postStatus(String message) {
        Minecraft.getInstance().execute(() -> addStatus(message));
    }

    List<Message> messages() {
        return List.copyOf(messages);
    }

    boolean busy() {
        return busy;
    }

    boolean isEmpty() {
        return messages.isEmpty();
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        busy = false;
        executor.shutdownNow();
        conversation.clear();
        messages.clear();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
            ? throwable.getClass().getSimpleName() : message;
    }

    record Message(Role role, String markdown) {

        Message {
            if (role == null) role = Role.STATUS;
            markdown = markdown == null ? "" : markdown;
        }
    }

    enum Role {
        USER,
        ASSISTANT,
        STATUS
    }
}
