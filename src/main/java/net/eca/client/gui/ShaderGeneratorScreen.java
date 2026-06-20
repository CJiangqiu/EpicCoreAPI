package net.eca.client.gui;

import net.eca.client.render.shader_generator.GeneratedShaderPreview;
import net.eca.client.render.shader_generator.ShaderPreviewRenderer;
import net.eca.client.render.shader_generator.ShaderPreviewSource;
import net.eca.client.render.shader_generator.ShaderPreviewSourceCatalog;
import net.eca.client.render.shader_generator.ShaderPreviewTarget;
import net.eca.util.shader_generator.ShaderCompositionProject;
import net.eca.util.shader_generator.ShaderExportMode;
import net.eca.util.shader_generator.ShaderLayer;
import net.eca.util.shader_generator.ShaderLayerBlendMode;
import net.eca.util.shader_generator.ShaderModuleDefinition;
import net.eca.util.shader_generator.ShaderModuleInstance;
import net.eca.util.shader_generator.ShaderModuleRegistry;
import net.eca.util.shader_generator.ShaderProject;
import net.eca.util.shader_generator.ShaderProjectCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class ShaderGeneratorScreen extends Screen {

    private static final AtomicLong PREVIEW_REVISION = new AtomicLong();
    private static final int TOP_HEIGHT = 20;
    private static final int LEFT_WIDTH = 110;
    private static final int RIGHT_WIDTH = 210;
    private static final int BOTTOM_HEIGHT = 24;
    private static final int PANEL_COLOR = 0xFF202225;
    private static final int PANEL_DARK = 0xFF17191C;
    private static final int DROPDOWN_BG = 0xFF2D2F34;
    private static final int BORDER_COLOR = 0xFF3C4046;
    private static final int MENU_ITEM_W = 56;
    private static final int MENU_ITEM_H = 16;
    private static final int MENU_Y = 2;
    private static final int MAX_UNDO = 50;
    private static int unnamedCounter;

    private final ShaderCompositionProject project = new ShaderCompositionProject();
    private String projectName;
    private final List<ShaderCompositionProject> undoStack = new ArrayList<>();
    private final List<ShaderCompositionProject> redoStack = new ArrayList<>();
    private List<ShaderPreviewSource> registeredSources = List.of();
    private GeneratedShaderPreview generatedPreview;
    private ShaderPreviewTarget previewTarget = ShaderPreviewTarget.PLANE;
    private int selectedLayerIndex = -1;
    private int selectedElementIndex = -1;
    private int sourceIndex;
    /* 下拉菜单: -1=无, 0=文件, 1=编辑, 2=视图, 3=导出 */
    private int openDropdown = -1;
    private int dropdownFirstWidgetIndex = -1;
    private int dropdownLastWidgetIndex = -1;
    private Component status = Component.translatable("gui.eca.shader_generator.status.ready");
    private boolean statusError;
    private boolean dirty = true;

    private ShaderGeneratorScreen() {
        super(Component.translatable("gui.eca.shader_generator.title"));
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new ShaderGeneratorScreen()));
    }

    /* ---------- undo / redo ---------- */

    private void pushUndo() {
        undoStack.add(project.deepCopy());
        if (undoStack.size() > MAX_UNDO) {
            undoStack.remove(0);
        }
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.add(project.deepCopy());
        ShaderCompositionProject previous = undoStack.remove(undoStack.size() - 1);
        project.copyStateFrom(previous);
        clampSelection();
        markDirty("gui.eca.shader_generator.status.undone");
        rebuildWidgets();
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.add(project.deepCopy());
        ShaderCompositionProject next = redoStack.remove(redoStack.size() - 1);
        project.copyStateFrom(next);
        clampSelection();
        markDirty("gui.eca.shader_generator.status.redone");
        rebuildWidgets();
    }

    /* 确保 restore 后 selectedIndex 不越界 */
    private void clampSelection() {
        List<ShaderLayer> layers = project.layers();
        if (selectedLayerIndex >= layers.size()) {
            selectedLayerIndex = layers.isEmpty() ? -1 : layers.size() - 1;
        }
        ShaderLayer layer = selectedLayer();
        if (layer != null && selectedElementIndex >= layer.elements().size()) {
            selectedElementIndex = layer.elements().isEmpty() ? -1 : layer.elements().size() - 1;
        }
    }

    /* ---------- init ---------- */

    @Override
    protected void init() {
        registeredSources = ShaderPreviewSourceCatalog.loadRegisteredSources();
        addMenuBar();
        addElementPanel();
        addLayerPanel();
        if (generatedPreview == null) {
            compileCurrentProject();
        }
    }

    /* ---------- menu bar ---------- */

    private void addMenuBar() {
        dropdownFirstWidgetIndex = -1;
        dropdownLastWidgetIndex = -1;

        int x = 6;
        x = addMenuButton(x, "gui.eca.shader_generator.menu.file", 0);
        x = addMenuButton(x, "gui.eca.shader_generator.menu.edit", 1);
        x = addMenuButton(x, "gui.eca.shader_generator.menu.view", 2);

        /* 项目名 */
        String name = projectName != null ? projectName : "";
        int nameX = this.width - 164;
        int nameW = 112;
        addRenderableWidget(Button.builder(
            Component.literal(name.isEmpty() ? "" : name),
            btn -> {}
        ).bounds(nameX, MENU_Y, nameW, MENU_ITEM_H).build());

        /* 关闭 */
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.close"),
            button -> onClose()
        ).bounds(this.width - 48, MENU_Y, 42, MENU_ITEM_H).build());

        /* 下拉菜单选项 */
        if (openDropdown >= 0) {
            dropdownFirstWidgetIndex = children().size();
            addDropdownOptions(openDropdown);
            dropdownLastWidgetIndex = children().size() - 1;
        }
    }

    private int addMenuButton(int x, String key, int dropdownId) {
        boolean open = openDropdown == dropdownId;
        Component label = Component.translatable(key).copy().append(open ? " ▲" : " ▼");
        addRenderableWidget(Button.builder(label, btn -> {
            openDropdown = (openDropdown == dropdownId) ? -1 : dropdownId;
            rebuildWidgets();
        }).bounds(x, MENU_Y, MENU_ITEM_W, MENU_ITEM_H).build());
        return x + MENU_ITEM_W + 2;
    }

    private void addDropdownOptions(int dropdownId) {
        switch (dropdownId) {
            case 0 -> addFileDropdown();
            case 1 -> addEditDropdown();
            case 2 -> addViewDropdown();
        }
    }

    /* 文件: 新建 / 保存 / 打开... / 编译 / 导出 */
    private void addFileDropdown() {
        int mx = 6;
        int y = MENU_Y + MENU_ITEM_H + 2;
        int w = 140;
        int row = 0;

        dropdownOption(mx, y + row * MENU_ITEM_H, w,
            Component.translatable("gui.eca.shader_generator.file.new"), () -> {
                pushUndo();
                ShaderCompositionProject fresh = new ShaderCompositionProject();
                project.copyStateFrom(fresh);
                projectName = null;
                selectedLayerIndex = 0;
                selectedElementIndex = -1;
                openDropdown = -1;
                markDirty("gui.eca.shader_generator.status.new_project");
                rebuildWidgets();
            });
        row++;

        dropdownOption(mx, y + row * MENU_ITEM_H, w,
            Component.translatable("gui.eca.shader_generator.file.save"), () -> {
                boolean ok = ShaderProjectCodec.save(currentProjectName(), project);
                openDropdown = -1;
                status = ok
                    ? Component.translatable("gui.eca.shader_generator.status.saved", currentProjectName())
                    : Component.translatable("gui.eca.shader_generator.status.save_failed");
                statusError = !ok;
                rebuildWidgets();
            });
        row++;

        /* saved projects */
        List<String> saved = ShaderProjectCodec.listSavedProjects();
        if (!saved.isEmpty()) {
            for (String name : saved) {
                if (row > 18) break;
                String label = (name.equals(projectName))
                    ? "▸ " + name
                    : "  " + name;
                dropdownOption(mx, y + row * MENU_ITEM_H, w, Component.literal(label), () -> {
                    pushUndo();
                    boolean ok = ShaderProjectCodec.load(name, project);
                    if (ok) {
                        projectName = name;
                        selectedLayerIndex = 0;
                        selectedElementIndex = -1;
                        openDropdown = -1;
                        statusError = false;
                        status = Component.translatable("gui.eca.shader_generator.status.loaded", name);
                    } else {
                        openDropdown = -1;
                        statusError = true;
                        status = Component.translatable("gui.eca.shader_generator.status.load_failed", name);
                    }
                    compileCurrentProject();
                    rebuildWidgets();
                });
                row++;
            }
        }

        row++;
        Component compileLabel = Component.translatable(dirty
            ? "gui.eca.shader_generator.button.compile_dirty"
            : "gui.eca.shader_generator.button.compile");
        dropdownOption(mx, y + row * MENU_ITEM_H, w, compileLabel, () -> {
            openDropdown = -1;
            compileCurrentProject();
            rebuildWidgets();
        });
        row++;

        Component exportLabel = Component.translatable("gui.eca.shader_generator.file.export_as",
            exportModeName(project.exportMode()));
        dropdownOption(mx, y + row * MENU_ITEM_H, w, exportLabel, () -> {
            pushUndo();
            ShaderExportMode[] modes = ShaderExportMode.values();
            project.setExportMode(modes[(project.exportMode().ordinal() + 1) % modes.length]);
            markDirty("gui.eca.shader_generator.status.export_mode_changed");
            rebuildWidgets();
        });
    }

    private String currentProjectName() {
        if (projectName == null) {
            unnamedCounter++;
            projectName = "Untitled-" + unnamedCounter;
        }
        return projectName;
    }

    /* 编辑 */
    private void addEditDropdown() {
        int mx = 6 + MENU_ITEM_W + 2;
        int y = MENU_Y + MENU_ITEM_H + 2;
        int w = 80;

        dropdownOption(mx, y, w, Component.translatable("gui.eca.shader_generator.edit.undo"), () -> {
            openDropdown = -1;
            undo();
        });
        dropdownOption(mx, y + MENU_ITEM_H, w, Component.translatable("gui.eca.shader_generator.edit.redo"), () -> {
            openDropdown = -1;
            redo();
        });
    }

    /* 视图 */
    private void addViewDropdown() {
        int mx = 6 + (MENU_ITEM_W + 2) * 2;
        int y = MENU_Y + MENU_ITEM_H + 2;
        int w = 90;

        for (int i = 0; i < ShaderPreviewTarget.values().length; i++) {
            ShaderPreviewTarget target = ShaderPreviewTarget.values()[i];
            dropdownOption(mx, y + i * MENU_ITEM_H, w,
                Component.translatable(target.translationKey()), () -> {
                    previewTarget = target;
                    openDropdown = -1;
                    rebuildWidgets();
                });
        }
    }

    private void dropdownOption(int x, int y, int w, Component label, Runnable action) {
        addRenderableWidget(Button.builder(label, btn -> action.run())
            .bounds(x, y, w, MENU_ITEM_H).build());
    }

    /* 点击菜单外关闭下拉 */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (openDropdown >= 0 && button == 0) {
            boolean hit = false;
            List<? extends net.minecraft.client.gui.components.events.GuiEventListener> kids = children();
            for (int i = dropdownFirstWidgetIndex;
                 i <= dropdownLastWidgetIndex && i < kids.size();
                 i++) {
                if (kids.get(i).isMouseOver(mx, my)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                openDropdown = -1;
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    /* ---------- left: element library ---------- */

    private void addElementPanel() {
        int x = 8;
        int y = TOP_HEIGHT + 16;
        int w = LEFT_WIDTH - 16;
        List<ShaderModuleDefinition> definitions = ShaderModuleRegistry.all();

        for (int i = 0; i < definitions.size(); i++) {
            ShaderModuleDefinition def = definitions.get(i);
            if (y > this.height - BOTTOM_HEIGHT - 52) {
                break;
            }
            addRenderableWidget(Button.builder(
                Component.translatable(def.displayName()),
                btn -> {
                    pushUndo();
                    ShaderLayer target = selectedLayer();
                    if (target == null) {
                        target = project.addLayer();
                        selectedLayerIndex = project.layers().size() - 1;
                    }
                    target.addElement(def);
                    selectedElementIndex = target.elements().size() - 1;
                    markDirty("gui.eca.shader_generator.status.element_added",
                        Component.translatable(def.displayName()));
                    rebuildWidgets();
                }
            ).bounds(x, y, w, 18).build());
            y += 22;
        }

        int addLayerY = this.height - BOTTOM_HEIGHT - 42;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.new_layer"),
            btn -> {
                pushUndo();
                project.addLayer();
                selectedLayerIndex = project.layers().size() - 1;
                selectedElementIndex = -1;
                markDirty("gui.eca.shader_generator.status.layer_added");
                rebuildWidgets();
            }
        ).bounds(x, addLayerY, w, 18).build());
    }

    /* ---------- right: layer stack + properties ---------- */

    private void addLayerPanel() {
        int px = this.width - RIGHT_WIDTH + 8;
        int y = TOP_HEIGHT + 20;
        int lw = RIGHT_WIDTH - 16;
        List<ShaderLayer> layers = project.layers();

        int available = Math.max(1, (this.height - BOTTOM_HEIGHT - y - 220) / 24);
        for (int di = 0; di < layers.size() && di < available; di++) {
            int li = layers.size() - 1 - di;
            addLayerRow(px, y + di * 24, lw, layers.get(li), li);
        }

        int cy = y + Math.min(layers.size(), available) * 24 + 4;
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.new_layer"),
            btn -> {
                pushUndo();
                project.addLayer();
                selectedLayerIndex = project.layers().size() - 1;
                selectedElementIndex = -1;
                markDirty("gui.eca.shader_generator.status.layer_added");
                rebuildWidgets();
            }
        ).bounds(px, cy, 52, 16).build());

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.add_element"),
            btn -> {
                ShaderLayer sl = selectedLayer();
                if (sl != null) {
                    pushUndo();
                    List<ShaderModuleDefinition> defs = ShaderModuleRegistry.all();
                    int nd = (selectedElementIndex + 1) % defs.size();
                    sl.addElement(defs.get(nd));
                    selectedElementIndex = sl.elements().size() - 1;
                    markDirty("gui.eca.shader_generator.status.element_added",
                        Component.translatable(defs.get(nd).displayName()));
                    rebuildWidgets();
                }
            }
        ).bounds(px + 56, cy, 52, 16).build());

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.move_up"),
            btn -> moveSelectedLayer(-1)
        ).bounds(px + 112, cy, 24, 16).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.move_down"),
            btn -> moveSelectedLayer(1)
        ).bounds(px + 140, cy, 24, 16).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.delete"),
            btn -> removeSelectedLayer()
        ).bounds(px + 168, cy, 24, 16).build());

        /* elements of selected layer */
        int ey = cy + 24;
        ShaderLayer sel = selectedLayer();
        if (sel != null && !sel.elements().isEmpty()) {
            for (int ei = 0; ei < sel.elements().size(); ei++) {
                elementRow(px, ey + ei * 18, lw, sel.elements().get(ei), ei);
            }
            ey += Math.min(sel.elements().size(), 6) * 18 + 4;
        }
        addPropertyControls(px, ey + 4);
    }

    private void addLayerRow(int x, int y, int w, ShaderLayer layer, int layerIdx) {
        addRenderableWidget(Button.builder(
            Component.literal(layer.visible() ? "☉" : "○"),
            btn -> {
                pushUndo();
                layer.setVisible(!layer.visible());
                markDirty("gui.eca.shader_generator.status.layer_visibility_changed");
                rebuildWidgets();
            }
        ).bounds(x, y, 16, 18).build());

        addRenderableWidget(Button.builder(
            Component.literal(truncatedName(layer.name(), 8)),
            btn -> {
                selectedLayerIndex = layerIdx;
                selectedElementIndex = -1;
                rebuildWidgets();
            }
        ).bounds(x + 18, y, 44, 18).build());

        addRenderableWidget(Button.builder(
            Component.literal(blendModeShortLabel(layer.blendMode())),
            btn -> {
                pushUndo();
                ShaderLayerBlendMode[] modes = ShaderLayerBlendMode.values();
                layer.setBlendMode(modes[(layer.blendMode().ordinal() + 1) % modes.length]);
                markDirty("gui.eca.shader_generator.status.blend_mode_changed");
                rebuildWidgets();
            }
        ).bounds(x + 64, y, 40, 18).build());

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.decrease"),
            btn -> {
                pushUndo();
                layer.setOpacity(layer.opacity() - 0.05F);
                markDirty("gui.eca.shader_generator.status.opacity_changed");
                rebuildWidgets();
            }
        ).bounds(x + 106, y, 18, 18).build());

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.layer.opacity",
                Math.round(layer.opacity() * 100)),
            ignored -> {}
        ).bounds(x + 126, y, 38, 18).build());

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.increase"),
            btn -> {
                pushUndo();
                layer.setOpacity(layer.opacity() + 0.05F);
                markDirty("gui.eca.shader_generator.status.opacity_changed");
                rebuildWidgets();
            }
        ).bounds(x + 166, y, 18, 18).build());
    }

    private void elementRow(int x, int y, int w, ShaderModuleInstance el, int elIdx) {
        Component name = Component.translatable(el.definition().displayName());
        if (!el.enabled()) {
            name = name.copy().append(" [-]");
        }
        addRenderableWidget(Button.builder(name, btn -> {
            selectedElementIndex = elIdx;
            rebuildWidgets();
        }).bounds(x, y, w - 48, 16).build());

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.toggle"),
            btn -> {
                pushUndo();
                el.setEnabled(!el.enabled());
                markDirty("gui.eca.shader_generator.status.element_toggled");
                rebuildWidgets();
            }
        ).bounds(x + w - 44, y, 20, 16).build());

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.delete"),
            btn -> {
                ShaderLayer sl = selectedLayer();
                if (sl != null) {
                    pushUndo();
                    sl.removeElement(elIdx);
                    if (selectedElementIndex >= sl.elements().size()) {
                        selectedElementIndex = Math.max(-1, sl.elements().size() - 1);
                    }
                    markDirty("gui.eca.shader_generator.status.element_removed");
                    rebuildWidgets();
                }
            }
        ).bounds(x + w - 22, y, 20, 16).build());
    }

    private void addPropertyControls(int x, int baseY) {
        ShaderModuleInstance el = selectedElement();
        if (el == null) {
            return;
        }
        int y = baseY;
        int vx = x + RIGHT_WIDTH - 96;
        for (ShaderModuleDefinition.Parameter param : el.definition().parameters()) {
            if (y > this.height - BOTTOM_HEIGHT - 24) {
                break;
            }
            float cur = el.value(param.key());
            addRenderableWidget(Button.builder(
                Component.translatable("gui.eca.shader_generator.button.decrease"),
                btn -> adjustParameter(el, param, -param.step())
            ).bounds(vx, y, 18, 16).build());
            addRenderableWidget(Button.builder(
                Component.literal(String.format(Locale.ROOT, "%.2f", cur)),
                ignored -> {}
            ).bounds(vx + 20, y, 38, 16).build());
            addRenderableWidget(Button.builder(
                Component.translatable("gui.eca.shader_generator.button.increase"),
                btn -> adjustParameter(el, param, param.step())
            ).bounds(vx + 60, y, 18, 16).build());
            y += 20;
        }
    }

    /* ---------- actions ---------- */

    private void adjustParameter(ShaderModuleInstance el, ShaderModuleDefinition.Parameter param, float delta) {
        pushUndo();
        el.setValue(param.key(), el.value(param.key()) + delta);
        markDirty("gui.eca.shader_generator.status.parameter_changed",
            Component.translatable(param.displayName()));
        rebuildWidgets();
    }

    private void moveSelectedLayer(int offset) {
        if (selectedLayerIndex < 0) {
            return;
        }
        int target = selectedLayerIndex + offset;
        if (target < 0 || target >= project.layers().size()) {
            return;
        }
        pushUndo();
        project.moveLayer(selectedLayerIndex, offset);
        selectedLayerIndex = target;
        markDirty("gui.eca.shader_generator.status.layer_reordered");
        rebuildWidgets();
    }

    private void removeSelectedLayer() {
        if (selectedLayerIndex < 0 || project.layers().size() <= 1) {
            return;
        }
        pushUndo();
        project.removeLayer(selectedLayerIndex);
        selectedLayerIndex = Math.max(0, Math.min(selectedLayerIndex, project.layers().size() - 1));
        selectedElementIndex = -1;
        markDirty("gui.eca.shader_generator.status.layer_removed");
        rebuildWidgets();
    }

    private void compileCurrentProject() {
        long rev = PREVIEW_REVISION.incrementAndGet();
        ShaderProject sp = project.toShaderProject("eca_preview", "generated/project_" + rev);
        try {
            GeneratedShaderPreview compiled = GeneratedShaderPreview.compile(sp, project.exportMode());
            GeneratedShaderPreview prev = generatedPreview;
            generatedPreview = compiled;
            if (prev != null) {
                prev.close();
            }
            sourceIndex = 0;
            dirty = false;
            statusError = false;
            status = Component.translatable("gui.eca.shader_generator.status.compiled", rev);
        } catch (Throwable t) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.compile_failed", conciseMessage(t));
        }
    }

    /* ---------- helpers ---------- */

    private ShaderLayer selectedLayer() {
        List<ShaderLayer> layers = project.layers();
        if (selectedLayerIndex < 0 || selectedLayerIndex >= layers.size()) {
            return null;
        }
        return layers.get(selectedLayerIndex);
    }

    private ShaderModuleInstance selectedElement() {
        ShaderLayer layer = selectedLayer();
        if (layer == null || selectedElementIndex < 0 || selectedElementIndex >= layer.elements().size()) {
            return null;
        }
        return layer.elements().get(selectedElementIndex);
    }

    private ShaderPreviewSource activeSource() {
        if (sourceIndex == 0) {
            return generatedPreview;
        }
        int ri = sourceIndex - 1;
        if (ri < 0 || ri >= registeredSources.size()) {
            return null;
        }
        return registeredSources.get(ri);
    }

    private void markDirty(String key, Object... args) {
        dirty = true;
        statusError = false;
        status = Component.translatable(key, args);
    }

    private static String truncatedName(String name, int max) {
        return name.length() <= max ? name : name.substring(0, max - 1) + "…";
    }

    private static String blendModeShortLabel(ShaderLayerBlendMode mode) {
        return switch (mode) {
            case NORMAL -> "Norm";
            case MULTIPLY -> "Mult";
            case SCREEN -> "Scrn";
            case OVERLAY -> "Over";
            case ADD -> "Add";
            case SUBTRACT -> "Sub";
            case DARKEN -> "Drk";
            case LIGHTEN -> "Lit";
        };
    }

    private static Component exportModeName(ShaderExportMode mode) {
        return Component.translatable(
            "gui.eca.shader_generator.export_mode." + mode.name().toLowerCase(Locale.ROOT));
    }

    private static String conciseMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return msg.replace('\n', ' ').replace('\r', ' ');
    }

    /* ---------- render ---------- */

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        /* 1. 背景 + 面板 */
        g.fill(0, 0, this.width, this.height, 0xFF111315);
        g.fill(0, 0, this.width, TOP_HEIGHT, PANEL_DARK);
        g.fill(0, TOP_HEIGHT, LEFT_WIDTH, this.height - BOTTOM_HEIGHT, PANEL_COLOR);
        g.fill(this.width - RIGHT_WIDTH, TOP_HEIGHT, this.width, this.height - BOTTOM_HEIGHT, PANEL_COLOR);
        g.fill(0, this.height - BOTTOM_HEIGHT, this.width, this.height, PANEL_DARK);

        /* 2. 预览区域 */
        int al = LEFT_WIDTH + 8;
        int at = TOP_HEIGHT + 8;
        int ar = this.width - RIGHT_WIDTH - 8;
        int ab = this.height - BOTTOM_HEIGHT - 8;
        int ps = Math.max(32, Math.min(ar - al, ab - at));
        int pl = al + (ar - al - ps) / 2;
        int pt2 = at + (ab - at - ps) / 2;
        int pr = pl + ps;
        int pb = pt2 + ps;

        g.fill(pl - 1, pt2 - 1, pr + 1, pb + 1, BORDER_COLOR);
        g.fill(pl, pt2, pr, pb, 0xFF08090B);
        ShaderPreviewRenderer.render(g, activeSource(), previewTarget,
            pl, pt2, pr, pb, mx, my, pt);

        /* 3. 标签 + 文本 */
        g.drawString(this.font, Component.translatable("gui.eca.shader_generator.panel.elements"),
            8, TOP_HEIGHT + 4, 0xFFBFC4CC, false);
        g.drawString(this.font, Component.translatable("gui.eca.shader_generator.panel.layers"),
            this.width - RIGHT_WIDTH + 8, TOP_HEIGHT + 4, 0xFFBFC4CC, false);

        ShaderModuleInstance el = selectedElement();
        int px = this.width - RIGHT_WIDTH + 8;
        if (el != null) {
            g.drawString(this.font, Component.translatable(el.definition().displayName()),
                px, this.height - BOTTOM_HEIGHT - 210, 0xFFFFFFFF, false);
            int py = this.height - BOTTOM_HEIGHT - 190;
            for (ShaderModuleDefinition.Parameter param : el.definition().parameters()) {
                if (py > this.height - BOTTOM_HEIGHT - 24) {
                    break;
                }
                Component label = Component.translatable("gui.eca.shader_generator.parameter.value",
                    Component.translatable(param.displayName()),
                    String.format(Locale.ROOT, "%.2f", el.value(param.key())));
                g.drawString(this.font, label, px, py, 0xFFC7CBD1, false);
                py += 20;
            }
        } else {
            g.drawString(this.font, Component.translatable("gui.eca.shader_generator.properties.none"),
                px, this.height - BOTTOM_HEIGHT - 210, 0xFF7D838C, false);
        }

        /* 4. 状态栏 */
        String sl = this.font.plainSubstrByWidth(status.getString(), this.width - 16);
        g.drawString(this.font, sl, 8, this.height - BOTTOM_HEIGHT + 8,
            statusError ? 0xFFFF6B6B : 0xFFB5BBC4, false);

        /* 5. 下拉菜单背景 — 最顶层覆盖所有内容 */
        drawDropdownBackground(g);

        /* 6. 控件（含下拉选项按钮） */
        super.render(g, mx, my, pt);
    }

    private void drawDropdownBackground(GuiGraphics g) {
        if (openDropdown < 0 || dropdownLastWidgetIndex < dropdownFirstWidgetIndex) {
            return;
        }
        List<? extends net.minecraft.client.gui.components.events.GuiEventListener> kids = children();
        if (dropdownFirstWidgetIndex >= kids.size()) {
            return;
        }
        var first = kids.get(dropdownFirstWidgetIndex);
        if (!(first instanceof net.minecraft.client.gui.components.AbstractWidget fw)) {
            return;
        }
        int count = dropdownLastWidgetIndex - dropdownFirstWidgetIndex + 1;
        int dt = fw.getY() - 2;
        int dl = fw.getX() - 4;
        int dh = count * MENU_ITEM_H + 4;
        int dr = dl + 142;
        int db = dt + dh;
        g.fill(dl, dt, dr, db, DROPDOWN_BG);
        g.fill(dl, dt, dr, dt + 1, BORDER_COLOR);
        g.fill(dl, db - 1, dr, db, BORDER_COLOR);
        g.fill(dl, dt, dl + 1, db, BORDER_COLOR);
        g.fill(dr - 1, dt, dr, db, BORDER_COLOR);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (generatedPreview != null) {
            generatedPreview.close();
            generatedPreview = null;
        }
        super.removed();
    }
}
