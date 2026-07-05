package net.eca.client.gui;

import net.eca.client.render.shader_generator.GeneratedShaderPreview;
import net.eca.client.render.shader_generator.ShaderPreviewRenderer;
import net.eca.client.render.shader_generator.ShaderPreviewSource;
import net.eca.client.render.shader_generator.ShaderPreviewSourceCatalog;
import net.eca.client.render.shader_generator.ShaderPreviewTarget;
import net.eca.util.EcaLogger;
import net.eca.util.shader_generator.ShaderCompositionProject;
import net.eca.util.shader_generator.ShaderLayer;
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
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

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
    private static final int RIGHT_WIDTH = 300;
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
    /* 画布视图缩放范围与滚轮步进；手柄尺寸(像素)与旋转手柄距选框上沿的偏移 */
    private static final double CANVAS_ZOOM_MIN = 0.25;
    private static final double CANVAS_ZOOM_MAX = 8.0;
    private static final double CANVAS_ZOOM_STEP = 1.1;
    private static final int CANVAS_HANDLE_SIZE = 7;
    private static final int CANVAS_ROTATE_HANDLE_OFFSET = 18;
    private static final long DOUBLE_CLICK_MS = 450L;
    /* 连续拖动编辑在此窗口内合并为一次撤销快照 */
    private static final long EDIT_COALESCE_MS = 400L;
    /* 预览重编译的最小间隔，拖动时限速刷新而非每帧全量重编 */
    private static final long COMPILE_MIN_INTERVAL_MS = 100L;
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
    private List<ShaderPreviewSource> registeredSources = List.of();
    private GeneratedShaderPreview generatedPreview;
    private ShaderPreviewTarget previewTarget = ShaderPreviewTarget.PLANE;
    private int selectedLayerIndex = -1;
    /* 右栏模式：LAYER_LIST 只显示图层列表；LAYER_DETAIL 显示选中图层的完整编辑器 */
    private RightPanelMode rightPanelMode = RightPanelMode.LAYER_LIST;
    private int sourceIndex;
    private int layerScroll;
    private int visibleLayerRowCount = 1;
    private int layerListTop;
    private int layerListBottom;
    /* 检查器面板（右下）：选中图层显示图层属性+元素列表，进入元素后显示参数 */
    private int inspectorTop;
    private int inspectorBottom;
    private int inspectorListTop;
    private int inspectorListBottom;
    private int inspectorVisibleRows = 1;
    private int selectedElementIndex = -1;
    private boolean elementParamView;
    private int elementScroll;
    private int paramScroll;
    /* 画布直接操纵：拖动选中图层内某元素改其 center_x/center_y */
    private int draggingElementIndex = -1;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean dragUndoStarted;
    /* 手柄拖拽模式：MOVE 改位置，SCALE 改 size，ROTATE 改 rotation */
    private CanvasDragMode canvasDragMode = CanvasDragMode.MOVE;
    private float dragStartSize;
    private float dragStartRotation;
    private double dragStartAngle;
    private double dragStartDist;
    /* 画布视图：缩放与平移(像素)，仅影响预览显示与 UV 映射，不改工程数据 */
    private double canvasZoom = 1.0;
    private double canvasPanX;
    private double canvasPanY;
    private boolean panningCanvas;
    /* 剪贴板：clipboardIsElement 决定粘贴的是元素还是整层 */
    private ShaderModuleInstance clipboardElement;
    private ShaderLayer clipboardLayer;
    private boolean clipboardIsElement;
    /* 编辑手势合并与编译节流的时间戳 */
    private long lastEditActivityMs;
    private long lastCompileMs;
    private boolean previewCompilePending;
    private int editingLayerIndex = -1;
    private EditBox layerNameField;
    private boolean pendingLayerNameFocus;
    private int lastLayerNameClickIndex = -1;
    private long lastLayerNameClickTime;
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

    /* 拖动等连续编辑：同一手势（间隔小于合并窗口）只压入一次撤销快照 */
    private void beginEdit() {
        long now = System.currentTimeMillis();
        if (now - lastEditActivityMs > EDIT_COALESCE_MS) {
            pushUndo();
        }
        lastEditActivityMs = now;
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
        elementParamView = false;
        selectedElementIndex = -1;
    }

    /* ---------- init ---------- */

    @Override
    protected void init() {
        registeredSources = ShaderPreviewSourceCatalog.loadRegisteredSources();
        if (selectedLayerIndex < 0 && !project.layers().isEmpty()) {
            selectedLayerIndex = project.layers().size() - 1;
        }
        if (rightPanelMode == RightPanelMode.LAYER_DETAIL && selectedLayer() != null) {
            addLayerDetailPanel();
        } else {
            rightPanelMode = RightPanelMode.LAYER_LIST;
            addLayerListPanel();
        }
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
        /* 中键在视口内按下即开始平移画布 */
        if (button == 2 && previewViewport().contains(mx, my)) {
            panningCanvas = true;
            return true;
        }
        if (button == 0 && handleCanvasClick(mx, my)) {
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (button == 2 && panningCanvas) {
            canvasPanX += dragX;
            canvasPanY += dragY;
            return true;
        }
        if (button == 0 && draggingElementIndex >= 0) {
            switch (canvasDragMode) {
                case SCALE -> scaleCanvasElement(mx, my);
                case ROTATE -> rotateCanvasElement(mx, my);
                default -> dragCanvasElement(mx, my);
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 2 && panningCanvas) {
            panningCanvas = false;
            return true;
        }
        if (button == 0 && draggingElementIndex >= 0) {
            resetDragState();
            rebuildWidgets();
            return true;
        }
        return super.mouseReleased(mx, my, button);
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
        /* 先给焦点控件（如滑条输入框）机会消费，避免抢走文本输入 */
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (ctrl) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_Z -> {
                    if (shift) {
                        redo();
                    } else {
                        undo();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_Y -> {
                    redo();
                    return true;
                }
                case GLFW.GLFW_KEY_S -> {
                    saveCurrentProject();
                    rebuildWidgets();
                    return true;
                }
                case GLFW.GLFW_KEY_C -> {
                    return copySelection();
                }
                case GLFW.GLFW_KEY_V -> {
                    return pasteClipboard();
                }
                default -> {
                    return false;
                }
            }
        }
        return switch (keyCode) {
            case GLFW.GLFW_KEY_DELETE -> deleteSelection();
            case GLFW.GLFW_KEY_LEFT -> nudgeSelectedElement(-1, 0, shift);
            case GLFW.GLFW_KEY_RIGHT -> nudgeSelectedElement(1, 0, shift);
            case GLFW.GLFW_KEY_UP -> nudgeSelectedElement(0, -1, shift);
            case GLFW.GLFW_KEY_DOWN -> nudgeSelectedElement(0, 1, shift);
            case GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_KP_0 -> resetCanvasView();
            default -> false;
        };
    }

    /* Delete：优先删除选中元素，否则删除选中图层 */
    private boolean deleteSelection() {
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            return false;
        }
        if (selectedElementIndex >= 0 && selectedElementIndex < layer.elements().size()) {
            removeElement(selectedElementIndex);
            return true;
        }
        removeSelectedLayer();
        return true;
    }

    /* 方向键微调选中元素位置；Shift 使用较大步长 */
    private boolean nudgeSelectedElement(int dx, int dy, boolean shift) {
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            return false;
        }
        ShaderModuleInstance element = selectedElementInLayer(layer);
        if (element == null || !containsPositionParameters(element)) {
            return false;
        }
        float delta = shift ? 0.05F : 0.01F;
        beginEdit();
        element.setValue("center_x", element.value("center_x") + dx * delta);
        element.setValue("center_y", element.value("center_y") + dy * delta);
        markDirty("gui.eca.shader_generator.status.parameter_changed",
            Component.translatable(element.definition().displayName()));
        return true;
    }

    /* 复制：有选中元素则复制元素，否则复制整层 */
    private boolean copySelection() {
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            return false;
        }
        ShaderModuleInstance element = selectedElementInLayer(layer);
        statusError = false;
        if (element != null) {
            clipboardElement = element.copy();
            clipboardIsElement = true;
            status = Component.translatable("gui.eca.shader_generator.status.element_copied");
        } else {
            clipboardLayer = layer.copy();
            clipboardIsElement = false;
            status = Component.translatable("gui.eca.shader_generator.status.layer_copied");
        }
        return true;
    }

    private boolean pasteClipboard() {
        return clipboardIsElement ? pasteElement() : pasteLayer();
    }

    private boolean pasteElement() {
        ShaderLayer layer = selectedLayer();
        if (layer == null || clipboardElement == null) {
            return false;
        }
        pushUndo();
        ShaderModuleInstance element = clipboardElement.copy();
        if (containsPositionParameters(element)) {
            element.setValue("center_x", element.value("center_x") + 0.03F);
            element.setValue("center_y", element.value("center_y") + 0.03F);
        }
        layer.addElement(element);
        selectedElementIndex = layer.elements().size() - 1;
        elementParamView = true;
        markDirty("gui.eca.shader_generator.status.element_pasted");
        rebuildWidgets();
        return true;
    }

    private boolean pasteLayer() {
        if (clipboardLayer == null) {
            return false;
        }
        pushUndo();
        int insertIndex = selectedLayerIndex >= 0 ? selectedLayerIndex + 1 : project.layers().size();
        ShaderLayer pasted = project.insertLayer(insertIndex, clipboardLayer.copy());
        selectLayerForInspector(project.layers().indexOf(pasted));
        ensureSelectedLayerVisible();
        markDirty("gui.eca.shader_generator.status.layer_pasted");
        rebuildWidgets();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        /* 滚轮在视口内缩放画布，并以光标为锚点 */
        if (openDropdown < 0 && previewViewport().contains(mouseX, mouseY)) {
            zoomCanvasAt(mouseX, mouseY, delta > 0.0 ? CANVAS_ZOOM_STEP : 1.0 / CANVAS_ZOOM_STEP);
            return true;
        }
        int panelLeft = this.width - RIGHT_WIDTH + 8;
        int panelRight = this.width - 8;
        if (openDropdown < 0 && mouseX >= panelLeft && mouseX < panelRight) {
            int direction = delta > 0.0 ? -1 : 1;
            /* 图层列表滚动（仅列表模式） */
            if (rightPanelMode == RightPanelMode.LAYER_LIST
                && mouseY >= layerListTop && mouseY < layerListBottom) {
                int maxScroll = Math.max(0, project.layers().size() - visibleLayerRowCount);
                int next = Math.max(0, Math.min(maxScroll, layerScroll + direction));
                if (next != layerScroll) {
                    layerScroll = next;
                    rebuildWidgets();
                }
                return true;
            }
            /* 检查器列表滚动（元素列表或参数列表，仅详细编辑模式） */
            ShaderLayer layer = selectedLayer();
            if (rightPanelMode == RightPanelMode.LAYER_DETAIL
                && layer != null && mouseY >= inspectorListTop && mouseY < inspectorListBottom) {
                if (elementParamView && selectedElementInLayer(layer) != null) {
                    int total = visibleParameters(selectedElementInLayer(layer)).size();
                    int maxScroll = Math.max(0, total - inspectorVisibleRows);
                    int next = Math.max(0, Math.min(maxScroll, paramScroll + direction));
                    if (next != paramScroll) {
                        paramScroll = next;
                        rebuildWidgets();
                    }
                } else {
                    int maxScroll = Math.max(0, layer.elements().size() - inspectorVisibleRows);
                    int next = Math.max(0, Math.min(maxScroll, elementScroll + direction));
                    if (next != elementScroll) {
                        elementScroll = next;
                        rebuildWidgets();
                    }
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /* ---------- right: layer stack ---------- */

    /* LAYER_LIST 模式：图层列表独占整条右栏，带滚动 */
    private void addLayerListPanel() {
        int px = this.width - RIGHT_WIDTH + 8;
        int y = TOP_HEIGHT + 20;
        int lw = RIGHT_WIDTH - 16;
        List<ShaderLayer> layers = project.layers();
        int panelBottom = this.height - BOTTOM_HEIGHT - 8;

        layerListTop = y;
        layerListBottom = panelBottom;
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
    }

    /* 每行：可见性 | ↑ | ↓ | 名字(双击重命名) | 详细编辑 */
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
            x + 3,
            y + 1,
            18,
            16,
            layer.visible(),
            () -> toggleLayerVisibility(layer)
        );
        visibility.setTooltip(Tooltip.create(
            Component.translatable("gui.eca.shader_generator.layer.visibility_tooltip")
        ));
        addRenderableWidget(visibility);

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.move_up"),
            button -> moveLayerRow(layerIdx, 1)
        ).bounds(x + 23, y, 18, 18).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.button.move_down"),
            button -> moveLayerRow(layerIdx, -1)
        ).bounds(x + 43, y, 18, 18).build());

        int editX = x + w - 46;
        int nameW = editX - (x + 63) - 2;
        if (editingLayerIndex == layerIdx) {
            layerNameField = new EditBox(
                font,
                x + 63,
                y,
                nameW,
                18,
                Component.translatable("gui.eca.shader_generator.layer.rename")
            );
            layerNameField.setMaxLength(64);
            layerNameField.setValue(layer.name());
            addRenderableWidget(layerNameField);
            setInitialFocus(layerNameField);
            layerNameField.setFocused(true);
            layerNameField.setHighlightPos(0);
        } else {
            addRenderableWidget(new LayerSelectWidget(
                x + 63,
                y,
                nameW,
                18,
                Component.literal(layer.name()),
                () -> handleLayerNameClick(layerIdx)
            ));
        }

        Button edit = Button.builder(
            Component.translatable("gui.eca.shader_generator.layer.edit"),
            button -> enterLayerDetail(layerIdx)
        ).bounds(editX, y, 46, 18).build();
        edit.setTooltip(Tooltip.create(
            Component.translatable("gui.eca.shader_generator.layer.edit_tooltip")));
        addRenderableWidget(edit);
    }

    /* LAYER_DETAIL 模式：选中图层的完整编辑器独占整条右栏 */
    private void addLayerDetailPanel() {
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            rightPanelMode = RightPanelMode.LAYER_LIST;
            addLayerListPanel();
            return;
        }
        visibleLayerRows.clear();
        int x = this.width - RIGHT_WIDTH + 8;
        int w = RIGHT_WIDTH - 16;
        int y = TOP_HEIGHT + 20;
        int panelBottom = this.height - BOTTOM_HEIGHT - 8;

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.layer.back"),
            button -> exitLayerDetail()
        ).bounds(x, y, 40, 18).build());

        int headerNameX = x + 44;
        int headerNameW = w - 44;
        if (editingLayerIndex == selectedLayerIndex) {
            layerNameField = new EditBox(
                font, headerNameX, y, headerNameW, 18,
                Component.translatable("gui.eca.shader_generator.layer.rename"));
            layerNameField.setMaxLength(64);
            layerNameField.setValue(layer.name());
            addRenderableWidget(layerNameField);
            setInitialFocus(layerNameField);
            layerNameField.setFocused(true);
            layerNameField.setHighlightPos(0);
        } else {
            addRenderableWidget(new LayerSelectWidget(
                headerNameX, y, headerNameW, 18,
                Component.literal(layer.name()),
                () -> handleLayerNameClick(selectedLayerIndex)));
        }

        inspectorTop = y + 24;
        inspectorBottom = panelBottom;
        addInspectorPanel();
    }

    private void enterLayerDetail(int layerIndex) {
        if (layerIndex < 0 || layerIndex >= project.layers().size()) {
            return;
        }
        selectLayerForInspector(layerIndex);
        ensureSelectedLayerVisible();
        rightPanelMode = RightPanelMode.LAYER_DETAIL;
        rebuildWidgets();
    }

    private void exitLayerDetail() {
        rightPanelMode = RightPanelMode.LAYER_LIST;
        elementParamView = false;
        selectedElementIndex = -1;
        resetDragState();
        rebuildWidgets();
    }

    private void toggleLayerVisibility(ShaderLayer layer) {
        pushUndo();
        layer.setVisible(!layer.visible());
        markDirty("gui.eca.shader_generator.status.layer_visibility_changed");
        rebuildWidgets();
    }

    /* 直接移动指定行的图层，并让选中/画布跟随 */
    private void moveLayerRow(int layerIndex, int offset) {
        int target = layerIndex + offset;
        if (layerIndex < 0 || layerIndex >= project.layers().size()
            || target < 0 || target >= project.layers().size()) {
            return;
        }
        pushUndo();
        project.moveLayer(layerIndex, offset);
        if (selectedLayerIndex == layerIndex) {
            selectedLayerIndex = target;
        } else if (selectedLayerIndex == target) {
            selectedLayerIndex = layerIndex;
        }
        markDirty("gui.eca.shader_generator.status.layer_order_changed");
        ensureSelectedLayerVisible();
        rebuildWidgets();
    }

    /* ---------- actions ---------- */

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
        pushUndo();
        project.addLayer();
        selectLayerForInspector(project.layers().size() - 1);
        layerScroll = 0;
        ensureSelectedLayerVisible();
        markDirty("gui.eca.shader_generator.status.layer_added");
        rebuildWidgets();
    }

    /* 选中图层进入检查器，重置元素子视图到图层属性/元素列表 */
    private void selectLayerForInspector(int layerIndex) {
        selectedLayerIndex = layerIndex;
        elementParamView = false;
        selectedElementIndex = -1;
        elementScroll = 0;
    }

    private void openLayerColorPicker(int layerIndex) {
        if (layerIndex < 0 || layerIndex >= project.layers().size()) {
            return;
        }
        selectLayerForInspector(layerIndex);
        ensureSelectedLayerVisible();
        ShaderLayer layer = project.layers().get(layerIndex);
        minecraft.setScreen(new ShaderColorPickerScreen(
            this,
            layer.baseRed(),
            layer.baseGreen(),
            layer.baseBlue(),
            layer.baseAlpha(),
            (red, green, blue, alpha) -> {
                pushUndo();
                layer.setBaseColor(red, green, blue, alpha);
                markDirty("gui.eca.shader_generator.status.layer_base_changed");
            }
        ));
    }

    /* ---------- inspector: layer + element ---------- */

    private void addInspectorPanel() {
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            return;
        }
        int x = this.width - RIGHT_WIDTH + 8;
        int w = RIGHT_WIDTH - 16;
        if (elementParamView && selectedElementInLayer(layer) != null) {
            addElementParamInspector(layer, x, w);
        } else {
            addLayerInspector(layer, x, w);
        }
    }

    private void addLayerInspector(ShaderLayer layer, int x, int w) {
        int rowH = 20;
        int pitch = 24;
        int y = inspectorTop;

        addRenderableWidget(Button.builder(
            Component.translatable(layer.visible()
                ? "gui.eca.shader_generator.layer_editor.visible"
                : "gui.eca.shader_generator.layer_editor.hidden"),
            button -> toggleSelectedLayerVisibility()
        ).bounds(x, y, 76, rowH).build());
        addRenderableWidget(new ColorSwatchWidget(
            x + 80, y, w - 80, rowH,
            layerColorArgb(layer),
            () -> openLayerColorPicker(selectedLayerIndex)
        ));
        y += pitch;

        addRenderableWidget(new ShaderSliderWidget(
            x, y, w, rowH,
            Component.translatable("gui.eca.shader_generator.layer_editor.base_alpha", 0),
            0.0, 1.0, 0.01,
            layer::baseAlpha,
            value -> setSelectedLayerAlpha((float) value),
            value -> Component.translatable(
                "gui.eca.shader_generator.layer_editor.base_alpha",
                ShaderPercentEditWidget.displayPercent(value))
        ));
        y += pitch;

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.layer_editor.blend_mode",
                Component.translatable(layer.blendMode().displayKey())),
            button -> cycleSelectedLayerBlendMode()
        ).bounds(x, y, w, rowH).build());
        y += pitch;

        addRenderableWidget(Button.builder(
            Component.translatable(layer.backgroundImagePath() == null
                ? "gui.eca.shader_generator.layer.import_background"
                : "gui.eca.shader_generator.layer.replace_background"),
            button -> importSelectedLayerBackground()
        ).bounds(x, y, w, rowH).build());
        y += pitch;

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.layer_elements.add"),
            button -> openEffectSelection()
        ).bounds(x, y, w, rowH).build());
        y += pitch + 2;

        inspectorListTop = y;
        inspectorListBottom = inspectorBottom;
        int listPitch = 22;
        inspectorVisibleRows = Math.max(1, (inspectorListBottom - inspectorListTop) / listPitch);
        List<ShaderModuleInstance> elements = layer.elements();
        int maxScroll = Math.max(0, elements.size() - inspectorVisibleRows);
        elementScroll = Math.max(0, Math.min(elementScroll, maxScroll));
        int end = Math.min(elements.size(), elementScroll + inspectorVisibleRows);
        for (int i = elementScroll; i < end; i++) {
            int elementIndex = i;
            ShaderModuleInstance element = elements.get(i);
            int ry = inspectorListTop + (i - elementScroll) * listPitch;
            Component label = Component.translatable(element.definition().displayName());
            if (!element.enabled()) {
                label = label.copy().append(
                    Component.translatable("gui.eca.shader_generator.layer.disabled"));
            }
            addRenderableWidget(new LayerSelectWidget(
                x, ry, w - 96, 20, label,
                () -> openElementParams(elementIndex)
            ));
            addRenderableWidget(Button.builder(
                Component.literal(element.enabled() ? "I" : "O"),
                button -> toggleElement(elementIndex)
            ).bounds(x + w - 92, ry, 22, 20).build());
            addRenderableWidget(Button.builder(
                Component.literal("^"),
                button -> moveElement(elementIndex, -1)
            ).bounds(x + w - 68, ry, 22, 20).build());
            addRenderableWidget(Button.builder(
                Component.literal("v"),
                button -> moveElement(elementIndex, 1)
            ).bounds(x + w - 44, ry, 22, 20).build());
            addRenderableWidget(Button.builder(
                Component.literal("X"),
                button -> removeElement(elementIndex)
            ).bounds(x + w - 20, ry, 20, 20).build());
        }
    }

    private void addElementParamInspector(ShaderLayer layer, int x, int w) {
        ShaderModuleInstance element = selectedElementInLayer(layer);
        int rowH = 20;
        int pitch = 24;
        int y = inspectorTop;

        addRenderableWidget(Button.builder(
            Component.literal("<"),
            button -> closeElementParams()
        ).bounds(x, y, 40, rowH).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.parameter.color"),
            button -> openElementColorPicker(element)
        ).bounds(x + 44, y, w - 44, rowH).build());
        y += pitch;

        if (element.definition().id().equals("image_element")) {
            addRenderableWidget(Button.builder(
                Component.translatable(element.imagePath() == null
                    ? "gui.eca.shader_generator.effects.import_image"
                    : "gui.eca.shader_generator.effects.replace_image"),
                button -> importElementImage(element)
            ).bounds(x, y, w, rowH).build());
            y += pitch;
        }

        inspectorListTop = y;
        inspectorListBottom = inspectorBottom;
        int listPitch = 22;
        List<ShaderModuleDefinition.Parameter> params = visibleParameters(element);
        inspectorVisibleRows = Math.max(1, (inspectorListBottom - inspectorListTop) / listPitch);
        int maxScroll = Math.max(0, params.size() - inspectorVisibleRows);
        paramScroll = Math.max(0, Math.min(paramScroll, maxScroll));
        int end = Math.min(params.size(), paramScroll + inspectorVisibleRows);
        for (int i = paramScroll; i < end; i++) {
            ShaderModuleDefinition.Parameter parameter = params.get(i);
            int ry = inspectorListTop + (i - paramScroll) * listPitch;
            addRenderableWidget(new ShaderSliderWidget(
                x, ry, w, 20,
                Component.translatable(parameter.displayName()),
                parameter.minimum(), parameter.maximum(), parameter.step(),
                () -> element.value(parameter.key()),
                value -> setParameter(element, parameter, (float) value),
                sliderFormatter(parameter)
            ));
        }
    }

    /* 滑条内嵌显示参数名与当前值；alpha 类参数用百分比 */
    private static ShaderSliderWidget.Formatter sliderFormatter(ShaderModuleDefinition.Parameter parameter) {
        boolean alpha = isAlphaParameter(parameter.key());
        int decimals = parameter.step() < 1.0F ? 2 : 0;
        return value -> {
            String name = Component.translatable(parameter.displayName()).getString();
            String shown = alpha
                ? ShaderPercentEditWidget.displayPercent(value) + "%"
                : formatNumber(value, decimals);
            return Component.literal(name + "  " + shown);
        };
    }

    private static String formatNumber(double value, int decimals) {
        return decimals <= 0
            ? String.valueOf((int) Math.round(value))
            : String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private ShaderModuleInstance selectedElementInLayer(ShaderLayer layer) {
        if (selectedElementIndex < 0 || selectedElementIndex >= layer.elements().size()) {
            return null;
        }
        return layer.elements().get(selectedElementIndex);
    }

    private void openElementParams(int elementIndex) {
        ShaderLayer layer = selectedLayer();
        if (layer == null || elementIndex < 0 || elementIndex >= layer.elements().size()) {
            return;
        }
        selectedElementIndex = elementIndex;
        elementParamView = true;
        paramScroll = 0;
        rebuildWidgets();
    }

    private void closeElementParams() {
        elementParamView = false;
        rebuildWidgets();
    }

    private void toggleElement(int elementIndex) {
        ShaderLayer layer = selectedLayer();
        if (layer == null || elementIndex < 0 || elementIndex >= layer.elements().size()) {
            return;
        }
        pushUndo();
        ShaderModuleInstance element = layer.elements().get(elementIndex);
        element.setEnabled(!element.enabled());
        markDirty("gui.eca.shader_generator.status.element_toggled");
        rebuildWidgets();
    }

    private void moveElement(int elementIndex, int offset) {
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            return;
        }
        int target = elementIndex + offset;
        if (elementIndex < 0 || elementIndex >= layer.elements().size()
            || target < 0 || target >= layer.elements().size()) {
            return;
        }
        pushUndo();
        layer.moveElement(elementIndex, offset);
        markDirty("gui.eca.shader_generator.status.layer_order_changed");
        rebuildWidgets();
    }

    private void removeElement(int elementIndex) {
        ShaderLayer layer = selectedLayer();
        if (layer == null || elementIndex < 0 || elementIndex >= layer.elements().size()) {
            return;
        }
        pushUndo();
        layer.removeElement(elementIndex);
        if (selectedElementIndex >= layer.elements().size()) {
            selectedElementIndex = layer.elements().size() - 1;
        }
        if (selectedElementIndex < 0) {
            elementParamView = false;
        }
        markDirty("gui.eca.shader_generator.status.element_removed");
        rebuildWidgets();
    }

    private void openElementColorPicker(ShaderModuleInstance element) {
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
            }
        ));
    }

    private void importElementImage(ShaderModuleInstance element) {
        ProjectRef reference = currentProjectRef();
        if (reference == null) {
            return;
        }
        try {
            String selected = TinyFileDialogs.tinyfd_openFileDialog(
                "Select PNG Image", null, null, "PNG Image (*.png)", false);
            if (selected != null) {
                pushUndo();
                element.setImagePath(ShaderProjectCodec.importTexture(reference, Path.of(selected)));
                markDirty("gui.eca.shader_generator.status.parameter_changed",
                    Component.translatable(element.definition().displayName()));
                rebuildWidgets();
            }
        } catch (Exception e) {
            EcaLogger.error("File dialog failed: {}", e.getMessage());
        }
    }

    private void setParameter(
        ShaderModuleInstance element,
        ShaderModuleDefinition.Parameter parameter,
        float value
    ) {
        beginEdit();
        element.setValue(parameter.key(), value);
        markDirty("gui.eca.shader_generator.status.parameter_changed",
            Component.translatable(parameter.displayName()));
    }

    private void setSelectedLayerAlpha(float alpha) {
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            return;
        }
        beginEdit();
        layer.setBaseColor(layer.baseRed(), layer.baseGreen(), layer.baseBlue(), alpha);
        markDirty("gui.eca.shader_generator.status.layer_base_changed");
    }

    private void cycleSelectedLayerBlendMode() {
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            return;
        }
        pushUndo();
        layer.setBlendMode(layer.blendMode().next());
        markDirty("gui.eca.shader_generator.status.layer_blend_changed");
        rebuildWidgets();
    }

    private void importSelectedLayerBackground() {
        ShaderLayer layer = selectedLayer();
        ProjectRef reference = currentProjectRef();
        if (layer == null || reference == null) {
            return;
        }
        try {
            String selected = TinyFileDialogs.tinyfd_openFileDialog(
                "Select PNG Image", null, null, "PNG Image (*.png)", false);
            if (selected != null) {
                pushUndo();
                layer.setBackgroundImagePath(
                    ShaderProjectCodec.importTexture(reference, Path.of(selected)));
                markDirty("gui.eca.shader_generator.status.layer_base_changed");
                rebuildWidgets();
            }
        } catch (Exception e) {
            EcaLogger.error("File dialog failed: {}", e.getMessage());
        }
    }

    private static List<ShaderModuleDefinition.Parameter> visibleParameters(ShaderModuleInstance element) {
        return element.definition().parameters().stream()
            .filter(parameter -> !isColorParameter(parameter.key()))
            .toList();
    }

    private static boolean isColorParameter(String key) {
        return key.equals("color_r")
            || key.equals("color_g")
            || key.equals("color_b")
            || key.equals("color_a");
    }

    private static boolean isAlphaParameter(String key) {
        return key.endsWith("_alpha") || key.equals("alpha");
    }

    private void beginLayerRename(int layerIndex) {
        if (layerIndex < 0 || layerIndex >= project.layers().size()) {
            return;
        }
        editingLayerIndex = layerIndex;
        pendingLayerNameFocus = true;
        lastLayerNameClickIndex = -1;
        lastLayerNameClickTime = 0L;
        rebuildWidgets();
    }

    private void handleLayerNameClick(int layerIndex) {
        long now = System.currentTimeMillis();
        boolean doubleClick = lastLayerNameClickIndex == layerIndex
            && now - lastLayerNameClickTime <= DOUBLE_CLICK_MS;
        selectLayerForInspector(layerIndex);
        ensureSelectedLayerVisible();
        if (doubleClick) {
            beginLayerRename(layerIndex);
            return;
        }
        lastLayerNameClickIndex = layerIndex;
        lastLayerNameClickTime = now;
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
        pendingLayerNameFocus = false;
        lastLayerNameClickIndex = -1;
        lastLayerNameClickTime = 0L;
        rebuildWidgets();
    }

    private void cancelLayerRename() {
        editingLayerIndex = -1;
        layerNameField = null;
        pendingLayerNameFocus = false;
        lastLayerNameClickIndex = -1;
        lastLayerNameClickTime = 0L;
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

    ProjectRef currentProjectRef() {
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
        selectLayerForInspector(
            Math.max(0, Math.min(selectedLayerIndex, project.layers().size() - 1)));
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
        lastCompileMs = System.currentTimeMillis();
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
        focusPendingLayerNameField();
        if (previewCompilePending
            && System.currentTimeMillis() - lastCompileMs >= COMPILE_MIN_INTERVAL_MS) {
            compileCurrentProject();
            rebuildWidgets();
        }
    }

    private void focusPendingLayerNameField() {
        if (!pendingLayerNameFocus || layerNameField == null) {
            return;
        }
        pendingLayerNameFocus = false;
        setFocused(layerNameField);
        layerNameField.setFocused(true);
        layerNameField.setCursorPosition(layerNameField.getValue().length());
        layerNameField.setHighlightPos(0);
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
        if (rightPanelMode == RightPanelMode.LAYER_LIST) {
            drawLayerRows(g);
        }

        Component projectLabel = currentProjectRef() == null
            ? Component.translatable("gui.eca.shader_generator.project.unnamed")
            : Component.literal(currentProjectRef().id());
        String projectText = this.font.plainSubstrByWidth(projectLabel.getString(), 112);
        g.drawString(this.font, projectText, 8, PROJECT_ROW_Y + 5, 0xFFCDD1D7, false);

        /* 2. 预览区域：视口固定作裁剪框，内容矩形按缩放/平移绘制 */
        PreviewRect viewport = previewViewport();
        PreviewRect content = previewBounds();
        g.fill(viewport.left() - 1, viewport.top() - 1, viewport.right() + 1, viewport.bottom() + 1, BORDER_COLOR);
        g.fill(viewport.left(), viewport.top(), viewport.right(), viewport.bottom(), 0xFF08090B);
        g.enableScissor(viewport.left(), viewport.top(), viewport.right(), viewport.bottom());
        ShaderPreviewRenderer.render(g, activeSource(), previewTarget,
            content.left(), content.top(), content.right(), content.bottom(), mx, my, pt);
        drawCanvasSelection(g, content);
        g.disableScissor();

        /* 3. 标签 + 文本 */
        Component panelLabel = rightPanelMode == RightPanelMode.LAYER_DETAIL
            ? Component.translatable("gui.eca.shader_generator.panel.layer_detail")
            : Component.translatable("gui.eca.shader_generator.panel.layers");
        g.drawString(this.font, panelLabel,
            this.width - RIGHT_WIDTH + 8, TOP_HEIGHT + 4, 0xFFBFC4CC, false);
        if (rightPanelMode == RightPanelMode.LAYER_DETAIL) {
            drawInspector(g);
        }

        /* 4. 普通控件 */
        super.render(g, mx, my, pt);

        /* 5. 独立深度层确保下拉菜单覆盖已经写入深度缓冲的普通控件 */
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 400.0F);
        drawDropdownBackground(g);
        renderDropdownWidgets(g, mx, my, pt);
        g.pose().popPose();
    }

    /* 检查器 chrome 与文本标签；交互控件由 super.render 绘制 */
    private void drawInspector(GuiGraphics g) {
        ShaderLayer layer = selectedLayer();
        int x = this.width - RIGHT_WIDTH + 8;
        int w = RIGHT_WIDTH - 16;
        g.fill(x, inspectorTop - 8, x + w, inspectorTop - 7, BORDER_COLOR);
        if (layer == null) {
            g.drawCenteredString(this.font,
                Component.translatable("gui.eca.shader_generator.inspector.no_layer"),
                x + w / 2, inspectorTop + 8, 0xFF9DA3AC);
            return;
        }
        ShaderModuleInstance element = selectedElementInLayer(layer);
        if ((!elementParamView || element == null) && layer.elements().isEmpty()) {
            g.drawCenteredString(this.font,
                Component.translatable("gui.eca.shader_generator.layer_editor.no_element"),
                x + w / 2, inspectorListTop + 6, 0xFF9DA3AC);
        }
    }

    /* ---------- canvas direct manipulation ---------- */

    /* 固定的画布视口(裁剪框)，几何仅由窗口尺寸决定，不受缩放/平移影响 */
    private PreviewRect previewViewport() {
        int al = LEFT_WIDTH + 8;
        int at = TOP_HEIGHT + 8;
        int ar = this.width - RIGHT_WIDTH - 8;
        int ab = this.height - BOTTOM_HEIGHT - 8;
        int ps = Math.max(32, Math.min(ar - al, ab - at));
        int left = al + (ar - al - ps) / 2;
        int top = at + (ab - at - ps) / 2;
        return new PreviewRect(left, top, left + ps, top + ps);
    }

    /* 应用缩放/平移后的内容矩形，可能超出视口；UV 映射与命中测试均基于此 */
    private PreviewRect previewBounds() {
        PreviewRect viewport = previewViewport();
        double size = (viewport.right() - viewport.left()) * canvasZoom;
        double centerX = (viewport.left() + viewport.right()) / 2.0 + canvasPanX;
        double centerY = (viewport.top() + viewport.bottom()) / 2.0 + canvasPanY;
        int left = (int) Math.round(centerX - size / 2.0);
        int top = (int) Math.round(centerY - size / 2.0);
        int right = (int) Math.round(centerX + size / 2.0);
        int bottom = (int) Math.round(centerY + size / 2.0);
        return new PreviewRect(left, top, right, bottom);
    }

    private void zoomCanvasAt(double screenX, double screenY, double rawFactor) {
        double newZoom = Mth.clamp(canvasZoom * rawFactor, CANVAS_ZOOM_MIN, CANVAS_ZOOM_MAX);
        double factor = newZoom / canvasZoom;
        PreviewRect viewport = previewViewport();
        double viewportCenterX = (viewport.left() + viewport.right()) / 2.0;
        double viewportCenterY = (viewport.top() + viewport.bottom()) / 2.0;
        canvasPanX = factor * canvasPanX + (1.0 - factor) * (screenX - viewportCenterX);
        canvasPanY = factor * canvasPanY + (1.0 - factor) * (screenY - viewportCenterY);
        canvasZoom = newZoom;
    }

    private boolean resetCanvasView() {
        if (canvasZoom == 1.0 && canvasPanX == 0.0 && canvasPanY == 0.0) {
            return false;
        }
        canvasZoom = 1.0;
        canvasPanX = 0.0;
        canvasPanY = 0.0;
        statusError = false;
        status = Component.translatable("gui.eca.shader_generator.status.view_reset");
        return true;
    }

    private void drawCanvasSelection(GuiGraphics g, PreviewRect preview) {
        if (rightPanelMode != RightPanelMode.LAYER_DETAIL) {
            return;
        }
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            return;
        }
        ShaderModuleInstance element = selectedElementInLayer(layer);
        if (element == null || !containsPositionParameters(element)) {
            return;
        }
        PreviewRect box = elementScreenBox(element, preview);
        int left = box.left();
        int top = box.top();
        int right = box.right();
        int bottom = box.bottom();
        g.renderOutline(left, top, Math.max(1, right - left), Math.max(1, bottom - top), 0xFFE6E9ED);

        drawHandle(g, left, top);
        drawHandle(g, right, top);
        drawHandle(g, right, bottom);
        drawHandle(g, left, bottom);
        if (element.values().containsKey("rotation")) {
            int handleX = (left + right) / 2;
            int handleY = top - CANVAS_ROTATE_HANDLE_OFFSET;
            g.fill(handleX, handleY, handleX + 1, top, 0xFFE6E9ED);
            drawHandle(g, handleX, handleY, 0xFF4D8DFF);
        }
    }

    /* 元素选框在屏幕空间的矩形，绘制与手柄命中共用以保证几何一致 */
    private PreviewRect elementScreenBox(ShaderModuleInstance element, PreviewRect content) {
        double rx = elementRadiusX(element);
        double ry = elementRadiusY(element);
        int spanX = content.right() - content.left();
        int spanY = content.bottom() - content.top();
        int left = content.left() + (int) Math.round((element.value("center_x") - rx) * spanX);
        int top = content.top() + (int) Math.round((element.value("center_y") - ry) * spanY);
        int right = content.left() + (int) Math.round((element.value("center_x") + rx) * spanX);
        int bottom = content.top() + (int) Math.round((element.value("center_y") + ry) * spanY);
        return new PreviewRect(left, top, right, bottom);
    }

    /* 命中选框手柄：0=左上 1=右上 2=右下 3=左下 4=旋转，未命中返回 -1 */
    private int hitCanvasHandle(ShaderModuleInstance element, PreviewRect content, double mouseX, double mouseY) {
        PreviewRect box = elementScreenBox(element, content);
        int[][] corners = {
            {box.left(), box.top()},
            {box.right(), box.top()},
            {box.right(), box.bottom()},
            {box.left(), box.bottom()}
        };
        for (int i = 0; i < corners.length; i++) {
            if (nearHandle(mouseX, mouseY, corners[i][0], corners[i][1])) {
                return i;
            }
        }
        if (element.values().containsKey("rotation")) {
            int handleX = (box.left() + box.right()) / 2;
            int handleY = box.top() - CANVAS_ROTATE_HANDLE_OFFSET;
            if (nearHandle(mouseX, mouseY, handleX, handleY)) {
                return 4;
            }
        }
        return -1;
    }

    private static boolean nearHandle(double mouseX, double mouseY, int handleX, int handleY) {
        int reach = CANVAS_HANDLE_SIZE;
        return Math.abs(mouseX - handleX) <= reach && Math.abs(mouseY - handleY) <= reach;
    }

    private void drawHandle(GuiGraphics g, int cx, int cy) {
        drawHandle(g, cx, cy, 0xFFE6E9ED);
    }

    private void drawHandle(GuiGraphics g, int cx, int cy, int color) {
        int half = CANVAS_HANDLE_SIZE / 2;
        g.fill(cx - half - 1, cy - half - 1, cx + half + 1, cy + half + 1, 0xFF111315);
        g.fill(cx - half, cy - half, cx + half, cy + half, color);
    }

    /* 命中测试：返回选中图层内最上层被点中的、含位置参数的元素索引 */
    private int hitCanvasElement(ShaderLayer layer, PreviewRect preview, double mouseX, double mouseY) {
        double uvX = preview.uvX(mouseX);
        double uvY = preview.uvY(mouseY);
        List<ShaderModuleInstance> elements = layer.elements();
        for (int i = elements.size() - 1; i >= 0; i--) {
            ShaderModuleInstance element = elements.get(i);
            if (element.enabled() && containsPositionParameters(element)
                && hitElementBounds(element, uvX, uvY)) {
                return i;
            }
        }
        return -1;
    }

    private boolean handleCanvasClick(double mouseX, double mouseY) {
        /* 元素直接操纵只在图层详细编辑模式下开放；总览模式画布只读 */
        if (rightPanelMode != RightPanelMode.LAYER_DETAIL) {
            return false;
        }
        /* 视口是固定裁剪框，缩放后内容虽可溢出视口，但仅视口内响应画布交互 */
        if (!previewViewport().contains(mouseX, mouseY)) {
            return false;
        }
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            return false;
        }
        PreviewRect content = previewBounds();
        /* 优先命中已选元素的手柄（缩放/旋转），再命中元素本体 */
        ShaderModuleInstance selected = selectedElementInLayer(layer);
        if (selected != null && containsPositionParameters(selected)) {
            int handle = hitCanvasHandle(selected, content, mouseX, mouseY);
            if (handle == 4) {
                beginHandleDrag(selectedElementIndex, CanvasDragMode.ROTATE, content, mouseX, mouseY);
                return true;
            }
            if (handle >= 0) {
                beginHandleDrag(selectedElementIndex, CanvasDragMode.SCALE, content, mouseX, mouseY);
                return true;
            }
        }
        int index = hitCanvasElement(layer, content, mouseX, mouseY);
        if (index >= 0) {
            ShaderModuleInstance element = layer.elements().get(index);
            selectedElementIndex = index;
            elementParamView = true;
            draggingElementIndex = index;
            canvasDragMode = CanvasDragMode.MOVE;
            dragUndoStarted = false;
            dragOffsetX = content.uvX(mouseX) - element.value("center_x");
            dragOffsetY = content.uvY(mouseY) - element.value("center_y");
            rebuildWidgets();
            return true;
        }
        /* 点击画布空白：清除元素选择，退出参数视图回到图层属性 */
        resetDragState();
        boolean changed = elementParamView || selectedElementIndex >= 0;
        elementParamView = false;
        selectedElementIndex = -1;
        if (changed) {
            rebuildWidgets();
        }
        return true;
    }

    private void beginHandleDrag(int index, CanvasDragMode mode, PreviewRect content, double mouseX, double mouseY) {
        ShaderLayer layer = selectedLayer();
        if (layer == null || index < 0 || index >= layer.elements().size()) {
            return;
        }
        ShaderModuleInstance element = layer.elements().get(index);
        draggingElementIndex = index;
        canvasDragMode = mode;
        dragUndoStarted = false;
        double centerX = content.left() + element.value("center_x") * (content.right() - content.left());
        double centerY = content.top() + element.value("center_y") * (content.bottom() - content.top());
        if (mode == CanvasDragMode.SCALE) {
            dragStartSize = element.value("size");
            dragStartDist = Math.max(1.0, Math.hypot(mouseX - centerX, mouseY - centerY));
        } else {
            dragStartRotation = element.value("rotation");
            dragStartAngle = Math.atan2(mouseY - centerY, mouseX - centerX);
        }
        elementParamView = true;
        rebuildWidgets();
    }

    private ShaderModuleInstance draggingElement(ShaderLayer layer) {
        if (layer == null || draggingElementIndex < 0 || draggingElementIndex >= layer.elements().size()) {
            return null;
        }
        return layer.elements().get(draggingElementIndex);
    }

    private void resetDragState() {
        draggingElementIndex = -1;
        dragUndoStarted = false;
        canvasDragMode = CanvasDragMode.MOVE;
    }

    private void scaleCanvasElement(double mouseX, double mouseY) {
        PreviewRect content = previewBounds();
        ShaderModuleInstance element = draggingElement(selectedLayer());
        if (element == null || !containsPositionParameters(element)) {
            resetDragState();
            return;
        }
        if (!dragUndoStarted) {
            pushUndo();
            dragUndoStarted = true;
        }
        double centerX = content.left() + element.value("center_x") * (content.right() - content.left());
        double centerY = content.top() + element.value("center_y") * (content.bottom() - content.top());
        double dist = Math.hypot(mouseX - centerX, mouseY - centerY);
        element.setValue("size", (float) (dragStartSize * dist / dragStartDist));
        markDirty("gui.eca.shader_generator.status.parameter_changed",
            Component.translatable(element.definition().displayName()));
    }

    private void rotateCanvasElement(double mouseX, double mouseY) {
        PreviewRect content = previewBounds();
        ShaderModuleInstance element = draggingElement(selectedLayer());
        if (element == null || !element.values().containsKey("rotation")) {
            resetDragState();
            return;
        }
        if (!dragUndoStarted) {
            pushUndo();
            dragUndoStarted = true;
        }
        double centerX = content.left() + element.value("center_x") * (content.right() - content.left());
        double centerY = content.top() + element.value("center_y") * (content.bottom() - content.top());
        double angle = Math.atan2(mouseY - centerY, mouseX - centerX);
        double rotated = dragStartRotation + Math.toDegrees(angle - dragStartAngle);
        element.setValue("rotation", (float) ((rotated % 360.0 + 360.0) % 360.0));
        markDirty("gui.eca.shader_generator.status.parameter_changed",
            Component.translatable(element.definition().displayName()));
    }

    private void dragCanvasElement(double mouseX, double mouseY) {
        PreviewRect preview = previewBounds();
        ShaderLayer layer = selectedLayer();
        ShaderModuleInstance element = layer != null
            && draggingElementIndex >= 0
            && draggingElementIndex < layer.elements().size()
            ? layer.elements().get(draggingElementIndex)
            : null;
        if (element == null || !containsPositionParameters(element)) {
            draggingElementIndex = -1;
            dragUndoStarted = false;
            return;
        }
        if (!dragUndoStarted) {
            pushUndo();
            dragUndoStarted = true;
        }
        element.setValue("center_x", (float) (preview.uvX(mouseX) - dragOffsetX));
        element.setValue("center_y", (float) (preview.uvY(mouseY) - dragOffsetY));
        markDirty("gui.eca.shader_generator.status.parameter_changed",
            Component.translatable(element.definition().displayName()));
    }

    private static boolean containsPositionParameters(ShaderModuleInstance element) {
        return element.values().containsKey("center_x")
            && element.values().containsKey("center_y")
            && element.values().containsKey("size");
    }

    private static boolean hitElementBounds(ShaderModuleInstance element, double uvX, double uvY) {
        double centerX = element.value("center_x");
        double centerY = element.value("center_y");
        double radiusX = elementRadiusX(element);
        double radiusY = elementRadiusY(element);
        return uvX >= centerX - radiusX && uvX <= centerX + radiusX
            && uvY >= centerY - radiusY && uvY <= centerY + radiusY;
    }

    private static double elementRadiusX(ShaderModuleInstance element) {
        double base = element.value("size") * 0.5D + element.value("spread_x");
        if (element.values().containsKey("width")) {
            base = Math.max(base, element.value("size") * element.value("width") * 0.5D);
        }
        if (element.values().containsKey("length")) {
            base = Math.max(base, element.value("size") * element.value("length") * 0.5D);
        }
        return Math.max(0.02D, base);
    }

    private static double elementRadiusY(ShaderModuleInstance element) {
        double base = element.value("size") * 0.5D + element.value("spread_y");
        if (element.values().containsKey("height")) {
            base = Math.max(base, element.value("size") * element.value("height") * 0.5D);
        }
        if (element.values().containsKey("thickness")) {
            base = Math.max(base, element.value("size") * element.value("thickness"));
        }
        return Math.max(0.02D, base);
    }

    private enum CanvasDragMode {
        MOVE,
        SCALE,
        ROTATE
    }

    private enum RightPanelMode {
        LAYER_LIST,
        LAYER_DETAIL
    }

    private record PreviewRect(int left, int top, int right, int bottom) {

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
        }

        double uvX(double mouseX) {
            return (mouseX - left) / Math.max(1.0D, right - left);
        }

        double uvY(double mouseY) {
            return (mouseY - top) / Math.max(1.0D, bottom - top);
        }
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

}
