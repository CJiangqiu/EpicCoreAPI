package net.eca.client.gui;

import net.eca.client.render.shader_generator.GeneratedShaderPreview;
import net.eca.client.render.shader_generator.ShaderPreviewDependencyResolver;
import net.eca.client.render.shader_generator.ShaderPreviewRenderer;
import net.eca.client.render.shader_generator.ShaderPreviewTarget;
import net.eca.mixin.MultiLineEditBoxAccessor;
import net.eca.util.EcaLogger;
import net.eca.util.shader_generator.ShaderCompositionProject;
import net.eca.util.shader_generator.ShaderExportBundle;
import net.eca.util.shader_generator.ShaderProject;
import net.eca.util.shader_generator.ShaderProjectCodec;
import net.eca.util.shader_generator.ShaderProjectCodec.ProjectRef;
import net.eca.util.shader_generator.ShaderSourceAssembler;
import net.eca.util.shader_generator.ShaderSourceFile;
import net.eca.util.shader_generator.ai.ShaderAiToolResult;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShaderSourceEditorScreen extends Screen {

    private static final AtomicLong REVISION = new AtomicLong();
    private static final Pattern NAVIGATION_MARKER = Pattern.compile(
        "(?m)^\\s*//\\s*@eca-nav\\s+((?:layer|element)\\s*:\\s*)?(.+?)\\s*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final long COMPILE_DELAY_MS = 300L;
    private static final int MENU_Y = 6;
    private static final int MENU_HEIGHT = 20;
    private static final int CONTENT_TOP = 32;
    private static final int MENU_FILE = 0;
    private static final int MENU_EDIT = 1;
    private static final int MENU_NAVIGATION = 2;
    private static final int MENU_PREVIEW = 3;
    private static final int DROPDOWN_BACKGROUND = 0xFF2D2F34;
    private static final int DROPDOWN_BORDER = 0xFF60656D;
    private static final float DIAGNOSTIC_TEXT_SCALE = 0.75F;

    private final ShaderGeneratorScreen parent;
    private final ShaderCompositionProject project;
    private final ProjectRef reference;
    private final Map<ShaderSourceFile, List<String>> undo = new EnumMap<>(ShaderSourceFile.class);
    private final Map<ShaderSourceFile, List<String>> redo = new EnumMap<>(ShaderSourceFile.class);
    private ShaderSourceFile selectedFile = ShaderSourceFile.FRAGMENT;
    private ShaderPreviewTarget previewTarget = ShaderPreviewTarget.PLANE;
    private MultiLineEditBox editor;
    private GeneratedShaderPreview preview;
    private Component diagnostics = Component.translatable("gui.eca.shader_generator.source_editor.ready");
    private Component navigationLocation;
    private boolean diagnosticsError;
    private boolean loadingText;
    private boolean compilePending = true;
    private long lastEditMs;
    private long lastUndoSnapshotMs;
    private int openDropdown = -1;
    private int dropdownFirstWidgetIndex = -1;
    private int dropdownLastWidgetIndex = -1;
    private int navigationScroll;
    private int diagnosticsScroll;
    private int editorRight;
    private int previewLeft;
    private int previewBottom;
    private int diagnosticsTop;
    private int navigationMenuX;
    private int navigationMenuWidth;

    ShaderSourceEditorScreen(
        ShaderGeneratorScreen parent,
        ShaderCompositionProject project,
        ProjectRef reference
    ) {
        super(Component.translatable("gui.eca.shader_generator.source_editor.title"));
        this.parent = parent;
        this.project = project;
        this.reference = reference;
    }

    @Override
    protected void init() {
        int contentBottom = height - 8;
        editorRight = Math.max(308, (int) (width * 0.58F));
        previewLeft = editorRight + 6;
        int contentHeight = contentBottom - CONTENT_TOP;
        int diagnosticsHeight = Math.max(90, contentHeight / 3);
        diagnosticsTop = contentBottom - diagnosticsHeight;
        previewBottom = diagnosticsTop - 6;

        editor = new MultiLineEditBox(
            font, 8, CONTENT_TOP, editorRight - 12, contentBottom - CONTENT_TOP,
            Component.translatable("gui.eca.shader_generator.source_editor.placeholder"),
            Component.translatable("gui.eca.shader_generator.source_editor.editor")
        );
        editor.setValueListener(this::sourceChanged);
        addRenderableWidget(editor);
        loadSelectedSource();

        Component fileLabel = Component.translatable("gui.eca.shader_generator.menu.file");
        Component editLabel = Component.translatable("gui.eca.shader_generator.menu.edit");
        Component previewLabel = Component.translatable("gui.eca.shader_generator.menu.preview");
        Component compileLabel = Component.translatable("gui.eca.shader_generator.source_editor.compile");
        Component closeLabel = Component.translatable("gui.eca.shader_generator.button.close");
        boolean compact = width < 600;
        int fileWidth = compact ? 48 : menuButtonWidth(fileLabel, 48, 72);
        int editWidth = compact ? 48 : menuButtonWidth(editLabel, 48, 72);
        int previewWidth = compact ? 48 : menuButtonWidth(previewLabel, 54, 82);
        int compileWidth = compact ? 48 : menuButtonWidth(compileLabel, 54, 82);
        int closeWidth = compact ? 48 : menuButtonWidth(closeLabel, 54, 82);

        int menuX = 8;
        addMenuButton(menuX, fileWidth, fileLabel, MENU_FILE);
        menuX += fileWidth + 2;
        addMenuButton(menuX, editWidth, editLabel, MENU_EDIT);
        menuX += editWidth + 2;
        navigationMenuX = menuX;

        int closeX = width - 8 - closeWidth;
        int compileX = closeX - 2 - compileWidth;
        int previewX = compileX - 2 - previewWidth;
        navigationMenuWidth = Math.max(40, previewX - 2 - navigationMenuX);
        addMenuButton(
            navigationMenuX,
            navigationMenuWidth,
            Component.translatable(
                "gui.eca.shader_generator.source_editor.quick_navigation",
                currentNavigationLocation()
            ),
            MENU_NAVIGATION
        );
        addMenuButton(
            previewX,
            previewWidth,
            previewLabel,
            MENU_PREVIEW
        );
        addRenderableWidget(Button.builder(
            compileLabel,
            button -> compile(true)
        ).bounds(compileX, MENU_Y, compileWidth, MENU_HEIGHT).build());
        addRenderableWidget(Button.builder(
            closeLabel,
            button -> onClose()
        ).bounds(closeX, MENU_Y, closeWidth, MENU_HEIGHT).build());

        if (openDropdown >= 0) {
            dropdownFirstWidgetIndex = children().size();
            addDropdownOptions();
            dropdownLastWidgetIndex = children().size() - 1;
        }
        setInitialFocus(editor);
    }

    private int menuButtonWidth(Component label, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, font.width(label) + 24));
    }

    private void addMenuButton(int x, int buttonWidth, Component label, int menu) {
        Component message = label.copy().append(openDropdown == menu ? " ▲" : " ▼");
        addRenderableWidget(Button.builder(message, button -> {
            openDropdown = openDropdown == menu ? -1 : menu;
            rebuildWidgets();
        }).bounds(x, MENU_Y, buttonWidth, MENU_HEIGHT).build());
    }

    private void addDropdownOptions() {
        switch (openDropdown) {
            case MENU_FILE -> addFileDropdown();
            case MENU_EDIT -> addEditDropdown();
            case MENU_NAVIGATION -> addNavigationDropdown();
            case MENU_PREVIEW -> addPreviewDropdown();
            default -> {
            }
        }
    }

    private void addFileDropdown() {
        int y = CONTENT_TOP;
        dropdownOption(8, y, 156,
            Component.translatable("gui.eca.shader_generator.file.save"), this::saveAndCloseMenu);
        dropdownOption(8, y + MENU_HEIGHT, 156,
            Component.translatable("gui.eca.shader_generator.source_editor.export"), this::exportAndCloseMenu);
        dropdownOption(8, y + MENU_HEIGHT * 2, 156,
            Component.translatable("gui.eca.shader_generator.source_editor.visual"), this::onClose);
    }

    private void addEditDropdown() {
        int y = CONTENT_TOP;
        dropdownOption(82, y, 112,
            Component.translatable("gui.eca.shader_generator.edit.undo"), () -> {
                restoreHistory(undo, redo);
                closeDropdown();
            });
        dropdownOption(82, y + MENU_HEIGHT, 112,
            Component.translatable("gui.eca.shader_generator.edit.redo"), () -> {
                restoreHistory(redo, undo);
                closeDropdown();
            });
    }

    private void addNavigationDropdown() {
        List<NavigationEntry> entries = navigationEntries();
        int maxRows = Math.max(1, (height - CONTENT_TOP - 8) / MENU_HEIGHT);
        int maxScroll = Math.max(0, entries.size() - maxRows);
        navigationScroll = Math.max(0, Math.min(navigationScroll, maxScroll));
        int end = Math.min(entries.size(), navigationScroll + maxRows);
        int row = 0;
        for (int index = navigationScroll; index < end; index++) {
            NavigationEntry entry = entries.get(index);
            dropdownOption(
                navigationMenuX,
                CONTENT_TOP + row * MENU_HEIGHT,
                navigationMenuWidth,
                navigationEntryLabel(entry),
                () -> selectNavigation(entry)
            );
            row++;
        }
    }

    private Component navigationEntryLabel(NavigationEntry entry) {
        String indent = "  ".repeat(entry.depth());
        String selected = entry.file() == selectedFile && entry.offset() == 0 ? "✓ " : "";
        return Component.literal(selected + indent).append(entry.label());
    }

    private void addPreviewDropdown() {
        int x = navigationMenuX + navigationMenuWidth + 2;
        int y = CONTENT_TOP;
        int row = 0;
        for (ShaderPreviewTarget target : ShaderPreviewTarget.values()) {
            dropdownOption(x, y + row * MENU_HEIGHT, 150,
                Component.translatable(target.translationKey()), () -> {
                    previewTarget = target;
                    closeDropdown();
                });
            row++;
        }
        dropdownOption(x, y + row * MENU_HEIGHT, 150, overlayLabel(), () -> {
            project.sourceWorkspace().setVisualOverlayEnabled(
                !project.sourceWorkspace().visualOverlayEnabled()
            );
            compilePending = true;
            lastEditMs = 0L;
            closeDropdown();
        });
    }

    private void dropdownOption(int x, int y, int buttonWidth, Component label, Runnable action) {
        addRenderableWidget(Button.builder(label, button -> action.run())
            .bounds(x, y, buttonWidth, MENU_HEIGHT).build());
    }

    private Component overlayLabel() {
        return Component.translatable(
            project.sourceWorkspace().visualOverlayEnabled()
                ? "gui.eca.shader_generator.source_editor.overlay_on"
                : "gui.eca.shader_generator.source_editor.overlay_off"
        );
    }

    private List<NavigationEntry> navigationEntries() {
        List<NavigationEntry> entries = new ArrayList<>();
        for (ShaderSourceFile file : ShaderSourceFile.values()) {
            Component fileLabel = sourceFileLabel(file);
            entries.add(new NavigationEntry(file, fileLabel, 0, 0));
            Matcher matcher = NAVIGATION_MARKER.matcher(project.sourceWorkspace().source(file));
            while (matcher.find()) {
                String type = matcher.group(1);
                String name = matcher.group(2).trim();
                boolean element = type != null && type.toLowerCase().startsWith("element");
                boolean layer = type != null && type.toLowerCase().startsWith("layer");
                Component label = Component.translatable(
                    element
                        ? "gui.eca.shader_generator.source_editor.navigation.element"
                        : layer
                            ? "gui.eca.shader_generator.source_editor.navigation.layer"
                            : "gui.eca.shader_generator.source_editor.navigation.section",
                    name
                );
                entries.add(new NavigationEntry(file, label, element ? 2 : 1, matcher.start()));
            }
        }
        return entries;
    }

    private Component sourceFileLabel(ShaderSourceFile file) {
        return Component.translatable("gui.eca.shader_generator.source_file." + file.serializedName());
    }

    private Component currentNavigationLocation() {
        return navigationLocation == null ? sourceFileLabel(selectedFile) : navigationLocation;
    }

    private void selectNavigation(NavigationEntry entry) {
        selectedFile = entry.file();
        navigationLocation = entry.label();
        openDropdown = -1;
        rebuildWidgets();
        MultiLineEditBoxAccessor accessor = (MultiLineEditBoxAccessor) (Object) editor;
        accessor.eca$getTextField().seekCursor(Whence.ABSOLUTE, entry.offset());
        setFocused(editor);
    }

    private void closeDropdown() {
        openDropdown = -1;
        rebuildWidgets();
    }

    private void saveAndCloseMenu() {
        save();
        closeDropdown();
    }

    private void exportAndCloseMenu() {
        export();
        closeDropdown();
    }

    private void loadSelectedSource() {
        if (editor == null) return;
        loadingText = true;
        editor.setValue(project.sourceWorkspace().source(selectedFile));
        loadingText = false;
        setFocused(editor);
    }

    private void sourceChanged(String value) {
        if (loadingText) return;
        String previous = project.sourceWorkspace().source(selectedFile);
        long now = System.currentTimeMillis();
        if (!previous.equals(value) && now - lastUndoSnapshotMs > 400L) {
            undo.computeIfAbsent(selectedFile, ignored -> new ArrayList<>()).add(previous);
            redo.computeIfAbsent(selectedFile, ignored -> new ArrayList<>()).clear();
            lastUndoSnapshotMs = now;
        }
        project.sourceWorkspace().setSource(selectedFile, value);
        navigationLocation = sourceFileLabel(selectedFile);
        compilePending = true;
        lastEditMs = now;
    }

    private void compile(boolean logResult) {
        compilePending = false;
        diagnosticsScroll = 0;
        try {
            long revision = REVISION.incrementAndGet();
            ShaderProject visualProject = project.toShaderProject(
                "eca_preview", "source/project_" + revision
            );
            GeneratedShaderPreview compiled = GeneratedShaderPreview.compileSource(
                visualProject,
                project.sourceWorkspace(),
                previewTexturePaths(visualProject),
                ShaderPreviewDependencyResolver.resolve(reference, project.sourceWorkspace())
            );
            GeneratedShaderPreview previous = preview;
            preview = compiled;
            if (previous != null) previous.close();
            diagnosticsError = false;
            List<String> warnings = project.sourceWorkspace().previewBindings().warnings();
            diagnostics = compileSuccessDiagnostics(warnings);
            if (logResult) {
                EcaLogger.info(
                    "[ShaderCompile] source compiled project={} resources={} atlases={} warnings={}",
                    reference.id(),
                    project.sourceWorkspace().previewBindings().resources().size(),
                    project.sourceWorkspace().previewBindings().atlases().size(), warnings.size()
                );
                for (String warning : warnings) {
                    EcaLogger.warn("[ShaderCompile] unresolved preview dependency project={} detail={}",
                        reference.id(), warning);
                }
            }
        } catch (Throwable throwable) {
            diagnosticsError = true;
            diagnostics = Component.literal(diagnosticMessage(throwable));
            if (logResult) {
                EcaLogger.error("[ShaderCompile] source failed project={} reason={}",
                    reference.id(), diagnosticMessage(throwable));
            }
        }
    }

    private Component compileSuccessDiagnostics(List<String> warnings) {
        MutableComponent message = Component.translatable(
            "gui.eca.shader_generator.source_editor.compile_success"
        );
        for (String warning : warnings) {
            message.append("\n• ").append(previewWarning(warning));
        }
        return message;
    }

    private Component previewWarning(String warning) {
        Map<String, String> fields = warningFields(warning);
        if (warning.startsWith("unresolved_sampler_bindings|")) {
            return Component.translatable(
                "gui.eca.shader_generator.source_editor.warning.sampler",
                fields.getOrDefault("samplers", "?"),
                fields.getOrDefault("uniforms", "none"),
                fields.getOrDefault("scan_root", "?")
            );
        }
        if (warning.startsWith("dependency_analysis_incomplete|")) {
            return Component.translatable(
                "gui.eca.shader_generator.source_editor.warning.analysis",
                fields.getOrDefault("reason", "?"),
                fields.getOrDefault("scan_root", "?")
            );
        }
        return Component.literal(warning);
    }

    private Map<String, String> warningFields(String warning) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : warning.split("\\|")) {
            int separator = part.indexOf('=');
            if (separator > 0) fields.put(part.substring(0, separator), part.substring(separator + 1));
        }
        return fields;
    }

    private String diagnosticMessage(Throwable throwable) {
        StringBuilder message = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 8) {
            if (depth > 0) message.append("\nCaused by: ");
            message.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message.append(": ").append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return message.toString();
    }

    private Map<String, Path> previewTexturePaths(ShaderProject visualProject) {
        Map<String, Path> paths = new LinkedHashMap<>();
        for (ShaderProject.TextureBinding texture : visualProject.textures()) {
            Path path = ShaderProjectCodec.resolveProjectAsset(reference, texture.projectPath());
            if (path != null) {
                boolean overlay = project.sourceWorkspace().visualOverlayEnabled();
                if (!overlay || project.sourceWorkspace().source(ShaderSourceFile.FRAGMENT)
                        .contains(texture.samplerName())) {
                    paths.put(texture.samplerName(), path);
                }
                if (overlay) {
                    paths.put(ShaderSourceAssembler.overlaySamplerName(texture.samplerName()), path);
                }
            }
        }
        return paths;
    }


    private void save() {
        project.setSourceActive(true);
        boolean saved = ShaderProjectCodec.save(reference.modId(), reference.shaderName(), project);
        diagnosticsScroll = 0;
        diagnosticsError = !saved;
        diagnostics = Component.translatable(saved
            ? "gui.eca.shader_generator.status.saved"
            : "gui.eca.shader_generator.status.save_failed", reference.id());
    }

    private void export() {
        diagnosticsScroll = 0;
        try {
            ShaderProject visualProject = project.toShaderProject(reference.modId(), reference.shaderName());
            ShaderExportBundle bundle = ShaderSourceAssembler.assemble(
                reference.modId(), reference.shaderName(), project.sourceWorkspace(), visualProject
            );
            Path directory = Path.of(
                "config", "eca", "shadergenerator", reference.modId(), reference.shaderName()
            );
            Files.createDirectories(directory);
            for (ShaderExportBundle.File file : bundle.files()) {
                Path target = directory.resolve(Path.of(file.relativePath()).getFileName());
                Files.writeString(target, file.content(), StandardCharsets.UTF_8);
            }
            diagnosticsError = false;
            diagnostics = Component.translatable(
                "gui.eca.shader_generator.status.exported", reference.id()
            );
        } catch (Exception exception) {
            diagnosticsError = true;
            diagnostics = Component.literal(diagnosticMessage(exception));
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (editor != null) editor.tick();
        if (compilePending && System.currentTimeMillis() - lastEditMs >= COMPILE_DELAY_MS) {
            compile(false);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && openDropdown >= 0) {
            closeDropdown();
            return true;
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_Z) {
            restoreHistory(undo, redo);
            return true;
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_Y) {
            restoreHistory(redo, undo);
            return true;
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_S) {
            save();
            return true;
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_ENTER) {
            compile(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void restoreHistory(
        Map<ShaderSourceFile, List<String>> source,
        Map<ShaderSourceFile, List<String>> destination
    ) {
        List<String> history = source.get(selectedFile);
        if (history == null || history.isEmpty()) return;
        destination.computeIfAbsent(selectedFile, ignored -> new ArrayList<>())
            .add(project.sourceWorkspace().source(selectedFile));
        String value = history.remove(history.size() - 1);
        project.sourceWorkspace().setSource(selectedFile, value);
        loadSelectedSource();
        navigationLocation = sourceFileLabel(selectedFile);
        compilePending = true;
        lastEditMs = 0L;
        lastUndoSnapshotMs = System.currentTimeMillis();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && editor != null && !editor.isMouseOver(mouseX, mouseY)) {
            clearEditorSelection();
        }
        if (openDropdown >= 0 && button == 0) {
            List<? extends GuiEventListener> children = children();
            for (int index = dropdownFirstWidgetIndex;
                 index <= dropdownLastWidgetIndex && index < children.size();
                 index++) {
                var listener = children.get(index);
                if (listener.isMouseOver(mouseX, mouseY)) {
                    return listener.mouseClicked(mouseX, mouseY, button);
                }
            }
            openDropdown = -1;
            rebuildWidgets();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void clearEditorSelection() {
        MultiLineEditBoxAccessor accessor = (MultiLineEditBoxAccessor) (Object) editor;
        accessor.eca$getTextField().setSelecting(false);
        accessor.eca$getTextField().seekCursor(Whence.RELATIVE, 0);
        setFocused(null);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (openDropdown == MENU_NAVIGATION
                && mouseX >= navigationMenuX
                && mouseX < navigationMenuX + navigationMenuWidth
                && mouseY >= CONTENT_TOP) {
            navigationScroll = Math.max(0, navigationScroll + (delta < 0.0D ? 1 : -1));
            rebuildWidgets();
            return true;
        }
        if (mouseX >= previewLeft && mouseY >= diagnosticsTop && mouseY < height - 8) {
            int maxScroll = diagnosticsMaxScroll();
            diagnosticsScroll = Math.max(0, Math.min(
                maxScroll, diagnosticsScroll + (delta < 0.0D ? 1 : -1)
            ));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, CONTENT_TOP - 2, 0xFF202225);
        graphics.fill(6, CONTENT_TOP - 2, editorRight, height - 6, 0xFF08090B);
        graphics.fill(previewLeft, CONTENT_TOP, width - 8, previewBottom, 0xFF08090B);
        graphics.fill(previewLeft, diagnosticsTop, width - 8, height - 8, 0xFF111317);
        ShaderPreviewRenderer.render(
            graphics, preview, previewTarget, previewLeft, CONTENT_TOP, width - 8, previewBottom,
            mouseX, mouseY, partialTick
        );
        renderDiagnostics(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 400.0F);
        drawDropdownBackground(graphics);
        renderDropdownWidgets(graphics, mouseX, mouseY, partialTick);
        graphics.pose().popPose();
    }

    private void drawDropdownBackground(GuiGraphics graphics) {
        DropdownBounds bounds = dropdownBounds();
        if (bounds == null) return;
        graphics.fill(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), DROPDOWN_BACKGROUND);
        graphics.renderOutline(
            bounds.left(),
            bounds.top(),
            bounds.right() - bounds.left(),
            bounds.bottom() - bounds.top(),
            DROPDOWN_BORDER
        );
    }

    private void renderDropdownWidgets(
        GuiGraphics graphics,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        if (openDropdown < 0 || dropdownLastWidgetIndex < dropdownFirstWidgetIndex) return;
        List<? extends GuiEventListener> children = children();
        for (int index = dropdownFirstWidgetIndex;
             index <= dropdownLastWidgetIndex && index < children.size();
             index++) {
            if (children.get(index) instanceof AbstractWidget widget) {
                widget.render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    private DropdownBounds dropdownBounds() {
        if (openDropdown < 0 || dropdownLastWidgetIndex < dropdownFirstWidgetIndex) return null;
        List<? extends GuiEventListener> children = children();
        if (dropdownFirstWidgetIndex >= children.size()
                || !(children.get(dropdownFirstWidgetIndex) instanceof AbstractWidget first)) {
            return null;
        }
        int left = first.getX() - 3;
        int top = first.getY() - 2;
        int right = first.getX() + first.getWidth() + 3;
        int bottom = first.getY() + first.getHeight() + 2;
        for (int index = dropdownFirstWidgetIndex + 1;
             index <= dropdownLastWidgetIndex && index < children.size();
             index++) {
            if (children.get(index) instanceof AbstractWidget widget) {
                left = Math.min(left, widget.getX() - 3);
                top = Math.min(top, widget.getY() - 2);
                right = Math.max(right, widget.getX() + widget.getWidth() + 3);
                bottom = Math.max(bottom, widget.getY() + widget.getHeight() + 2);
            }
        }
        return new DropdownBounds(left, top, right, bottom);
    }

    private void renderDiagnostics(GuiGraphics graphics) {
        int textX = previewLeft + 6;
        int textWidth = Math.max(20, width - previewLeft - 22);
        graphics.drawString(
            font,
            Component.translatable("gui.eca.shader_generator.source_editor.diagnostics"),
            textX,
            diagnosticsTop + 6,
            0xFFFFFFFF,
            false
        );
        List<FormattedCharSequence> lines = diagnosticLines(textWidth);
        int visibleRows = diagnosticsVisibleRows();
        int start = Math.min(diagnosticsScroll, Math.max(0, lines.size() - visibleRows));
        int color = diagnosticsError ? 0xFFFF6B6B : 0xFF8FE388;
        graphics.pose().pushPose();
        graphics.pose().translate(textX, diagnosticsTop + 20, 0.0F);
        graphics.pose().scale(DIAGNOSTIC_TEXT_SCALE, DIAGNOSTIC_TEXT_SCALE, 1.0F);
        for (int row = 0; row < visibleRows && start + row < lines.size(); row++) {
            graphics.drawString(
                font,
                lines.get(start + row),
                0,
                row * font.lineHeight,
                color,
                false
            );
        }
        graphics.pose().popPose();
    }

    private int diagnosticsVisibleRows() {
        float availableHeight = height - diagnosticsTop - 32.0F;
        return Math.max(1, (int) (availableHeight / (font.lineHeight * DIAGNOSTIC_TEXT_SCALE)));
    }

    private int diagnosticsMaxScroll() {
        int textWidth = Math.max(20, width - previewLeft - 22);
        return Math.max(0, diagnosticLines(textWidth).size() - diagnosticsVisibleRows());
    }

    private List<FormattedCharSequence> diagnosticLines(int displayWidth) {
        int sourceWidth = Math.max(1, (int) (displayWidth / DIAGNOSTIC_TEXT_SCALE));
        return font.split(diagnostics, sourceWidth);
    }

    ShaderAiToolResult capturePreview() {
        return ShaderPreviewCapture.capture(
            previewLeft, CONTENT_TOP, width - 8, previewBottom
        );
    }

    @Override
    public void onClose() {
        if (preview != null) {
            preview.close();
            preview = null;
        }
        parent.returnFromSourceEditor();
        minecraft.setScreen(parent);
    }

    private record NavigationEntry(
        ShaderSourceFile file,
        Component label,
        int depth,
        int offset
    ) {}

    private record DropdownBounds(int left, int top, int right, int bottom) {}

}
