package net.eca.client.gui;

import net.eca.util.shader_generator.ai.ShaderAiProtocol;
import net.eca.util.shader_generator.ai.ShaderAiSettings;
import net.eca.util.shader_generator.ai.ShaderAiSettingsCodec;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class ShaderAiSettingsScreen extends Screen {

    private static final int DIALOG_WIDTH = 600;
    private static final int ROW_HEIGHT = 24;
    private static final int FIELD_HEIGHT = 20;
    private static final int LABEL_WIDTH = 144;
    private static final int TOTAL_ROWS = 11;
    private final ShaderAiAssistantScreen parent;
    private final List<ProfileDraft> profiles = new ArrayList<>();
    private final List<ScrollWidget> scrollWidgets = new ArrayList<>();
    private final ShaderAiSettings.AssistantOptions originalAssistant;
    private int selectedProfile;
    private int left;
    private int top;
    private int dialogWidth;
    private int fieldLeft;
    private int fieldWidth;
    private int contentTop;
    private int contentBottom;
    private int footerTop;
    private int scrollRow;
    private EditBox baseUrlField;
    private EditBox modelField;
    private EditBox apiKeyField;
    private EditBox apiKeyEnvField;
    private EditBox timeoutField;
    private EditBox maxToolRoundsField;
    private Button profileButton;
    private Button protocolButton;
    private Button apiKeyVisibilityButton;
    private Button autoApplyButton;
    private Button autoCompileButton;
    private Button sendPreviewButton;
    private boolean showApiKey;
    private boolean autoApply;
    private boolean autoCompile;
    private boolean sendPreviewImages;
    private Component status = Component.empty();
    private boolean statusError;

    ShaderAiSettingsScreen(
        ShaderAiAssistantScreen parent,
        ShaderAiSettings settings
    ) {
        super(Component.translatable("gui.eca.shader_generator.ai.settings_title"));
        this.parent = parent;
        ShaderAiSettings source = settings == null ? ShaderAiSettings.defaults() : settings;
        for (ShaderAiSettings.Profile profile : source.profiles()) {
            profiles.add(new ProfileDraft(profile));
        }
        if (profiles.isEmpty()) {
            ShaderAiSettings defaults = ShaderAiSettings.defaults();
            defaults.profiles().forEach(profile -> profiles.add(new ProfileDraft(profile)));
        }
        for (int index = 0; index < profiles.size(); index++) {
            if (profiles.get(index).id.equals(source.activeProfile())) {
                selectedProfile = index;
                break;
            }
        }
        originalAssistant = source.assistant();
        autoApply = originalAssistant.autoApply();
        autoCompile = originalAssistant.autoCompile();
        sendPreviewImages = originalAssistant.sendPreviewImages();
    }

    @Override
    protected void init() {
        scrollWidgets.clear();
        dialogWidth = Math.min(DIALOG_WIDTH, width - 24);
        left = (width - dialogWidth) / 2;
        top = 8;
        fieldLeft = left + LABEL_WIDTH;
        fieldWidth = dialogWidth - LABEL_WIDTH - 16;
        contentTop = top + 24;
        footerTop = height - 30;
        contentBottom = footerTop - 18;

        int row = 0;
        int currentRow = row++;
        int y = rowY(currentRow);
        addScrollWidget(Button.builder(Component.literal("‹"), button -> switchProfile(-1))
            .bounds(fieldLeft, y, 24, FIELD_HEIGHT).build(), currentRow);
        profileButton = addScrollWidget(Button.builder(
            profileLabel(), button -> switchProfile(1)
        ).bounds(fieldLeft + 28, y, fieldWidth - 56, FIELD_HEIGHT).build(), currentRow);
        addScrollWidget(Button.builder(Component.literal("›"), button -> switchProfile(1))
            .bounds(fieldLeft + fieldWidth - 24, y, 24, FIELD_HEIGHT).build(), currentRow);

        currentRow = row++;
        y = rowY(currentRow);
        protocolButton = addScrollWidget(Button.builder(
            protocolLabel(), button -> cycleProtocol()
        ).bounds(fieldLeft, y, fieldWidth, FIELD_HEIGHT).build(), currentRow);

        baseUrlField = textField(row++, 2048);
        modelField = textField(row++, 256);
        currentRow = row++;
        apiKeyField = textField(currentRow, 4096, fieldWidth - 76);
        apiKeyVisibilityButton = addScrollWidget(Button.builder(
            apiKeyVisibilityLabel(), button -> toggleApiKeyVisibility()
        ).bounds(fieldLeft + fieldWidth - 72, apiKeyField.getY(), 72, FIELD_HEIGHT).build(), currentRow);
        apiKeyEnvField = textField(row++, 256);
        timeoutField = textField(row++, 3);
        timeoutField.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        maxToolRoundsField = textField(row++, 2);
        maxToolRoundsField.setFilter(
            value -> value.isEmpty() || value.chars().allMatch(Character::isDigit)
        );
        maxToolRoundsField.setValue(Integer.toString(originalAssistant.maxToolRounds()));

        autoApplyButton = booleanButton(row++, () -> {
            autoApply = !autoApply;
            autoApplyButton.setMessage(booleanLabel(
                "gui.eca.shader_generator.ai.auto_apply", autoApply
            ));
        });
        autoCompileButton = booleanButton(row++, () -> {
            autoCompile = !autoCompile;
            autoCompileButton.setMessage(booleanLabel(
                "gui.eca.shader_generator.ai.auto_compile", autoCompile
            ));
        });
        sendPreviewButton = booleanButton(row, () -> {
            sendPreviewImages = !sendPreviewImages;
            sendPreviewButton.setMessage(booleanLabel(
                "gui.eca.shader_generator.ai.send_preview", sendPreviewImages
            ));
        });
        refreshBooleanLabels();
        loadSelectedProfile();
        updateScrollWidgets();

        int buttonWidth = Math.min(144, (dialogWidth - 24) / 2);
        int buttonGap = 8;
        int buttonLeft = left + (dialogWidth - buttonWidth * 2 - buttonGap) / 2;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.ai.save"),
            button -> save()
        ).bounds(buttonLeft, footerTop, buttonWidth, FIELD_HEIGHT).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.ai.cancel"),
            button -> onClose()
        ).bounds(buttonLeft + buttonWidth + buttonGap, footerTop, buttonWidth, FIELD_HEIGHT).build());
        setInitialFocus(baseUrlField);
    }

    private EditBox textField(int row, int maxLength) {
        return textField(row, maxLength, fieldWidth);
    }

    private EditBox textField(int row, int maxLength, int width) {
        EditBox field = new EditBox(
            font, fieldLeft, rowY(row), width, FIELD_HEIGHT, Component.empty()
        );
        field.setMaxLength(maxLength);
        return addScrollWidget(field, row);
    }

    private Button booleanButton(int row, Runnable action) {
        return addScrollWidget(Button.builder(Component.empty(), button -> action.run())
            .bounds(fieldLeft, rowY(row), fieldWidth, FIELD_HEIGHT).build(), row);
    }

    private int rowY(int row) {
        return contentTop + (row - scrollRow) * ROW_HEIGHT;
    }

    private <T extends AbstractWidget> T addScrollWidget(T widget, int row) {
        scrollWidgets.add(new ScrollWidget(widget, row));
        return addRenderableWidget(widget);
    }

    private void updateScrollWidgets() {
        int visibleRows = visibleRows();
        int maximum = Math.max(0, TOTAL_ROWS - visibleRows);
        scrollRow = Math.max(0, Math.min(scrollRow, maximum));
        for (ScrollWidget entry : scrollWidgets) {
            boolean visible = entry.row >= scrollRow && entry.row < scrollRow + visibleRows;
            entry.widget.visible = visible;
            entry.widget.setY(rowY(entry.row));
        }
    }

    private int visibleRows() {
        return Math.max(1, (contentBottom - contentTop) / ROW_HEIGHT);
    }

    private void switchProfile(int offset) {
        storeSelectedProfile();
        selectedProfile = Math.floorMod(selectedProfile + offset, profiles.size());
        loadSelectedProfile();
        status = Component.empty();
    }

    private void cycleProtocol() {
        ShaderAiProtocol[] values = ShaderAiProtocol.values();
        ProfileDraft profile = profiles.get(selectedProfile);
        profile.protocol = values[(profile.protocol.ordinal() + 1) % values.length];
        protocolButton.setMessage(protocolLabel());
    }

    private void toggleApiKeyVisibility() {
        showApiKey = !showApiKey;
        updateApiKeyFormatter();
        apiKeyVisibilityButton.setMessage(apiKeyVisibilityLabel());
    }

    private void updateApiKeyFormatter() {
        apiKeyField.setFormatter((value, offset) -> FormattedCharSequence.forward(
            showApiKey ? value : "•".repeat(value.length()),
            Style.EMPTY
        ));
    }

    private void loadSelectedProfile() {
        ProfileDraft profile = profiles.get(selectedProfile);
        baseUrlField.setValue(profile.baseUrl);
        modelField.setValue(profile.model);
        apiKeyField.setValue(profile.apiKey);
        apiKeyEnvField.setValue(profile.apiKeyEnv);
        timeoutField.setValue(Integer.toString(profile.timeoutSeconds));
        profileButton.setMessage(profileLabel());
        protocolButton.setMessage(protocolLabel());
        updateApiKeyFormatter();
    }

    private void storeSelectedProfile() {
        ProfileDraft profile = profiles.get(selectedProfile);
        profile.baseUrl = baseUrlField.getValue().strip();
        profile.model = modelField.getValue().strip();
        profile.apiKey = apiKeyField.getValue().strip();
        profile.apiKeyEnv = apiKeyEnvField.getValue().strip();
        profile.timeoutSeconds = parseTimeout(timeoutField.getValue());
    }

    private void save() {
        storeSelectedProfile();
        ProfileDraft selected = profiles.get(selectedProfile);
        Component validation = validate(selected);
        if (validation != null) {
            statusError = true;
            status = validation;
            return;
        }
        int maxToolRounds = parseInteger(maxToolRoundsField.getValue());
        if (maxToolRounds < 0 || maxToolRounds > 64) {
            statusError = true;
            status = Component.translatable(
                "gui.eca.shader_generator.ai.validation.tool_rounds"
            );
            return;
        }
        List<ShaderAiSettings.Profile> savedProfiles = profiles.stream()
            .map(ProfileDraft::toProfile)
            .toList();
        ShaderAiSettings.AssistantOptions assistant = new ShaderAiSettings.AssistantOptions(
            autoCompile,
            autoApply,
            sendPreviewImages,
            maxToolRounds,
            originalAssistant.maxAutoFixRounds(),
            originalAssistant.storeRemoteConversation()
        );
        ShaderAiSettings saved = new ShaderAiSettings(
            2,
            selected.id,
            savedProfiles,
            assistant
        );
        if (!ShaderAiSettingsCodec.save(saved)) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.ai.save_failed");
            return;
        }
        parent.settingsSaved(saved);
        minecraft.setScreen(parent);
    }

    private Component validate(ProfileDraft profile) {
        if (profile.baseUrl.isBlank()) {
            return Component.translatable("gui.eca.shader_generator.ai.validation.url_required");
        }
        try {
            URI uri = URI.create(profile.baseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) {
                return Component.translatable("gui.eca.shader_generator.ai.validation.url_invalid");
            }
        } catch (IllegalArgumentException exception) {
            return Component.translatable("gui.eca.shader_generator.ai.validation.url_invalid");
        }
        if (profile.model.isBlank()) {
            return Component.translatable("gui.eca.shader_generator.ai.validation.model_required");
        }
        if (profile.apiKey.isBlank() && profile.apiKeyEnv.isBlank()) {
            return Component.translatable("gui.eca.shader_generator.ai.validation.key_required");
        }
        if (profile.timeoutSeconds < 10 || profile.timeoutSeconds > 600) {
            return Component.translatable("gui.eca.shader_generator.ai.validation.timeout");
        }
        return null;
    }

    private int parseTimeout(String value) {
        return parseInteger(value);
    }

    private int parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private void refreshBooleanLabels() {
        autoApplyButton.setMessage(booleanLabel(
            "gui.eca.shader_generator.ai.auto_apply", autoApply
        ));
        autoCompileButton.setMessage(booleanLabel(
            "gui.eca.shader_generator.ai.auto_compile", autoCompile
        ));
        sendPreviewButton.setMessage(booleanLabel(
            "gui.eca.shader_generator.ai.send_preview", sendPreviewImages
        ));
    }

    private Component booleanLabel(String key, boolean enabled) {
        return Component.translatable(
            key,
            Component.translatable(enabled
                ? "gui.eca.shader_generator.ai.enabled"
                : "gui.eca.shader_generator.ai.disabled")
        );
    }

    private Component profileLabel() {
        return Component.literal(profiles.get(selectedProfile).id)
            .append("  " + (selectedProfile + 1) + "/" + profiles.size());
    }

    private Component protocolLabel() {
        return Component.translatable(protocolKey(profiles.get(selectedProfile).protocol));
    }

    private Component apiKeyVisibilityLabel() {
        return Component.translatable(showApiKey
            ? "gui.eca.shader_generator.ai.hide_key"
            : "gui.eca.shader_generator.ai.show_key");
    }

    private String protocolKey(ShaderAiProtocol protocol) {
        return "gui.eca.shader_generator.ai.protocol." + protocol.serializedName();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF111315);
        graphics.fill(left - 8, top - 4, left + dialogWidth + 8, height - 4, 0xFF202225);
        graphics.renderOutline(
            left - 8, top - 4, dialogWidth + 16, height - top, 0xFF60656D
        );
        graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFFFF);
        drawVisibleLabel(graphics, 0, "gui.eca.shader_generator.ai.profile");
        drawVisibleLabel(graphics, 1, "gui.eca.shader_generator.ai.protocol");
        drawVisibleLabel(graphics, 2, "gui.eca.shader_generator.ai.base_url");
        drawVisibleLabel(graphics, 3, "gui.eca.shader_generator.ai.model");
        drawVisibleLabel(graphics, 4, "gui.eca.shader_generator.ai.api_key");
        drawVisibleLabel(graphics, 5, "gui.eca.shader_generator.ai.api_key_env");
        drawVisibleLabel(graphics, 6, "gui.eca.shader_generator.ai.timeout");
        drawVisibleLabel(graphics, 7, "gui.eca.shader_generator.ai.max_tool_rounds");
        drawScrollbar(graphics);
        if (!status.getString().isBlank()) {
            graphics.drawCenteredString(
                font, status, width / 2, footerTop - 13,
                statusError ? 0xFFFF6B6B : 0xFF8FE388
            );
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawVisibleLabel(GuiGraphics graphics, int row, String key) {
        if (row < scrollRow || row >= scrollRow + visibleRows()) return;
        graphics.drawString(
            font,
            Component.translatable(key),
            left + 8,
            rowY(row) + 6,
            0xFFCDD1D7,
            false
        );
    }

    private void drawScrollbar(GuiGraphics graphics) {
        int visibleRows = visibleRows();
        if (visibleRows >= TOTAL_ROWS) return;
        int x = left + dialogWidth - 4;
        int trackHeight = contentBottom - contentTop;
        graphics.fill(x, contentTop, x + 3, contentBottom, 0xFF34383E);
        int thumbHeight = Math.max(12, trackHeight * visibleRows / TOTAL_ROWS);
        int maximum = TOTAL_ROWS - visibleRows;
        int thumbTop = contentTop + (trackHeight - thumbHeight) * scrollRow / maximum;
        graphics.fill(x, thumbTop, x + 3, thumbTop + thumbHeight, 0xFF9DA3AC);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta == 0.0D || visibleRows() >= TOTAL_ROWS) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        int previous = scrollRow;
        scrollRow += delta > 0.0D ? -1 : 1;
        updateScrollWidgets();
        return previous != scrollRow || super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private static final class ProfileDraft {

        private final String id;
        private ShaderAiProtocol protocol;
        private String baseUrl;
        private String apiKey;
        private String apiKeyEnv;
        private String model;
        private final LinkedHashMap<String, String> customHeaders;
        private int timeoutSeconds;

        private ProfileDraft(ShaderAiSettings.Profile source) {
            id = source.id();
            protocol = source.protocol();
            baseUrl = source.baseUrl();
            apiKey = source.apiKey();
            apiKeyEnv = source.apiKeyEnv();
            model = source.model();
            customHeaders = new LinkedHashMap<>(source.customHeaders());
            timeoutSeconds = source.timeoutSeconds();
        }

        private ShaderAiSettings.Profile toProfile() {
            return new ShaderAiSettings.Profile(
                id,
                protocol,
                baseUrl,
                apiKey,
                apiKeyEnv,
                model,
                customHeaders,
                timeoutSeconds
            );
        }
    }

    private record ScrollWidget(AbstractWidget widget, int row) {}
}
