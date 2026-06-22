package net.eca.client.gui;

import net.eca.client.render.shader_generator.GeneratedShaderPreview;
import net.eca.client.render.shader_generator.ShaderPreviewRenderer;
import net.eca.client.render.shader_generator.ShaderPreviewSource;
import net.eca.client.render.shader_generator.ShaderPreviewSourceCatalog;
import net.eca.client.render.shader_generator.ShaderPreviewTarget;
import net.eca.util.shader_generator.ShaderCompositionProject;
import net.eca.util.shader_generator.ShaderLayer;
import net.eca.util.shader_generator.ShaderLayerBlendMode;
import net.eca.util.shader_generator.ShaderModuleDefinition;
import net.eca.util.shader_generator.ShaderModuleInstance;
import net.eca.util.shader_generator.ShaderModuleRegistry;
import net.eca.util.shader_generator.ShaderProject;
import net.eca.util.shader_generator.ShaderProjectCodec;
import net.eca.util.shader_generator.ShaderProjectCodec.ProjectRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public final class ShaderGeneratorScreen extends Screen {

    private static final AtomicLong PREVIEW_REVISION = new AtomicLong();
    private static final int TOP_HEIGHT = 40;
    private static final int LEFT_WIDTH = 0;
    private static final int RIGHT_WIDTH = 210;
    private static final int BOTTOM_HEIGHT = 0;
    private static final int PANEL_COLOR = 0xFF202225;
    private static final int PANEL_DARK = 0xFF17191C;
    private static final int DROPDOWN_BG = 0xFF2D2F34;
    private static final int BORDER_COLOR = 0xFF3C4046;
    private static final int MENU_ITEM_H = 16;
    private static final int MENU_Y = 22;
    private static final int PROJECT_ROW_Y = 2;
    private static final int MAX_UNDO = 50;
    private static final int LAYER_ROW_HEIGHT = 22;
    private static final int[] LAYER_COLORS = {
        0xFF4D8DFF,
        0xFF9B6DFF,
        0xFFFF6F91,
        0xFFFFA94D,
        0xFF52C878,
        0xFF3BC9DB
    };

    private final ShaderCompositionProject project = new ShaderCompositionProject();
    private String projectModId;
    private String projectShaderName;
    private final List<ShaderCompositionProject> undoStack = new ArrayList<>();
    private final List<ShaderCompositionProject> redoStack = new ArrayList<>();
    private final List<LayerRowVisual> visibleLayerRows = new ArrayList<>();
    private final List<ElementRowVisual> visibleElementRows = new ArrayList<>();
    private List<ShaderPreviewSource> registeredSources = List.of();
    private GeneratedShaderPreview generatedPreview;
    private ShaderPreviewTarget previewTarget = ShaderPreviewTarget.PLANE;
    private int selectedLayerIndex = -1;
    private int selectedElementIndex = -1;
    private int sourceIndex;
    private int layerScroll;
    private int visibleLayerRowCount = 1;
    private int layerListTop;
    private int layerListBottom;
    private int elementScroll;
    private int visibleElementRowCount = 1;
    private int elementListTop;
    private int elementListBottom;
    private int propertyControlsY = -1;
    private boolean previewCompilePending;
    private int editingLayerIndex = -1;
    private EditBox layerNameField;
    /* 下拉菜单: -1=无, 0=文件, 1=编辑, 2=图层, 3=预览视图, 4=效果 */
    private int openDropdown = -1;
    private int dropdownFirstWidgetIndex = -1;
    private int dropdownLastWidgetIndex = -1;
    private ShaderModuleDefinition.Category expandedEffectCategory;
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
        addLayerPanel();
        addMenuBar();
        if (generatedPreview == null) {
            compileCurrentProject();
        }
    }

    /* ---------- menu bar ---------- */

    private void addMenuBar() {
        dropdownFirstWidgetIndex = -1;
        dropdownLastWidgetIndex = -1;

        int x = 6;
        x = addMenuButton(x, "gui.eca.shader_generator.menu.file", 0, 56);
        x = addMenuButton(x, "gui.eca.shader_generator.menu.edit", 1, 56);
        x = addMenuButton(x, "gui.eca.shader_generator.menu.layer", 2, 56);
        x = addMenuButton(x, "gui.eca.shader_generator.menu.effects", 4, 88);
        addMenuButton(x, "gui.eca.shader_generator.menu.preview_view", 3, 76);

        Component compileLabel = Component.translatable(dirty
            ? "gui.eca.shader_generator.button.compile_dirty"
            : "gui.eca.shader_generator.button.compile");
        if (statusError) {
            compileLabel = compileLabel.copy().append(" !");
        }
        Button compileButton = Button.builder(
            compileLabel,
            button -> compileCurrentProject()
        ).bounds(this.width - 102, PROJECT_ROW_Y, 48, MENU_ITEM_H).build();
        if (statusError) {
            compileButton.setTooltip(Tooltip.create(status));
        }
        addRenderableWidget(compileButton);

        /* 关闭 */
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.close"),
            button -> onClose()
        ).bounds(this.width - 48, PROJECT_ROW_Y, 42, MENU_ITEM_H).build());

        /* 下拉菜单选项 */
        if (openDropdown >= 0) {
            dropdownFirstWidgetIndex = children().size();
            addDropdownOptions(openDropdown);
            dropdownLastWidgetIndex = children().size() - 1;
        }
    }

    private int addMenuButton(int x, String key, int dropdownId, int width) {
        boolean open = openDropdown == dropdownId;
        Component label = Component.translatable(key).copy().append(open ? " ▲" : " ▼");
        addRenderableWidget(Button.builder(label, btn -> {
            openDropdown = (openDropdown == dropdownId) ? -1 : dropdownId;
            rebuildWidgets();
        }).bounds(x, MENU_Y, width, MENU_ITEM_H).build());
        return x + width + 2;
    }

    private int addActionButton(int x, String key, int width, Runnable action) {
        addRenderableWidget(Button.builder(Component.translatable(key), button -> action.run())
            .bounds(x, MENU_Y, width, MENU_ITEM_H).build());
        return x + width + 2;
    }

    private void addDropdownOptions(int dropdownId) {
        switch (dropdownId) {
            case 0 -> addFileDropdown();
            case 1 -> addEditDropdown();
            case 2 -> addLayerDropdown();
            case 3 -> addViewDropdown();
            case 4 -> addEffectDropdown();
        }
    }

    /* 文件菜单同时管理可编辑工程和最新生成的五文件。 */
    private void addFileDropdown() {
        int mx = 6;
        int y = MENU_Y + MENU_ITEM_H + 2;
        int w = 176;
        int row = 0;

        dropdownOption(mx, y + row * MENU_ITEM_H, w,
            Component.translatable("gui.eca.shader_generator.file.new"), () -> {
                openDropdown = -1;
                openProjectDetails(false);
            });
        row++;

        dropdownOption(mx, y + row * MENU_ITEM_H, w,
            Component.translatable("gui.eca.shader_generator.file.open"), () -> {
                openDropdown = -1;
                minecraft.setScreen(new ShaderProjectSelectionScreen(
                    this,
                    ShaderProjectCodec.listSavedProjects(),
                    this::openProject
                ));
            });
        row++;

        dropdownOption(mx, y + row * MENU_ITEM_H, w,
            Component.translatable("gui.eca.shader_generator.file.save"), () -> {
                openDropdown = -1;
                saveCurrentProject();
                rebuildWidgets();
            });
        row++;

        dropdownOption(mx, y + row * MENU_ITEM_H, w,
            Component.translatable("gui.eca.shader_generator.file.rename"), () -> {
                openDropdown = -1;
                openProjectDetails(true);
            });
    }

    /* 编辑 */
    private void addEditDropdown() {
        int mx = 64;
        int y = MENU_Y + MENU_ITEM_H + 2;
        int w = 96;

        dropdownOption(mx, y, w, Component.translatable("gui.eca.shader_generator.edit.undo"), () -> {
            openDropdown = -1;
            undo();
        });
        dropdownOption(mx, y + MENU_ITEM_H, w, Component.translatable("gui.eca.shader_generator.edit.redo"), () -> {
            openDropdown = -1;
            redo();
        });
        dropdownOption(mx, y + MENU_ITEM_H * 2, w,
            Component.translatable("gui.eca.shader_generator.button.compile"), () -> {
                openDropdown = -1;
                compileCurrentProject();
                rebuildWidgets();
            });
    }

    private void addLayerDropdown() {
        int mx = 122;
        int y = MENU_Y + MENU_ITEM_H + 2;
        int width = 112;
        dropdownOption(mx, y, width,
            Component.translatable("gui.eca.shader_generator.button.new_layer"),
            this::addLayer);
        dropdownOption(mx, y + MENU_ITEM_H, width,
            Component.translatable("gui.eca.shader_generator.layer.move_up"),
            () -> moveSelectedLayer(1));
        dropdownOption(mx, y + MENU_ITEM_H * 2, width,
            Component.translatable("gui.eca.shader_generator.layer.move_down"),
            () -> moveSelectedLayer(-1));
        dropdownOption(mx, y + MENU_ITEM_H * 3, width,
            Component.translatable("gui.eca.shader_generator.layer.toggle_visibility"),
            this::toggleSelectedLayerVisibility);
        dropdownOption(mx, y + MENU_ITEM_H * 4, width,
            Component.translatable("gui.eca.shader_generator.layer.delete"),
            this::removeSelectedLayer);
    }

    /* 预览视图 */
    private void addViewDropdown() {
        int mx = 270;
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

    /* 着色器效果：点击分类展开/收起右侧效果列表（PS 折叠面板风格） */
    private void addEffectDropdown() {
        int mx = 180;
        int y = MENU_Y + MENU_ITEM_H + 2;
        int width = 130;

        /* 只展示有子项的三大分类——IMAGE 不在此处作为分类按钮 */
        ShaderModuleDefinition.Category[] categories = {
            ShaderModuleDefinition.Category.BASIC,
            ShaderModuleDefinition.Category.STARRY_SKY,
            ShaderModuleDefinition.Category.MAGIC
        };
        for (int i = 0; i < categories.length; i++) {
            ShaderModuleDefinition.Category category = categories[i];
            int rowY = y + i * MENU_ITEM_H;
            dropdownOption(mx, rowY, width,
                Component.translatable(category.translationKey()),
                () -> {
                    expandedEffectCategory = (expandedEffectCategory == category) ? null : category;
                    rebuildWidgets();
                });
        }

        /* image_element 作为直接条目，不归属任何分类飞出一级 */
        ShaderModuleDefinition imageDef = ShaderModuleRegistry.get("image_element");
        if (imageDef != null) {
            int imageRowY = y + (categories.length + 1) * MENU_ITEM_H;
            dropdownOption(mx, imageRowY, width,
                Component.translatable(imageDef.displayName()),
                () -> {
                    openDropdown = -1;
                    expandedEffectCategory = null;
                    selectEffectFromMenu(imageDef);
                });
        }

        /* 点击分类后右侧固定展开效果列表 */
        if (expandedEffectCategory != null) {
            List<ShaderModuleDefinition> effects = ShaderModuleRegistry.byCategory(expandedEffectCategory);
            int flyoutX = mx + width + 4;
            if (!effects.isEmpty()) {
                for (int i = 0; i < effects.size(); i++) {
                    ShaderModuleDefinition definition = effects.get(i);
                    dropdownOption(flyoutX, y + i * MENU_ITEM_H, 160,
                        Component.translatable(definition.displayName()),
                        () -> {
                            openDropdown = -1;
                            expandedEffectCategory = null;
                            selectEffectFromMenu(definition);
                        });
                }
            }
        }
    }

    private void selectEffectFromMenu(ShaderModuleDefinition definition) {
        if (selectedLayer() == null) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.layer_required");
            rebuildWidgets();
            return;
        }
        openEffectDetails(definition);
    }

    private void dropdownOption(int x, int y, int w, Component label, Runnable action) {
        addRenderableWidget(Button.builder(label, btn -> action.run())
            .bounds(x, y, w, MENU_ITEM_H).build());
    }

    /* 点击菜单外关闭下拉 */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (editingLayerIndex >= 0
            && layerNameField != null
            && !layerNameField.isMouseOver(mx, my)
            && button == 0) {
            confirmLayerRename();
            return true;
        }
        if (openDropdown >= 0 && button == 0) {
            List<? extends GuiEventListener> kids = children();
            for (int i = dropdownFirstWidgetIndex;
                 i <= dropdownLastWidgetIndex && i < kids.size();
                 i++) {
                GuiEventListener listener = kids.get(i);
                if (listener.isMouseOver(mx, my)) {
                    return listener.mouseClicked(mx, my, button);
                }
            }
            openDropdown = -1;
            rebuildWidgets();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (editingLayerIndex >= 0) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirmLayerRename();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelLayerRename();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int panelLeft = this.width - RIGHT_WIDTH + 8;
        int panelRight = this.width - 8;
        if (openDropdown < 0 && mouseX >= panelLeft && mouseX < panelRight) {
            /* 图层列表滚动 */
            if (mouseY >= layerListTop && mouseY < layerListBottom) {
                int maxScroll = Math.max(0, project.layers().size() - visibleLayerRowCount);
                int direction = delta > 0.0 ? -1 : 1;
                int next = Math.max(0, Math.min(maxScroll, layerScroll + direction));
                if (next != layerScroll) {
                    layerScroll = next;
                    rebuildWidgets();
                }
                return true;
            }
            /* 元素列表滚动 */
            ShaderLayer sel = selectedLayer();
            if (sel != null
                && mouseY >= elementListTop
                && mouseY < elementListBottom) {
                int maxScroll = Math.max(0, sel.elements().size() - visibleElementRowCount);
                int direction = delta > 0.0 ? -1 : 1;
                int next = Math.max(0, Math.min(maxScroll, elementScroll + direction));
                if (next != elementScroll) {
                    elementScroll = next;
                    rebuildWidgets();
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /* ---------- right: layer stack + element list + properties ---------- */

    private void addLayerPanel() {
        int px = this.width - RIGHT_WIDTH + 8;
        int y = TOP_HEIGHT + 20;
        int lw = RIGHT_WIDTH - 16;
        List<ShaderLayer> layers = project.layers();
        int panelBottom = this.height - BOTTOM_HEIGHT - 8;

        /* 图层列表：顶部约 1/3 */
        int layerListMaxHeight = Math.max(LAYER_ROW_HEIGHT * 3,
            Math.min(LAYER_ROW_HEIGHT * 5, (panelBottom - y) / 3));
        layerListTop = y;
        layerListBottom = y + layerListMaxHeight;
        visibleLayerRowCount = Math.max(1, (layerListBottom - layerListTop) / LAYER_ROW_HEIGHT);
        int layerMaxScroll = Math.max(0, layers.size() - visibleLayerRowCount);
        layerScroll = Math.max(0, Math.min(layerScroll, layerMaxScroll));
        visibleLayerRows.clear();

        int endIdx = Math.min(layers.size(), layerScroll + visibleLayerRowCount);
        for (int di = layerScroll; di < endIdx; di++) {
            int li = layers.size() - 1 - di;
            int rowY = layerListTop + (di - layerScroll) * LAYER_ROW_HEIGHT;
            addLayerRow(px, rowY, lw, layers.get(li), li);
        }

        ShaderLayer sel = selectedLayer();
        int cursorY = layerListBottom + 4;
        if (sel == null) {
            elementScroll = 0;
            propertyControlsY = -1;
            return;
        }

        /* 图层设置：混合模式 + 透明度 */
        addLayerSettings(px, cursorY, lw, sel);
        cursorY += 44;

        /* 元素列表标题 + 添加按钮 */
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.panel.elements"),
            btn -> {}
        ).bounds(px, cursorY, lw - 24, 16).build());
        addRenderableWidget(Button.builder(
            Component.literal("+"),
            btn -> {
                if (selectedLayer() == null) {
                    statusError = true;
                    status = Component.translatable("gui.eca.shader_generator.status.layer_required");
                    rebuildWidgets();
                    return;
                }
                openDropdown = 4;
                rebuildWidgets();
            }
        ).bounds(px + lw - 22, cursorY, 22, 16).build());
        cursorY += 20;

        /* 元素列表 */
        List<ShaderModuleInstance> elements = sel.elements();
        int remainingForElements = panelBottom - cursorY;
        int elementListMaxHeight = Math.max(18,
            Math.min(18 * 4, remainingForElements / 2));
        elementListTop = cursorY;
        elementListBottom = cursorY + elementListMaxHeight;
        elementListBottom = Math.min(elementListBottom, panelBottom);
        visibleElementRowCount = Math.max(1,
            (elementListBottom - elementListTop) / 18);
        int elemMaxScroll = Math.max(0, elements.size() - visibleElementRowCount);
        elementScroll = Math.max(0, Math.min(elementScroll, elemMaxScroll));

        int elemEnd = Math.min(elements.size(), elementScroll + visibleElementRowCount);
        visibleElementRows.clear();
        for (int ei = elementScroll; ei < elemEnd; ei++) {
            int rowY = elementListTop + (ei - elementScroll) * 18;
            visibleElementRows.add(new ElementRowVisual(px, rowY, lw, 18, ei));
            elementRow(px, rowY, lw, elements.get(ei), ei);
        }

        cursorY = elementListBottom + 4;

        /* 选中元素属性 */
        ShaderModuleInstance el = selectedElement();
        if (el != null) {
            propertyControlsY = cursorY;
            addPropertyControls(px, cursorY);
        } else {
            propertyControlsY = -1;
        }
    }

    private void addLayerRow(int x, int y, int w, ShaderLayer layer, int layerIdx) {
        visibleLayerRows.add(new LayerRowVisual(
            x,
            y,
            w,
            18,
            layerIdx,
            LAYER_COLORS[Math.floorMod(layerIdx, LAYER_COLORS.length)]
        ));
        VisibilityWidget visibility = new VisibilityWidget(
            x + 5,
            y + 1,
            20,
            16,
            layer.visible(),
            () -> {
                pushUndo();
                layer.setVisible(!layer.visible());
                markDirty("gui.eca.shader_generator.status.layer_visibility_changed");
                rebuildWidgets();
            }
        );
        visibility.setTooltip(Tooltip.create(
            Component.translatable("gui.eca.shader_generator.layer.visibility_tooltip")
        ));
        addRenderableWidget(visibility);

        addRenderableWidget(new ColorSwatchWidget(
            x + 28,
            y + 1,
            20,
            16,
            layerColorArgb(layer),
            () -> editLayerDetails(layerIdx)
        ));

        if (editingLayerIndex == layerIdx) {
            layerNameField = new EditBox(
                font,
                x + 50,
                y,
                w - 50,
                18,
                Component.translatable("gui.eca.shader_generator.layer.rename")
            );
            layerNameField.setMaxLength(64);
            layerNameField.setValue(layer.name());
            addRenderableWidget(layerNameField);
            setInitialFocus(layerNameField);
            layerNameField.setHighlightPos(0);
        } else {
            addRenderableWidget(new LayerSelectWidget(
                x + 50,
                y,
                w - 50,
                18,
                Component.literal(layer.name()),
                () -> {
                    if (selectedLayerIndex == layerIdx) {
                        beginLayerRename(layerIdx);
                        return;
                    }
                    selectedLayerIndex = layerIdx;
                    selectedElementIndex = -1;
                    ensureSelectedLayerVisible();
                    rebuildWidgets();
                }
            ));
        }
    }

    private void addLayerSettings(int x, int y, int width, ShaderLayer layer) {
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.layer.blend",
                Component.translatable(layer.blendMode().translationKey())),
            btn -> {
                pushUndo();
                ShaderLayerBlendMode[] modes = ShaderLayerBlendMode.values();
                layer.setBlendMode(modes[(layer.blendMode().ordinal() + 1) % modes.length]);
                markDirty("gui.eca.shader_generator.status.blend_mode_changed");
                rebuildWidgets();
            }
        ).bounds(x, y, width, 16).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.decrease"),
            btn -> {
                pushUndo();
                layer.setOpacity(layer.opacity() - 0.05F);
                markDirty("gui.eca.shader_generator.status.opacity_changed");
                rebuildWidgets();
            }
        ).bounds(x, y + 20, 20, 16).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.layer.opacity_label",
                Math.round(layer.opacity() * 100)),
            ignored -> {}
        ).bounds(x + 24, y + 20, width - 48, 16).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.increase"),
            btn -> {
                pushUndo();
                layer.setOpacity(layer.opacity() + 0.05F);
                markDirty("gui.eca.shader_generator.status.opacity_changed");
                rebuildWidgets();
            }
        ).bounds(x + width - 20, y + 20, 20, 16).build());
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
        if (hasColorParameters(el)) {
            addRenderableWidget(new ColorSwatchWidget(
                vx,
                y,
                78,
                16,
                colorArgb(el),
                () -> openColorPicker(el)
            ));
            y += 20;
        }
        for (ShaderModuleDefinition.Parameter param : el.definition().parameters()) {
            if (isColorParameter(param.key())) {
                continue;
            }
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

    private void openColorPicker(ShaderModuleInstance element) {
        minecraft.setScreen(new ShaderColorPickerScreen(
            this,
            element.value("color_r"),
            element.value("color_g"),
            element.value("color_b"),
            element.value("color_a"),
            (red, green, blue, alpha) -> {
                pushUndo();
                element.setValue("color_r", red);
                element.setValue("color_g", green);
                element.setValue("color_b", blue);
                element.setValue("color_a", alpha);
                markDirty("gui.eca.shader_generator.status.color_changed");
                rebuildWidgets();
            }
        ));
    }

    private static boolean hasColorParameters(ShaderModuleInstance element) {
        return element.values().containsKey("color_r")
            && element.values().containsKey("color_g")
            && element.values().containsKey("color_b")
            && element.values().containsKey("color_a");
    }

    private static boolean isColorParameter(String key) {
        return key.equals("color_r")
            || key.equals("color_g")
            || key.equals("color_b")
            || key.equals("color_a");
    }

    private static int colorArgb(ShaderModuleInstance element) {
        return channel(element.value("color_a")) << 24
            | channel(element.value("color_r")) << 16
            | channel(element.value("color_g")) << 8
            | channel(element.value("color_b"));
    }

    private static int layerColorArgb(ShaderLayer layer) {
        return channel(layer.baseAlpha()) << 24
            | channel(layer.baseRed()) << 16
            | channel(layer.baseGreen()) << 8
            | channel(layer.baseBlue());
    }

    private static int channel(float value) {
        return Math.round(Math.max(0.0F, Math.min(1.0F, value)) * 255.0F);
    }

    private void addLayer() {
        openDropdown = -1;
        int nextIndex = project.layers().size() + 1;
        minecraft.setScreen(new ShaderLayerDetailsScreen(
            this,
            currentProjectRef(),
            new ShaderLayerDetailsScreen.LayerDetails(
                "Layer " + nextIndex,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                null
            ),
            details -> {
                pushUndo();
                ShaderLayer layer = project.addLayer();
                applyLayerDetails(layer, details);
                selectedLayerIndex = project.layers().size() - 1;
                selectedElementIndex = -1;
                layerScroll = 0;
                markDirty("gui.eca.shader_generator.status.layer_added");
                rebuildWidgets();
            }
        ));
    }

    private void editLayerDetails(int layerIndex) {
        if (layerIndex < 0 || layerIndex >= project.layers().size()) {
            return;
        }
        ShaderLayer layer = project.layers().get(layerIndex);
        minecraft.setScreen(new ShaderLayerDetailsScreen(
            this,
            currentProjectRef(),
            new ShaderLayerDetailsScreen.LayerDetails(
                layer.name(),
                layer.baseRed(),
                layer.baseGreen(),
                layer.baseBlue(),
                layer.baseAlpha(),
                layer.backgroundImagePath()
            ),
            details -> {
                pushUndo();
                applyLayerDetails(layer, details);
                markDirty("gui.eca.shader_generator.status.layer_base_changed");
                rebuildWidgets();
            }
        ));
    }

    private static void applyLayerDetails(
        ShaderLayer layer,
        ShaderLayerDetailsScreen.LayerDetails details
    ) {
        layer.setName(details.name());
        layer.setBaseColor(details.red(), details.green(), details.blue(), details.alpha());
        layer.setBackgroundImagePath(details.imagePath());
    }

    private void beginLayerRename(int layerIndex) {
        if (layerIndex < 0 || layerIndex >= project.layers().size()) {
            return;
        }
        editingLayerIndex = layerIndex;
        rebuildWidgets();
    }

    private void confirmLayerRename() {
        if (editingLayerIndex < 0 || editingLayerIndex >= project.layers().size()) {
            cancelLayerRename();
            return;
        }
        String name = layerNameField == null ? "" : layerNameField.getValue().trim();
        ShaderLayer layer = project.layers().get(editingLayerIndex);
        if (!name.isEmpty() && !name.equals(layer.name())) {
            pushUndo();
            layer.setName(name);
            statusError = false;
            status = Component.translatable("gui.eca.shader_generator.status.layer_renamed", name);
        }
        editingLayerIndex = -1;
        layerNameField = null;
        rebuildWidgets();
    }

    private void cancelLayerRename() {
        editingLayerIndex = -1;
        layerNameField = null;
        rebuildWidgets();
    }

    private void toggleSelectedLayerVisibility() {
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            return;
        }
        openDropdown = -1;
        pushUndo();
        layer.setVisible(!layer.visible());
        markDirty("gui.eca.shader_generator.status.layer_visibility_changed");
        rebuildWidgets();
    }

    private void openEffectSelection() {
        openDropdown = -1;
        if (selectedLayer() == null) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.layer_required");
            rebuildWidgets();
            return;
        }
        ShaderEffectSelectionScreen selectionScreen = new ShaderEffectSelectionScreen(
            this,
            ShaderModuleRegistry.all(),
            definition -> openEffectDetails(definition)
        );
        minecraft.setScreen(selectionScreen);
    }

    private void openEffectDetails(ShaderModuleDefinition definition) {
        minecraft.setScreen(new ShaderEffectDetailsScreen(
            minecraft.screen,
            this,
            definition,
            currentProjectRef(),
            this::addShaderEffect
        ));
    }

    private void addShaderEffect(ShaderModuleInstance effect) {
        ShaderLayer layer = selectedLayer();
        if (layer == null || effect == null) {
            return;
        }
        pushUndo();
        layer.addElement(effect);
        selectedElementIndex = layer.elements().size() - 1;
        markDirty("gui.eca.shader_generator.status.element_added",
            Component.translatable(effect.definition().displayName()));
        rebuildWidgets();
    }

    private void openProjectDetails(boolean rename) {
        if (rename && (projectModId == null || projectShaderName == null)) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.project_required");
            rebuildWidgets();
            return;
        }
        Component title = Component.translatable(rename
            ? "gui.eca.shader_generator.project.rename_title"
            : "gui.eca.shader_generator.project.new_title");
        String initialModId = rename ? projectModId : "";
        String initialShaderName = rename ? projectShaderName : "";
        minecraft.setScreen(new ShaderProjectDetailsScreen(
            this,
            title,
            initialModId,
            initialShaderName,
            rename ? this::renameProject : this::createProject
        ));
    }

    private boolean createProject(String modId, String shaderName) {
        ProjectRef reference = new ProjectRef(modId, shaderName);
        if (ShaderProjectCodec.exists(reference)) {
            return false;
        }
        pushUndo();
        project.copyStateFrom(new ShaderCompositionProject());
        projectModId = modId;
        projectShaderName = shaderName;
        selectedLayerIndex = 0;
        selectedElementIndex = -1;
        layerScroll = 0;
        boolean saved = ShaderProjectCodec.save(modId, shaderName, project);
        if (!saved) {
            return false;
        }
        compileCurrentProject();
        if (!statusError) {
            status = Component.translatable("gui.eca.shader_generator.status.project_created", reference.id());
        }
        return true;
    }

    private boolean renameProject(String modId, String shaderName) {
        ProjectRef source = currentProjectRef();
        if (source == null || !ShaderProjectCodec.rename(source, modId, shaderName, project)) {
            return false;
        }
        projectModId = modId;
        projectShaderName = shaderName;
        compileCurrentProject();
        if (!statusError) {
            status = Component.translatable("gui.eca.shader_generator.status.project_renamed",
                currentProjectRef().id());
        }
        return true;
    }

    private void openProject(ProjectRef reference) {
        pushUndo();
        boolean loaded = ShaderProjectCodec.load(reference, project);
        openDropdown = -1;
        if (loaded) {
            projectModId = reference.modId();
            projectShaderName = reference.shaderName();
            selectedLayerIndex = 0;
            selectedElementIndex = -1;
            layerScroll = 0;
            compileCurrentProject();
            if (!statusError) {
                status = Component.translatable("gui.eca.shader_generator.status.loaded", reference.id());
            }
        } else {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.load_failed", reference.id());
        }
        rebuildWidgets();
    }

    private void saveCurrentProject() {
        ProjectRef reference = currentProjectRef();
        if (reference == null) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.project_required");
            return;
        }
        boolean saved = ShaderProjectCodec.save(reference.modId(), reference.shaderName(), project);
        statusError = !saved;
        status = saved
            ? Component.translatable("gui.eca.shader_generator.status.saved", reference.id())
            : Component.translatable("gui.eca.shader_generator.status.save_failed");
    }

    private ProjectRef currentProjectRef() {
        if (projectModId == null || projectShaderName == null) {
            return null;
        }
        return new ProjectRef(projectModId, projectShaderName);
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
        ensureSelectedLayerVisible();
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
        ensureSelectedLayerVisible();
        markDirty("gui.eca.shader_generator.status.layer_removed");
        rebuildWidgets();
    }

    private void ensureSelectedLayerVisible() {
        if (selectedLayerIndex < 0 || project.layers().isEmpty()) {
            return;
        }
        int displayIndex = project.layers().size() - 1 - selectedLayerIndex;
        if (displayIndex < layerScroll) {
            layerScroll = displayIndex;
        } else if (displayIndex >= layerScroll + visibleLayerRowCount) {
            layerScroll = displayIndex - visibleLayerRowCount + 1;
        }
    }

    private void compileCurrentProject() {
        previewCompilePending = false;
        long rev = PREVIEW_REVISION.incrementAndGet();
        ShaderProject sp = project.toShaderProject("eca_preview", "generated/project_" + rev);
        try {
            GeneratedShaderPreview compiled = GeneratedShaderPreview.compile(
                sp,
                project.exportMode(),
                previewTexturePaths(sp)
            );
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

    private Map<String, Path> previewTexturePaths(ShaderProject shaderProject) {
        ProjectRef reference = currentProjectRef();
        if (reference == null || shaderProject.textures().isEmpty()) {
            return Map.of();
        }
        Map<String, Path> paths = new LinkedHashMap<>();
        for (ShaderProject.TextureBinding texture : shaderProject.textures()) {
            Path path = ShaderProjectCodec.resolveProjectAsset(reference, texture.projectPath());
            if (path != null) {
                paths.put(texture.samplerName(), path);
            }
        }
        return paths;
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
        previewCompilePending = true;
        statusError = false;
        status = Component.translatable(key, args);
    }

    @Override
    public void tick() {
        super.tick();
        if (previewCompilePending) {
            compileCurrentProject();
            rebuildWidgets();
        }
    }

    private static String truncatedName(String name, int max) {
        return name.length() <= max ? name : name.substring(0, max - 1) + "…";
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
        g.fill(this.width - RIGHT_WIDTH, TOP_HEIGHT, this.width, this.height - BOTTOM_HEIGHT, PANEL_COLOR);
        drawLayerRows(g);
        drawElementRows(g);

        Component projectLabel = currentProjectRef() == null
            ? Component.translatable("gui.eca.shader_generator.project.unnamed")
            : Component.literal(currentProjectRef().id());
        String projectText = this.font.plainSubstrByWidth(projectLabel.getString(), 112);
        g.drawString(this.font, projectText, 8, PROJECT_ROW_Y + 5, 0xFFCDD1D7, false);

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
        g.drawString(this.font, Component.translatable("gui.eca.shader_generator.panel.layers"),
            this.width - RIGHT_WIDTH + 8, TOP_HEIGHT + 4, 0xFFBFC4CC, false);

        /* 4. 普通控件 */
        super.render(g, mx, my, pt);

        /* 5. 独立深度层确保下拉菜单覆盖已经写入深度缓冲的普通控件 */
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 400.0F);
        drawDropdownBackground(g);
        renderDropdownWidgets(g, mx, my, pt);
        g.pose().popPose();
    }

    private void drawLayerRows(GuiGraphics graphics) {
        for (LayerRowVisual row : visibleLayerRows) {
            boolean selected = row.layerIndex() == selectedLayerIndex;
            int background = (selected ? 0x66000000 : 0x33000000) | (row.color() & 0x00FFFFFF);
            graphics.fill(row.x(), row.y(), row.x() + row.width(), row.y() + row.height(), background);
            graphics.fill(row.x(), row.y(), row.x() + 4, row.y() + row.height(), row.color());
            if (selected) {
                graphics.renderOutline(
                    row.x(),
                    row.y(),
                    row.width(),
                    row.height(),
                    0xFFE6E9ED
                );
            }
        }
    }

    private void drawElementRows(GuiGraphics graphics) {
        for (ElementRowVisual row : visibleElementRows) {
            boolean selected = row.elementIndex() == selectedElementIndex;
            int background = selected ? 0x44FFFFFF : 0x11FFFFFF;
            graphics.fill(row.x(), row.y(), row.x() + row.width(), row.y() + row.height(), background);
            if (selected) {
                graphics.renderOutline(
                    row.x(),
                    row.y(),
                    row.width(),
                    row.height(),
                    0xFF8B9098
                );
            }
        }
    }

    private void drawDropdownBackground(GuiGraphics g) {
        if (openDropdown < 0 || dropdownLastWidgetIndex < dropdownFirstWidgetIndex) {
            return;
        }
        List<? extends GuiEventListener> kids = children();
        if (dropdownFirstWidgetIndex >= kids.size()) {
            return;
        }
        var first = kids.get(dropdownFirstWidgetIndex);
        if (!(first instanceof AbstractWidget fw)) {
            return;
        }
        int dl = fw.getX();
        int dt = fw.getY();
        int dr = fw.getX() + fw.getWidth();
        int db = fw.getY() + fw.getHeight();
        for (int i = dropdownFirstWidgetIndex + 1;
             i <= dropdownLastWidgetIndex && i < kids.size();
             i++) {
            if (kids.get(i) instanceof AbstractWidget widget) {
                dl = Math.min(dl, widget.getX());
                dt = Math.min(dt, widget.getY());
                dr = Math.max(dr, widget.getX() + widget.getWidth());
                db = Math.max(db, widget.getY() + widget.getHeight());
            }
        }
        dl -= 4;
        dt -= 2;
        dr += 4;
        db += 2;
        g.fill(dl, dt, dr, db, DROPDOWN_BG);
        g.fill(dl, dt, dr, dt + 1, BORDER_COLOR);
        g.fill(dl, db - 1, dr, db, BORDER_COLOR);
        g.fill(dl, dt, dl + 1, db, BORDER_COLOR);
        g.fill(dr - 1, dt, dr, db, BORDER_COLOR);
    }

    private void renderDropdownWidgets(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (openDropdown < 0 || dropdownLastWidgetIndex < dropdownFirstWidgetIndex) {
            return;
        }
        List<? extends GuiEventListener> kids = children();
        for (int i = dropdownFirstWidgetIndex;
             i <= dropdownLastWidgetIndex && i < kids.size();
             i++) {
            if (kids.get(i) instanceof AbstractWidget widget) {
                widget.render(graphics, mouseX, mouseY, partialTick);
            }
        }
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

    private static final class VisibilityWidget extends AbstractWidget {

        private final boolean checked;
        private final Runnable action;

        private VisibilityWidget(
            int x,
            int y,
            int width,
            int height,
            boolean checked,
            Runnable action
        ) {
            super(x, y, width, height,
                Component.translatable("gui.eca.shader_generator.layer.visibility_tooltip"));
            this.checked = checked;
            this.action = action;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            action.run();
        }

        @Override
        protected void renderWidget(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
        ) {
            int border = isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF8B9098;
            graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x55212428);
            graphics.renderOutline(getX(), getY(), width, height, border);
            if (checked) {
                graphics.drawCenteredString(
                    Minecraft.getInstance().font,
                    "◉",
                    getX() + width / 2,
                    getY() + 4,
                    0xFFFFFFFF
                );
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static final class LayerSelectWidget extends AbstractWidget {

        private final Runnable action;

        private LayerSelectWidget(
            int x,
            int y,
            int width,
            int height,
            Component name,
            Runnable action
        ) {
            super(x, y, width, height, name);
            this.action = action;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            action.run();
        }

        @Override
        protected void renderWidget(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
        ) {
            if (isHoveredOrFocused()) {
                graphics.fill(getX(), getY(), getX() + width, getY() + height, 0x33FFFFFF);
            }
            String name = Minecraft.getInstance().font.plainSubstrByWidth(
                getMessage().getString(),
                width - 8
            );
            graphics.drawString(
                Minecraft.getInstance().font,
                name,
                getX() + 5,
                getY() + 5,
                0xFFFFFFFF,
                false
            );
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static final class ColorSwatchWidget extends AbstractWidget {

        private final int color;
        private final Runnable action;

        private ColorSwatchWidget(
            int x,
            int y,
            int width,
            int height,
            int color,
            Runnable action
        ) {
            super(x, y, width, height,
                Component.translatable("gui.eca.shader_generator.color_picker.open"));
            this.color = color;
            this.action = action;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            action.run();
        }

        @Override
        protected void renderWidget(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
        ) {
            int border = isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF8B9098;
            graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xFF202225);
            if (width < 50) {
                /* 窄控件仅显示色块，不绘制 hex 文本，避免与相邻图层名重叠 */
                graphics.fill(getX() + 2, getY() + 2, getX() + width - 2, getY() + height - 2, color);
                graphics.renderOutline(getX(), getY(), width, height, border);
            } else {
                graphics.fill(getX() + 2, getY() + 2, getX() + 28, getY() + height - 2, color);
                graphics.renderOutline(getX(), getY(), width, height, border);
                graphics.drawString(
                    Minecraft.getInstance().font,
                    String.format("#%06X", color & 0xFFFFFF),
                    getX() + 32,
                    getY() + 4,
                    0xFFFFFFFF,
                    false
                );
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private record LayerRowVisual(
        int x,
        int y,
        int width,
        int height,
        int layerIndex,
        int color
    ) {}

    private record ElementRowVisual(
        int x,
        int y,
        int width,
        int height,
        int elementIndex
    ) {}
}
