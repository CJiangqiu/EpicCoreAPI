package net.eca.client.gui;

import net.eca.util.bossshow.BossShowEditorState;
import net.eca.util.bossshow.BossShowEffectCue;
import net.eca.util.bossshow.Curve;
import net.eca.util.filter.FilterType;
import net.eca.util.shader_generator.ShaderOutputEffectDefinition;
import net.eca.util.shader_generator.ShaderOutputEffectRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

// 当前播放头上的屏幕效果编辑器；参数文本允许覆盖注册表提供的全部参数。
final class BossShowEffectEditorScreen extends Screen implements BossShowEditorSessionScreen {

    private static final List<String> TYPES = List.of(
        BossShowEffectCue.CAMERA_SHAKE, BossShowEffectCue.SHADER_EFFECT, BossShowEffectCue.FILTER);
    private final Screen parent;
    private final int tick;
    private final List<BossShowEffectCue> cues = new ArrayList<>();
    private int selected;
    private String type = BossShowEffectCue.CAMERA_SHAKE;
    private String effect = "";
    private Curve easing = Curve.EASE_OUT;
    private EditBox durationBox;
    private EditBox fadeInBox;
    private EditBox fadeOutBox;
    private EditBox parametersBox;
    private Button typeButton;
    private Button effectButton;
    private Button easingButton;

    BossShowEffectEditorScreen(Screen parent, int tick) {
        super(Component.translatable("gui.eca.bossshow.effect.title", tick));
        this.parent = parent;
        this.tick = tick;
        cues.addAll(BossShowEditorState.getEffectsAtTick(tick));
        if (!cues.isEmpty()) load(cues.get(0));
    }

    @Override
    protected void init() {
        int center = width / 2;
        int y = height / 2 - 86;
        typeButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleType())
            .bounds(center - 154, y, 100, 20).build());
        effectButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleEffect())
            .bounds(center - 50, y, 204, 20).build());
        durationBox = numericBox(center - 154, y + 30, 96);
        fadeInBox = numericBox(center - 50, y + 30, 96);
        fadeOutBox = numericBox(center + 54, y + 30, 100);
        easingButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            easing = easing.next(); refreshLabels();
        }).bounds(center - 154, y + 60, 150, 20).build());
        parametersBox = new EditBox(font, center, y + 60, 154, 20, Component.empty());
        parametersBox.setMaxLength(1024);
        addRenderableWidget(parametersBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.effect.previous"), b -> select(-1))
            .bounds(center - 154, y + 94, 72, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.effect.next"), b -> select(1))
            .bounds(center - 78, y + 94, 72, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.effect.add"), b -> addCue())
            .bounds(center + 2, y + 94, 72, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.effect.delete"), b -> deleteCue())
            .bounds(center + 78, y + 94, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.effect.save"), b -> saveAndClose())
            .bounds(center - 102, y + 126, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.eca.bossshow.effect.cancel"), b -> onClose())
            .bounds(center + 2, y + 126, 100, 20).build());
        refreshFields();
    }

    private EditBox numericBox(int x, int y, int width) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.empty());
        box.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        addRenderableWidget(box);
        return box;
    }

    private void cycleType() {
        storeCurrent();
        type = TYPES.get((TYPES.indexOf(type) + 1) % TYPES.size());
        effect = defaultEffect(type);
        parametersBox.setValue(defaultParameters(type, effect));
        refreshLabels();
    }

    private void cycleEffect() {
        List<String> effects = effectsFor(type);
        if (effects.isEmpty()) return;
        int index = effects.indexOf(effect);
        effect = effects.get((index + 1 + effects.size()) % effects.size());
        parametersBox.setValue(defaultParameters(type, effect));
        refreshLabels();
    }

    private void select(int delta) {
        if (cues.isEmpty()) return;
        storeCurrent();
        selected = Math.floorMod(selected + delta, cues.size());
        load(cues.get(selected));
        refreshFields();
    }

    private void addCue() {
        storeCurrent();
        BossShowEffectCue cue = new BossShowEffectCue(tick, BossShowEffectCue.CAMERA_SHAKE, "", 20,
            0, 8, Curve.EASE_OUT, defaultParameterMap(BossShowEffectCue.CAMERA_SHAKE, ""));
        cues.add(cue);
        selected = cues.size() - 1;
        load(cue);
        refreshFields();
    }

    private void deleteCue() {
        if (cues.isEmpty()) return;
        cues.remove(selected);
        selected = Math.max(0, Math.min(selected, cues.size() - 1));
        if (cues.isEmpty()) {
            type = BossShowEffectCue.CAMERA_SHAKE;
            effect = "";
            easing = Curve.EASE_OUT;
        } else load(cues.get(selected));
        refreshFields();
    }

    private void storeCurrent() {
        if (cues.isEmpty() || durationBox == null) return;
        cues.set(selected, new BossShowEffectCue(tick, type, effect, integer(durationBox, 20),
            integer(fadeInBox, 0), integer(fadeOutBox, 0), easing, parseParameters(parametersBox.getValue())));
    }

    private void saveAndClose() {
        storeCurrent();
        BossShowEditorState.replaceEffectsAtTick(tick, cues);
        minecraft.setScreen(parent);
    }

    private void load(BossShowEffectCue cue) {
        type = cue.type();
        effect = cue.effect();
        easing = cue.easing();
    }

    private void refreshFields() {
        BossShowEffectCue cue = cues.isEmpty() ? new BossShowEffectCue(tick, type, effect, 20,
            0, 8, easing, defaultParameterMap(type, effect)) : cues.get(selected);
        durationBox.setValue(Integer.toString(cue.durationTicks()));
        fadeInBox.setValue(Integer.toString(cue.fadeInTicks()));
        fadeOutBox.setValue(Integer.toString(cue.fadeOutTicks()));
        parametersBox.setValue(formatParameters(cue.parameters()));
        refreshLabels();
    }

    private void refreshLabels() {
        typeButton.setMessage(Component.translatable("gui.eca.bossshow.effect.type." + type));
        effectButton.setMessage(Component.literal(effect.isEmpty() ? "—" : effect));
        effectButton.active = !BossShowEffectCue.CAMERA_SHAKE.equals(type);
        easingButton.setMessage(Component.translatable(easing.translationKey()));
    }

    private static int integer(EditBox box, int fallback) {
        try { return Integer.parseInt(box.getValue()); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static List<String> effectsFor(String type) {
        if (BossShowEffectCue.SHADER_EFFECT.equals(type)) {
            return ShaderOutputEffectRegistry.all().stream().map(ShaderOutputEffectDefinition::id).toList();
        }
        if (BossShowEffectCue.FILTER.equals(type)) {
            return Arrays.stream(FilterType.values()).map(value -> value.name().toLowerCase(Locale.ROOT)).toList();
        }
        return List.of();
    }

    private static String defaultEffect(String type) {
        List<String> effects = effectsFor(type);
        return effects.isEmpty() ? "" : effects.get(0);
    }

    private static Map<String, Float> defaultParameterMap(String type, String effect) {
        LinkedHashMap<String, Float> values = new LinkedHashMap<>();
        if (BossShowEffectCue.CAMERA_SHAKE.equals(type)) {
            values.put("yaw", 1.0F); values.put("pitch", 0.75F); values.put("roll", 0.4F);
            values.put("frequency", 1.0F); values.put("seed", 0.0F);
        } else if (BossShowEffectCue.SHADER_EFFECT.equals(type)) {
            ShaderOutputEffectDefinition definition = ShaderOutputEffectRegistry.get(effect);
            if (definition != null) definition.parameters().forEach(parameter -> values.put(parameter.key(), parameter.defaultValue()));
        } else if (BossShowEffectCue.FILTER.equals(type)) {
            values.put("strength", 1.0F); values.put("speed", 1.0F);
        }
        return values;
    }

    private static String defaultParameters(String type, String effect) {
        return formatParameters(defaultParameterMap(type, effect));
    }

    private static String formatParameters(Map<String, Float> parameters) {
        return parameters.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(", "));
    }

    private static Map<String, Float> parseParameters(String text) {
        LinkedHashMap<String, Float> result = new LinkedHashMap<>();
        for (String part : text.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) continue;
            try { result.put(pair[0].trim(), Float.parseFloat(pair[1].trim())); }
            catch (NumberFormatException ignored) { }
        }
        return result;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 112, 0xFFFFFFFF);
        int y = height / 2 - 86;
        graphics.drawString(font, Component.translatable("gui.eca.bossshow.effect.duration"), width / 2 - 154, y + 22, 0xFFAAAAAA);
        graphics.drawString(font, Component.translatable("gui.eca.bossshow.effect.fade_in"), width / 2 - 50, y + 22, 0xFFAAAAAA);
        graphics.drawString(font, Component.translatable("gui.eca.bossshow.effect.fade_out"), width / 2 + 54, y + 22, 0xFFAAAAAA);
        graphics.drawString(font, Component.translatable("gui.eca.bossshow.effect.parameters"), width / 2, y + 52, 0xFFAAAAAA);
        graphics.drawCenteredString(font, Component.literal((cues.isEmpty() ? 0 : selected + 1) + "/" + cues.size()),
            width / 2, y + 118, 0xFFAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
