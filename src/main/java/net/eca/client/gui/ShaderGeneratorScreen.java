package net.eca.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.eca.client.render.shader_generator.GeneratedShaderPreview;
import net.eca.client.render.shader_generator.ShaderPreviewRenderer;
import net.eca.client.render.shader_generator.ShaderPreviewSource;
import net.eca.client.render.shader_generator.ShaderPreviewSourceCatalog;
import net.eca.client.render.shader_generator.ShaderPreviewTarget;
import net.eca.client.render.shader_generator.ShaderPreviewDependencyResolver;
import net.eca.util.EcaLogger;
import net.eca.util.shader_generator.ShaderBlendMode;
import net.eca.util.shader_generator.ShaderCompositionProject;
import net.eca.util.shader_generator.ShaderExportBundle;
import net.eca.util.shader_generator.ShaderFolderImporter;
import net.eca.util.shader_generator.ShaderFolderImporter.Candidate;
import net.eca.util.shader_generator.ShaderGenerator;
import net.eca.util.shader_generator.ShaderLayer;
import net.eca.util.shader_generator.ShaderModuleDefinition;
import net.eca.util.shader_generator.ShaderModuleInstance;
import net.eca.util.shader_generator.ShaderModuleRegistry;
import net.eca.util.shader_generator.ShaderOutputEffectDefinition;
import net.eca.util.shader_generator.ShaderOutputEffectInstance;
import net.eca.util.shader_generator.ShaderOutputEffectRegistry;
import net.eca.util.shader_generator.ShaderProject;
import net.eca.util.shader_generator.ShaderProjectCodec;
import net.eca.util.shader_generator.ShaderProjectCodec.ProjectRef;
import net.eca.util.shader_generator.ShaderTargetProfile;
import net.eca.util.shader_generator.ShaderSourceAssembler;
import net.eca.util.shader_generator.ShaderSourceFile;
import net.eca.util.shader_generator.ShaderSourceWorkspace;
import net.eca.util.shader_generator.ai.ShaderAiModuleMetadata;
import net.eca.util.shader_generator.ai.ShaderAiToolContext;
import net.eca.util.shader_generator.ai.ShaderAiToolResult;
import net.eca.util.shader_generator.mcp.ShaderMcpServer;
import net.eca.util.shader_generator.mcp.ShaderMcpSessionInfo;
import net.eca.util.shader_generator.mcp.ShaderMcpSettings;
import net.eca.util.shader_generator.mcp.ShaderMcpSettingsCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.joml.Quaternionf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@OnlyIn(Dist.CLIENT)
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
    private static final int OUTPUT_EFFECT_ROW_HEIGHT = 22;
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int RIGHT_PANEL_GAP = 12;
    private static final int OUTPUT_EFFECT_PANEL_MIN_HEIGHT = 126;
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
    private final Deque<ShaderCompositionProject> aiUndoStack = new ArrayDeque<>();
    private final Deque<ShaderCompositionProject> aiRedoStack = new ArrayDeque<>();
    private final List<LayerRowVisual> visibleLayerRows = new ArrayList<>();
    private final List<ElementRowVisual> visibleElementRows = new ArrayList<>();
    private final List<OutputEffectRowVisual> visibleOutputEffectRows = new ArrayList<>();
    private List<ShaderPreviewSource> registeredSources = List.of();
    private GeneratedShaderPreview generatedPreview;
    private ShaderAiSession aiSession;
    private final ShaderMcpServer mcpServer = new ShaderMcpServer();
    private ShaderMcpSettings mcpSettings;
    private boolean closed;
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
    private int outputEffectScroll;
    private int outputEffectListTop;
    private int outputEffectListBottom;
    private int outputEffectVisibleRows = 1;
    private ScrollbarTarget scrollbarDragTarget = ScrollbarTarget.NONE;
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
    /* 下拉菜单: -1=无, 0=文件, 1=编辑, 2=图层, 3=预览视图, 4=元素, 5=效果 */
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
        if (rightPanelMode == RightPanelMode.LAYER_LIST) {
            addOutputEffectPanel();
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
        x = addMenuButton(x, "gui.eca.shader_generator.menu.elements", 4, 56);
        x = addMenuButton(x, "gui.eca.shader_generator.menu.effects", 5, 56);
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

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.ai.button"),
            button -> openAiAssistant()
        ).bounds(this.width - 176, PROJECT_ROW_Y, 68, MENU_ITEM_H).build());

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
            case 5 -> addOutputEffectDropdown();
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
            Component.translatable("gui.eca.shader_generator.file.import_shader"), () -> {
                openDropdown = -1;
                openShaderFolderImport();
            });
        row++;

        dropdownOption(mx, y + row * MENU_ITEM_H, w,
            Component.translatable("gui.eca.shader_generator.file.source_editor"), () -> {
                openDropdown = -1;
                openSourceEditor();
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
        row++;

        dropdownOption(mx, y + row * MENU_ITEM_H, w,
            Component.translatable("gui.eca.shader_generator.file.delete"), () -> {
                openDropdown = -1;
                requestDeleteProject();
            });
        row++;

        dropdownOption(mx, y + row * MENU_ITEM_H, w,
            Component.translatable("gui.eca.shader_generator.file.export_as",
                currentProjectRef() == null ? "?" : currentProjectRef().shaderName()), () -> {
                openDropdown = -1;
                exportFiveFiles();
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

    /* 元素：基础/星空/魔法点击展开列表，自定义直接进入详情编辑 */
    private void addEffectDropdown() {
        int mx = 180;
        int y = MENU_Y + MENU_ITEM_H + 2;
        int width = 130;

        ShaderModuleDefinition.Category[] categories = ShaderModuleDefinition.Category.values();
        for (int i = 0; i < categories.length; i++) {
            ShaderModuleDefinition.Category category = categories[i];
            int rowY = y + i * MENU_ITEM_H;
            if (category == ShaderModuleDefinition.Category.IMAGE) {
                /* 自定义分类：直接进入详情编辑，不展开效果列表 */
                ShaderModuleDefinition imageDef = ShaderModuleRegistry.get("image_element");
                if (imageDef != null) {
                    dropdownOption(mx, rowY, width,
                        Component.translatable(category.translationKey()),
                        () -> {
                            openDropdown = -1;
                            expandedEffectCategory = null;
                            selectEffectFromMenu(imageDef);
                        });
                }
            } else {
                dropdownOption(mx, rowY, width,
                    Component.translatable(category.translationKey()),
                    () -> {
                        expandedEffectCategory = (expandedEffectCategory == category) ? null : category;
                        rebuildWidgets();
                    });
            }
        }

        /* 点击基础/星空/魔法分类后右侧展开效果列表 */
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

    private void addOutputEffectDropdown() {
        int mx = 238;
        int y = MENU_Y + MENU_ITEM_H + 2;
        int width = 176;
        List<ShaderOutputEffectDefinition> definitions = ShaderOutputEffectRegistry.all();
        int row = 0;
        for (ShaderOutputEffectDefinition definition : definitions) {
            if (outputEffectIndex(definition.id()) >= 0) {
                continue;
            }
            dropdownOption(mx, y + row * MENU_ITEM_H, width,
                Component.translatable(definition.displayName()), () -> {
                openDropdown = -1;
                selectOutputEffect(definition);
            });
            row++;
        }
    }

    private void selectOutputEffect(ShaderOutputEffectDefinition definition) {
        int index = outputEffectIndex(definition.id());
        ShaderOutputEffectInstance effect = index >= 0
            ? project.outputEffects().get(index)
            : definition.createInstance();
        openOutputEffectDetails(effect, index);
    }

    private int outputEffectIndex(String definitionId) {
        for (int index = 0; index < project.outputEffects().size(); index++) {
            if (project.outputEffects().get(index).definition().id().equals(definitionId)) {
                return index;
            }
        }
        return -1;
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
        if (button == 0 && openDropdown < 0 && beginScrollbarDrag(mx, my)) {
            return true;
        }
        if (button == 0 && handleCanvasClick(mx, my)) {
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (button == 0 && scrollbarDragTarget != ScrollbarTarget.NONE) {
            updateScrollbarScroll(my);
            return true;
        }
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
        if (button == 0 && scrollbarDragTarget != ScrollbarTarget.NONE) {
            scrollbarDragTarget = ScrollbarTarget.NONE;
            return true;
        }
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
            if (rightPanelMode == RightPanelMode.LAYER_LIST
                    && mouseY >= outputEffectListTop && mouseY < outputEffectListBottom) {
                int maxScroll = Math.max(0, project.outputEffects().size() - outputEffectVisibleRows);
                int next = Math.max(0, Math.min(maxScroll, outputEffectScroll + direction));
                if (next != outputEffectScroll) {
                    outputEffectScroll = next;
                    rebuildWidgets();
                }
                return true;
            }
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

    private boolean beginScrollbarDrag(double mouseX, double mouseY) {
        ScrollbarGeometry upper = upperScrollbarGeometry();
        if (isInsideScrollbar(upper, mouseX, mouseY)) {
            scrollbarDragTarget = ScrollbarTarget.UPPER;
            updateScrollbarScroll(mouseY);
            return true;
        }
        ScrollbarGeometry output = outputEffectScrollbarGeometry();
        if (isInsideScrollbar(output, mouseX, mouseY)) {
            scrollbarDragTarget = ScrollbarTarget.OUTPUT_EFFECTS;
            updateScrollbarScroll(mouseY);
            return true;
        }
        return false;
    }

    private static boolean isInsideScrollbar(ScrollbarGeometry geometry, double mouseX, double mouseY) {
        return geometry != null
            && mouseX >= geometry.x() && mouseX <= geometry.x() + SCROLLBAR_WIDTH
            && mouseY >= geometry.top() && mouseY <= geometry.bottom();
    }

    private void updateScrollbarScroll(double mouseY) {
        ScrollbarGeometry geometry = scrollbarDragTarget == ScrollbarTarget.UPPER
            ? upperScrollbarGeometry()
            : outputEffectScrollbarGeometry();
        if (geometry == null) {
            return;
        }
        int maxScroll = Math.max(0, geometry.total() - geometry.visible());
        int travel = Math.max(1, geometry.bottom() - geometry.top()
            - (geometry.thumbBottom() - geometry.thumbTop()));
        double fraction = (mouseY - geometry.top() - (geometry.thumbBottom() - geometry.thumbTop()) * 0.5) / travel;
        int next = Mth.clamp((int) Math.round(fraction * maxScroll), 0, maxScroll);
        if (scrollbarDragTarget == ScrollbarTarget.OUTPUT_EFFECTS) {
            if (next != outputEffectScroll) {
                outputEffectScroll = next;
                rebuildWidgets();
            }
            return;
        }
        if (rightPanelMode == RightPanelMode.LAYER_LIST) {
            if (next != layerScroll) {
                layerScroll = next;
                rebuildWidgets();
            }
            return;
        }
        if (elementParamView) {
            if (next != paramScroll) {
                paramScroll = next;
                rebuildWidgets();
            }
        } else if (next != elementScroll) {
            elementScroll = next;
            rebuildWidgets();
        }
    }

    /* ---------- right: layer stack ---------- */

    private int rightPanelBottom() {
        return this.height - BOTTOM_HEIGHT - 8;
    }

    private int outputEffectPanelTop() {
        int available = rightPanelBottom() - (TOP_HEIGHT + 20);
        int height = Math.max(OUTPUT_EFFECT_PANEL_MIN_HEIGHT, available * 2 / 5);
        height = Math.min(height, Math.max(OUTPUT_EFFECT_PANEL_MIN_HEIGHT, available - 72));
        return rightPanelBottom() - height;
    }

    private int upperPanelBottom() {
        return rightPanelMode == RightPanelMode.LAYER_DETAIL
            ? rightPanelBottom() : outputEffectPanelTop() - RIGHT_PANEL_GAP;
    }

    /* LAYER_LIST 模式：图层列表独占整条右栏，带滚动 */
    private void addLayerListPanel() {
        int px = this.width - RIGHT_WIDTH + 8;
        int y = TOP_HEIGHT + 20;
        int lw = RIGHT_WIDTH - 16;
        List<ShaderLayer> layers = project.layers();
        int panelBottom = upperPanelBottom();

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
            addLayerRow(px, rowY, lw - SCROLLBAR_WIDTH - 2, layers.get(li), li);
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
        int w = RIGHT_WIDTH - 16 - SCROLLBAR_WIDTH - 2;
        int y = TOP_HEIGHT + 20;
        int panelBottom = upperPanelBottom();

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.layer.back"),
            button -> exitLayerDetail()
        ).bounds(x, y, 40, 18).build());

        int deleteWidth = 56;
        int headerNameX = x + 44;
        int headerNameW = w - 44 - deleteWidth - 4;
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
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.layer.delete"),
            button -> removeSelectedLayer()
        ).bounds(x + w - deleteWidth, y, deleteWidth, 18).build());

        inspectorTop = y + 24;
        inspectorBottom = panelBottom;
        addInspectorPanel();
    }

    private void addOutputEffectPanel() {
        int x = this.width - RIGHT_WIDTH + 8;
        int w = RIGHT_WIDTH - 16;
        int top = outputEffectPanelTop();
        int bottom = rightPanelBottom();
        int listWidth = w - SCROLLBAR_WIDTH - 2;

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.output_effect.add"),
            button -> {
                openDropdown = 5;
                rebuildWidgets();
            }
        ).bounds(x, top + 18, listWidth, 18).build());

        outputEffectListTop = top + 42;
        outputEffectListBottom = bottom;
        outputEffectVisibleRows = Math.max(0,
            (outputEffectListBottom - outputEffectListTop) / OUTPUT_EFFECT_ROW_HEIGHT);
        int maxScroll = Math.max(0, project.outputEffects().size() - outputEffectVisibleRows);
        outputEffectScroll = Math.max(0, Math.min(outputEffectScroll, maxScroll));
        visibleOutputEffectRows.clear();

        int end = Math.min(project.outputEffects().size(), outputEffectScroll + outputEffectVisibleRows);
        for (int index = outputEffectScroll; index < end; index++) {
            int rowY = outputEffectListTop + (index - outputEffectScroll) * OUTPUT_EFFECT_ROW_HEIGHT;
            addOutputEffectRow(x, rowY, listWidth, project.outputEffects().get(index), index);
        }
    }

    private void addOutputEffectRow(
        int x,
        int y,
        int width,
        ShaderOutputEffectInstance effect,
        int effectIndex
    ) {
        int color = outputEffectColor(effect.definition().stage());
        visibleOutputEffectRows.add(new OutputEffectRowVisual(x, y, width, 18, effectIndex, color));
        addRenderableWidget(Button.builder(Component.literal(effect.enabled() ? "●" : "○"),
            button -> toggleOutputEffect(effectIndex))
            .bounds(x + 3, y, 18, 18).build());
        addRenderableWidget(Button.builder(Component.literal("↑"),
            button -> moveOutputEffect(effectIndex, -1))
            .bounds(x + 23, y, 18, 18).build());
        addRenderableWidget(Button.builder(Component.literal("↓"),
            button -> moveOutputEffect(effectIndex, 1))
            .bounds(x + 43, y, 18, 18).build());
        addRenderableWidget(Button.builder(
            Component.translatable(effect.definition().displayName()),
            button -> openOutputEffectDetails(effect, effectIndex)
        ).bounds(x + 63, y, Math.max(42, width - 131), 18).build());
        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.output_effect.edit"),
            button -> openOutputEffectDetails(effect, effectIndex)
        ).bounds(x + width - 64, y, 42, 18).build());
        addRenderableWidget(Button.builder(Component.literal("×"),
            button -> removeOutputEffect(effectIndex))
            .bounds(x + width - 20, y, 18, 18).build());
    }

    private static int outputEffectColor(ShaderOutputEffectDefinition.Stage stage) {
        return switch (stage) {
            case UV -> 0xFF3BC9DB;
            case RESAMPLE -> 0xFFFFA94D;
            case COLOR -> 0xFF9B6DFF;
        };
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
        int w = RIGHT_WIDTH - 16 - SCROLLBAR_WIDTH - 2;
        if (elementParamView && selectedElementInLayer(layer) != null) {
            addElementParamInspector(layer, x, w);
        } else {
            addLayerInspector(layer, x, w);
        }
    }

    private void addLayerInspector(ShaderLayer layer, int x, int w) {
        boolean compact = inspectorBottom - inspectorTop < 142;
        int rowH = compact ? 16 : 20;
        int pitch = compact ? 18 : 24;
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
        inspectorVisibleRows = Math.max(
            0, (inspectorListBottom - inspectorListTop) / listPitch
        );
        visibleElementRows.clear();
        List<ShaderModuleInstance> elements = layer.elements();
        int maxScroll = Math.max(0, elements.size() - inspectorVisibleRows);
        elementScroll = Math.max(0, Math.min(elementScroll, maxScroll));
        int end = Math.min(elements.size(), elementScroll + inspectorVisibleRows);
        for (int i = elementScroll; i < end; i++) {
            int elementIndex = i;
            ShaderModuleInstance element = elements.get(i);
            int ry = inspectorListTop + (i - elementScroll) * listPitch;
            boolean selected = elementIndex == selectedElementIndex && !elementParamView;
            int rowColor = LAYER_COLORS[Math.floorMod(elementIndex, LAYER_COLORS.length)];

            visibleElementRows.add(new ElementRowVisual(x, ry, w, 20, elementIndex, rowColor));

            /* 启用/禁用切换，用 ●/○ 视觉提示 */
            addRenderableWidget(Button.builder(
                Component.literal(element.enabled() ? "●" : "○"),
                button -> toggleElement(elementIndex)
            ).bounds(x + 3, ry + 1, 18, 18).build());

            addRenderableWidget(Button.builder(
                Component.literal("↑"),
                button -> moveElement(elementIndex, -1)
            ).bounds(x + 23, ry + 1, 18, 18).build());

            addRenderableWidget(Button.builder(
                Component.literal("↓"),
                button -> moveElement(elementIndex, 1)
            ).bounds(x + 43, ry + 1, 18, 18).build());

            int nameX = x + 63;
            int deleteW = 22;
            int nameW = w - 63 - deleteW - 2;
            Component label = Component.translatable(element.definition().displayName());
            if (!element.enabled()) {
                label = label.copy().append(
                    Component.translatable("gui.eca.shader_generator.layer.disabled"));
            }
            addRenderableWidget(new LayerSelectWidget(
                nameX, ry + 1, nameW, 18, label,
                () -> openElementParams(elementIndex)
            ));

            addRenderableWidget(Button.builder(
                Component.literal("✕"),
                button -> removeElement(elementIndex)
            ).bounds(x + w - deleteW - 2, ry + 1, deleteW, 18).build());
        }
    }

    private void addElementParamInspector(ShaderLayer layer, int x, int w) {
        ShaderModuleInstance element = selectedElementInLayer(layer);
        int fixedRows = element.definition().id().equals("image_element") ? 3 : 2;
        boolean compact = inspectorBottom - inspectorTop < fixedRows * 24 + 44;
        int rowH = compact ? 16 : 20;
        int pitch = compact ? 18 : 24;
        int y = inspectorTop;

        addRenderableWidget(Button.builder(
            Component.literal("<"),
            button -> closeElementParams()
        ).bounds(x, y, 40, rowH).build());
        y += pitch;

        /* 自定义图像元素：导入/替换图片按钮放在最前面 */
        if (element.definition().id().equals("image_element")) {
            addRenderableWidget(Button.builder(
                Component.translatable(element.imagePath() == null
                    ? "gui.eca.shader_generator.effects.import_image"
                    : "gui.eca.shader_generator.effects.replace_image"),
                button -> importElementImage(element)
            ).bounds(x, y, w, rowH).build());
            y += pitch;
        }

        addRenderableWidget(Button.builder(
            Component.translatable("gui.eca.shader_generator.parameter.color"),
            button -> openElementColorPicker(element)
        ).bounds(x, y, w, rowH).build());
        y += pitch;

        inspectorListTop = y;
        inspectorListBottom = inspectorBottom;
        int listPitch = 22;
        List<ShaderModuleDefinition.Parameter> params = visibleParameters(element);
        inspectorVisibleRows = Math.max(
            0, (inspectorListBottom - inspectorListTop) / listPitch
        );
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

    private void openOutputEffectDetails(ShaderOutputEffectInstance effect, int existingIndex) {
        minecraft.setScreen(new ShaderOutputEffectDetailsScreen(
            this,
            this,
            effect,
            updatedEffect -> {
                pushUndo();
                if (existingIndex >= 0) {
                    project.replaceOutputEffect(existingIndex, updatedEffect);
                    markDirty("gui.eca.shader_generator.status.output_effect_changed",
                        Component.translatable(updatedEffect.definition().displayName()));
                } else {
                    project.addOutputEffect(updatedEffect);
                    markDirty("gui.eca.shader_generator.status.output_effect_added",
                        Component.translatable(updatedEffect.definition().displayName()));
                }
                rebuildWidgets();
            },
            existingIndex < 0 ? null : () -> {
                pushUndo();
                project.removeOutputEffect(existingIndex);
                markDirty("gui.eca.shader_generator.status.output_effect_removed",
                    Component.translatable(effect.definition().displayName()));
                rebuildWidgets();
            }
        ));
    }

    private void toggleOutputEffect(int index) {
        if (index < 0 || index >= project.outputEffects().size()) {
            return;
        }
        pushUndo();
        ShaderOutputEffectInstance effect = project.outputEffects().get(index);
        effect.setEnabled(!effect.enabled());
        markDirty("gui.eca.shader_generator.status.output_effect_changed",
            Component.translatable(effect.definition().displayName()));
        rebuildWidgets();
    }

    private void removeOutputEffect(int index) {
        if (index < 0 || index >= project.outputEffects().size()) {
            return;
        }
        ShaderOutputEffectInstance effect = project.outputEffects().get(index);
        pushUndo();
        project.removeOutputEffect(index);
        markDirty("gui.eca.shader_generator.status.output_effect_removed",
            Component.translatable(effect.definition().displayName()));
        rebuildWidgets();
    }

    private void moveOutputEffect(int index, int direction) {
        if (index < 0 || index >= project.outputEffects().size() || direction == 0) {
            return;
        }
        ShaderOutputEffectDefinition.Stage stage = project.outputEffects().get(index).definition().stage();
        int candidate = index + direction;
        while (candidate >= 0 && candidate < project.outputEffects().size()) {
            if (project.outputEffects().get(candidate).definition().stage() == stage) {
                pushUndo();
                project.moveOutputEffect(index, candidate - index);
                markDirty("gui.eca.shader_generator.status.output_effect_changed",
                    Component.translatable(project.outputEffects().get(candidate).definition().displayName()));
                rebuildWidgets();
                return;
            }
            candidate += direction;
        }
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
        clearAiHistory();
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

    private void requestDeleteProject() {
        ProjectRef reference = currentProjectRef();
        if (reference == null) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.project_required");
            rebuildWidgets();
            return;
        }
        Path directory = ShaderProjectCodec.projectPath(reference);
        ConfirmScreen confirm = new ConfirmScreen(
            confirmed -> {
                if (confirmed) deleteProject(reference);
                minecraft.setScreen(this);
            },
            Component.translatable("gui.eca.shader_generator.delete.title"),
            Component.translatable(
                "gui.eca.shader_generator.delete.body", reference.id(), directory
            ),
            Component.translatable("gui.eca.shader_generator.delete.confirm"),
            Component.translatable("gui.eca.shader_generator.button.cancel")
        );
        minecraft.setScreen(confirm);
    }

    private void deleteProject(ProjectRef reference) {
        if (!ShaderProjectCodec.delete(reference)) {
            statusError = true;
            status = Component.translatable(
                "gui.eca.shader_generator.status.delete_failed", reference.id()
            );
            return;
        }
        if (generatedPreview != null) {
            generatedPreview.close();
            generatedPreview = null;
        }
        project.copyStateFrom(new ShaderCompositionProject());
        projectModId = null;
        projectShaderName = null;
        undoStack.clear();
        redoStack.clear();
        clearAiHistory();
        selectedLayerIndex = -1;
        selectedElementIndex = -1;
        elementParamView = false;
        layerScroll = 0;
        sourceIndex = 0;
        dirty = true;
        statusError = false;
        status = Component.translatable(
            "gui.eca.shader_generator.status.deleted", reference.id()
        );
    }

    private void openProject(ProjectRef reference) {
        pushUndo();
        boolean loaded = ShaderProjectCodec.load(reference, project);
        openDropdown = -1;
        if (loaded) {
            clearAiHistory();
            projectModId = reference.modId();
            projectShaderName = reference.shaderName();
            selectedLayerIndex = project.layers().isEmpty() ? -1 : 0;
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

    void openSourceEditor() {
        ProjectRef reference = currentProjectRef();
        if (reference == null) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.project_required");
            rebuildWidgets();
            return;
        }
        try {
            boolean initializedIndependentSource = ensureSourceWorkspaceInitialized();
            project.setSourceActive(true);
            if (initializedIndependentSource && hasVisualContent()) {
                project.sourceWorkspace().setVisualOverlayEnabled(true);
            }
            minecraft.setScreen(new ShaderSourceEditorScreen(this, project, reference));
        } catch (RuntimeException exception) {
            statusError = true;
            status = Component.literal(conciseMessage(exception));
            rebuildWidgets();
        }
    }

    private void openAiAssistant() {
        if (currentProjectRef() == null) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.project_required");
            rebuildWidgets();
            return;
        }
        openDropdown = -1;
        minecraft.setScreen(new ShaderAiAssistantScreen(this, aiSession()));
    }

    private void openShaderFolderImport() {
        try {
            String selected = TinyFileDialogs.tinyfd_selectFolderDialog(
                Component.translatable("gui.eca.shader_generator.import.folder_dialog").getString(),
                shaderImportInitialDirectory()
            );
            if (selected == null) {
                rebuildWidgets();
                return;
            }
            List<Candidate> candidates = ShaderFolderImporter.scan(Path.of(selected));
            if (candidates.isEmpty()) {
                statusError = true;
                status = Component.translatable("gui.eca.shader_generator.import.none_found");
                rebuildWidgets();
            } else if (candidates.size() == 1) {
                minecraft.setScreen(new ShaderImportScreen(this, candidates.get(0)));
            } else {
                minecraft.setScreen(new ShaderImportSelectionScreen(
                    this, candidates, this::openShaderImportDetails
                ));
            }
        } catch (Exception exception) {
            EcaLogger.error("Failed to scan shader folder: {}", exception.getMessage());
            statusError = true;
            status = Component.literal(conciseMessage(exception));
            rebuildWidgets();
        }
    }

    private String shaderImportInitialDirectory() throws IOException {
        Path gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        if (Files.isDirectory(gameDirectory)) {
            gameDirectory = gameDirectory.toRealPath();
        } else {
            gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        }
        String separator = gameDirectory.getFileSystem().getSeparator();
        String value = gameDirectory.toString();
        return value.endsWith(separator) ? value : value + separator;
    }

    private void openShaderImportDetails(Candidate candidate) {
        minecraft.setScreen(new ShaderImportScreen(this, candidate));
    }

    boolean importShader(Candidate candidate, String modId, String shaderName) {
        if (candidate == null || !ShaderProjectCodec.isValidModId(modId)
                || !ShaderProjectCodec.isValidShaderName(shaderName)) {
            return false;
        }
        ProjectRef reference = new ProjectRef(modId, shaderName);
        if (ShaderProjectCodec.exists(reference)) return false;
        try {
            ShaderCompositionProject imported = new ShaderCompositionProject();
            imported.layers().get(0).setBaseColor(0.0F, 0.0F, 0.0F, 0.0F);
            imported.sourceWorkspace().copyFrom(candidate.workspace());
            imported.setSourceActive(true);
            if (!ShaderProjectCodec.importDependencies(
                    reference, candidate.dependencyPlan(), imported.sourceWorkspace())) return false;
            if (!ShaderProjectCodec.save(modId, shaderName, imported)) return false;
            pushUndo();
            project.copyStateFrom(imported);
            clearAiHistory();
            projectModId = modId;
            projectShaderName = shaderName;
            selectedLayerIndex = 0;
            layerScroll = 0;
            statusError = false;
            status = Component.translatable(
                "gui.eca.shader_generator.import.success", candidate.displayName()
            );
            EcaLogger.info(
                "[ShaderImport] import complete project={} candidate={} sourceFiles={} resources={} atlases={} warnings={}",
                reference.id(), candidate.displayName(), candidate.fileCount(),
                imported.sourceWorkspace().previewBindings().resources().size(),
                imported.sourceWorkspace().previewBindings().atlases().size(),
                imported.sourceWorkspace().previewBindings().warnings().size()
            );
            return true;
        } catch (Exception exception) {
            EcaLogger.error(
                "Failed to import shader {}: {}", candidate.displayName(), exception.getMessage()
            );
            statusError = true;
            status = Component.literal(conciseMessage(exception));
            return false;
        }
    }

    void returnFromSourceEditor() {
        compileCurrentProject();
        rebuildWidgets();
    }

    /* 将当前工程生成为标准五文件，写入 config/eca/shadergenerator/<ns>/<name>/ */
    private void exportFiveFiles() {
        ProjectRef reference = currentProjectRef();
        if (reference == null) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.project_required");
            return;
        }
        try {
            writeFiveShaderFiles(reference);
            statusError = false;
            status = Component.translatable("gui.eca.shader_generator.status.exported",
                reference.modId() + ":" + reference.shaderName());
        } catch (Throwable t) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.export_failed",
                conciseMessage(t));
        }
    }

    private Path writeFiveShaderFiles(ProjectRef reference) throws IOException {
        ShaderProject shaderProject = project.toShaderProject(
            reference.modId(), reference.shaderName()
        );
        ShaderExportBundle bundle = ShaderGenerator.standard().generate(
            new ShaderGenerator.Request(
                shaderProject,
                project.exportMode(),
                Set.of(ShaderTargetProfile.BLOCK, ShaderTargetProfile.NEW_ENTITY)
            )
        );
        Path exportDirectory = Path.of(
            "config", "eca", "shadergenerator", reference.modId(), reference.shaderName()
        );
        Files.createDirectories(exportDirectory);
        for (ShaderExportBundle.File file : bundle.files()) {
            String fileName = Path.of(file.relativePath()).getFileName().toString();
            Path target = exportDirectory.resolve(fileName);
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        }
        return exportDirectory.toAbsolutePath().normalize();
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

    String aiProjectSummary() {
        ProjectRef reference = currentProjectRef();
        if (reference == null) throw new IllegalStateException("No shader project is open");
        JsonObject root = new JsonObject();
        root.addProperty("project", reference.id());
        root.addProperty("source_initialized", project.sourceWorkspace().isInitialized());
        root.addProperty("source_active", project.sourceActive());
        root.addProperty("visual_overlay", project.sourceWorkspace().visualOverlayEnabled());
        root.addProperty("editing_mode", aiEditingMode());
        JsonArray layers = new JsonArray();
        for (int layerIndex = 0; layerIndex < project.layers().size(); layerIndex++) {
            ShaderLayer layer = project.layers().get(layerIndex);
            JsonObject layerObject = new JsonObject();
            layerObject.addProperty("index", layerIndex);
            layerObject.addProperty("name", layer.name());
            layerObject.addProperty("visible", layer.visible());
            layerObject.addProperty("blend_mode", layer.blendMode().name().toLowerCase());
            JsonArray baseColor = new JsonArray();
            baseColor.add(layer.baseRed());
            baseColor.add(layer.baseGreen());
            baseColor.add(layer.baseBlue());
            baseColor.add(layer.baseAlpha());
            layerObject.add("base_color", baseColor);
            if (layer.backgroundImagePath() != null) {
                layerObject.addProperty("background_image", layer.backgroundImagePath());
            }
            JsonArray elements = new JsonArray();
            for (int elementIndex = 0; elementIndex < layer.elements().size(); elementIndex++) {
                ShaderModuleInstance element = layer.elements().get(elementIndex);
                JsonObject elementObject = new JsonObject();
                elementObject.addProperty("index", elementIndex);
                elementObject.addProperty("definition", element.definition().id());
                elementObject.addProperty("enabled", element.enabled());
                if (element.imagePath() != null) {
                    elementObject.addProperty("image", element.imagePath());
                }
                JsonObject values = new JsonObject();
                element.values().forEach(values::addProperty);
                elementObject.add("parameters", values);
                elements.add(elementObject);
            }
            layerObject.add("elements", elements);
            layers.add(layerObject);
        }
        root.add("layers", layers);
        return root.toString();
    }

    String aiReadShaderFile(String serializedFile) {
        ensureSourceWorkspaceInitialized();
        return project.sourceWorkspace().source(aiSourceFile(serializedFile));
    }

    String aiReadShaderFileRange(String serializedFile, int startLine, int endLine) {
        ensureSourceWorkspaceInitialized();
        ShaderSourceFile file = aiSourceFile(serializedFile);
        String source = project.sourceWorkspace().source(file);
        String[] lines = sourceLines(source);
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("Invalid 1-based line range");
        }
        if (endLine - startLine + 1 > 400) {
            throw new IllegalArgumentException("A ranged read cannot exceed 400 lines");
        }
        if (startLine > lines.length) {
            throw new IllegalArgumentException(
                "Start line exceeds file length " + lines.length
            );
        }
        int actualEnd = Math.min(endLine, lines.length);
        int startOffset = sourceLineStart(source, startLine);
        int endOffset = sourceLineContentEnd(source, actualEnd);
        JsonObject result = aiSourceDescriptor(file, source);
        result.addProperty("start_line", startLine);
        result.addProperty("end_line", actualEnd);
        result.addProperty("content", source.substring(startOffset, endOffset));
        return result.toString();
    }

    String aiSearchShaderFile(String serializedFile, String query) {
        ensureSourceWorkspaceInitialized();
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("Search query must not be empty");
        }
        ShaderSourceFile file = aiSourceFile(serializedFile);
        String source = project.sourceWorkspace().source(file);
        String[] lines = sourceLines(source);
        JsonArray matches = new JsonArray();
        int matchingLines = 0;
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].contains(query)) continue;
            matchingLines++;
            if (matches.size() >= 20) continue;
            JsonObject match = new JsonObject();
            match.addProperty("line", index + 1);
            match.addProperty("text", abbreviateSourceLine(lines[index]));
            matches.add(match);
        }
        JsonObject result = aiSourceDescriptor(file, source);
        result.addProperty("occurrences", countOccurrences(source, query));
        result.addProperty("matching_lines", matchingLines);
        result.addProperty("results_truncated", matchingLines > matches.size());
        result.add("matches", matches);
        return result.toString();
    }

    String aiReplaceShaderFile(String serializedFile, String content) {
        ensureSourceWorkspaceInitialized();
        ShaderSourceFile file = aiSourceFile(serializedFile);
        return applyAiSourceMutation(file, content, "Replaced complete file");
    }

    String aiReplaceShaderRange(
        String serializedFile,
        int startLine,
        int endLine,
        String expectedVersion,
        String content
    ) {
        ensureSourceWorkspaceInitialized();
        ShaderSourceFile file = aiSourceFile(serializedFile);
        String source = project.sourceWorkspace().source(file);
        requireAiSourceVersion(source, expectedVersion);
        int lineCount = sourceLines(source).length;
        if (startLine < 1 || endLine < startLine || endLine > lineCount) {
            throw new IllegalArgumentException(
                "Invalid line range; file contains " + lineCount + " lines"
            );
        }
        int startOffset = sourceLineStart(source, startLine);
        int endOffset = sourceLineContentEnd(source, endLine);
        String replacement = normalizeRangeReplacement(
            normalizeSourceLineEndings(content, source), endOffset < source.length()
        );
        String updated = source.substring(0, startOffset)
            + replacement
            + source.substring(endOffset);
        return applyAiSourceMutation(
            file, updated, "Replaced lines " + startLine + "-" + endLine
        );
    }

    String aiReplaceShaderText(
        String serializedFile,
        String oldText,
        String newText,
        String expectedVersion
    ) {
        ensureSourceWorkspaceInitialized();
        if (oldText == null || oldText.isEmpty()) {
            throw new IllegalArgumentException("Old text must not be empty");
        }
        ShaderSourceFile file = aiSourceFile(serializedFile);
        String source = project.sourceWorkspace().source(file);
        requireAiSourceVersion(source, expectedVersion);
        requireUniqueSourceText(source, oldText, "old_text");
        int matchIndex = source.indexOf(oldText);
        String updated = source.substring(0, matchIndex)
            + normalizeSourceLineEndings(newText, source)
            + source.substring(matchIndex + oldText.length());
        return applyAiSourceMutation(file, updated, "Replaced one exact source fragment");
    }

    String aiInsertShaderText(
        String serializedFile,
        String anchor,
        String position,
        String content,
        String expectedVersion
    ) {
        ensureSourceWorkspaceInitialized();
        if (anchor == null || anchor.isEmpty()) {
            throw new IllegalArgumentException("Anchor must not be empty");
        }
        if (!"before".equals(position) && !"after".equals(position)) {
            throw new IllegalArgumentException("Position must be before or after");
        }
        ShaderSourceFile file = aiSourceFile(serializedFile);
        String source = project.sourceWorkspace().source(file);
        requireAiSourceVersion(source, expectedVersion);
        requireUniqueSourceText(source, anchor, "anchor");
        int anchorIndex = source.indexOf(anchor);
        int insertionIndex = "before".equals(position)
            ? anchorIndex : anchorIndex + anchor.length();
        String updated = source.substring(0, insertionIndex)
            + normalizeSourceLineEndings(content, source)
            + source.substring(insertionIndex);
        return applyAiSourceMutation(file, updated, "Inserted source " + position + " anchor");
    }

    String aiAddLayer(String name, JsonObject properties) {
        pushAiUndo();
        ShaderLayer layer = project.addLayer();
        layer.setName(name);
        layer.setBaseColor(0.0F, 0.0F, 0.0F, 0.0F);
        applyAiLayerProperties(layer, properties);
        enableAiVisualOverlayWhenNeeded();
        dirty = true;
        return "Added layer " + (project.layers().size() - 1) + ": " + name;
    }

    String aiUpdateLayer(int layerIndex, JsonObject properties) {
        ShaderLayer layer = aiLayer(layerIndex);
        pushAiUndo();
        applyAiLayerProperties(layer, properties);
        enableAiVisualOverlayWhenNeeded();
        dirty = true;
        return "Updated layer " + layerIndex;
    }

    String aiAddElement(int layerIndex, String definitionId, JsonObject parameters) {
        if (layerIndex < 0 || layerIndex >= project.layers().size()) {
            throw new IllegalArgumentException("Layer index is out of range");
        }
        ShaderModuleDefinition definition = ShaderModuleRegistry.get(definitionId);
        if (definition == null) throw new IllegalArgumentException("Unknown module " + definitionId);
        validateAiParameters(definition, parameters);
        pushAiUndo();
        ShaderModuleInstance element = project.layers().get(layerIndex).addElement(definition);
        JsonArray applied = applyAiParameters(element, parameters);
        enableAiVisualOverlayWhenNeeded();
        dirty = true;
        return aiElementMutationResult("added", layerIndex,
            project.layers().get(layerIndex).elements().size() - 1, element, applied);
    }

    String aiUpdateElement(int layerIndex, int elementIndex, JsonObject parameters) {
        if (layerIndex < 0 || layerIndex >= project.layers().size()) {
            throw new IllegalArgumentException("Layer index is out of range");
        }
        ShaderLayer layer = project.layers().get(layerIndex);
        if (elementIndex < 0 || elementIndex >= layer.elements().size()) {
            throw new IllegalArgumentException("Element index is out of range");
        }
        ShaderModuleInstance element = layer.elements().get(elementIndex);
        validateAiParameters(element.definition(), parameters);
        pushAiUndo();
        JsonArray applied = applyAiParameters(element, parameters);
        enableAiVisualOverlayWhenNeeded();
        dirty = true;
        return aiElementMutationResult(
            "updated", layerIndex, elementIndex, element, applied
        );
    }

    String aiSetLayerBackgroundImage(int layerIndex, String sourcePath) {
        ShaderLayer layer = aiLayer(layerIndex);
        String importedPath = importAiImage(sourcePath);
        pushAiUndo();
        layer.setBackgroundImagePath(importedPath);
        enableAiVisualOverlayWhenNeeded();
        dirty = true;
        return "Set layer " + layerIndex + " background image to " + importedPath;
    }

    String aiSetElementImage(int layerIndex, int elementIndex, String sourcePath) {
        ShaderLayer layer = aiLayer(layerIndex);
        if (elementIndex < 0 || elementIndex >= layer.elements().size()) {
            throw new IllegalArgumentException("Element index is out of range");
        }
        ShaderModuleInstance element = layer.elements().get(elementIndex);
        if (!"image_element".equals(element.definition().id())) {
            throw new IllegalArgumentException("Element must use the image_element module");
        }
        String importedPath = importAiImage(sourcePath);
        pushAiUndo();
        element.setImagePath(importedPath);
        enableAiVisualOverlayWhenNeeded();
        dirty = true;
        return "Set element " + layerIndex + ":" + elementIndex + " image to " + importedPath;
    }

    private String importAiImage(String sourcePath) {
        ProjectRef reference = currentProjectRef();
        if (reference == null) throw new IllegalStateException("No shader project is open");
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("Image source path must not be blank");
        }
        Path source = Path.of(sourcePath);
        if (!source.isAbsolute()) {
            throw new IllegalArgumentException("Image source path must be absolute: " + source);
        }
        source = source.normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Image file does not exist: " + source);
        }
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new IllegalArgumentException("Image file must be a PNG: " + source);
        }
        String importedPath = ShaderProjectCodec.importTexture(reference, source);
        if (importedPath == null) {
            throw new IllegalStateException("Failed to import image: " + source);
        }
        return importedPath;
    }

    String aiRemoveElement(int layerIndex, int elementIndex) {
        ShaderLayer layer = aiLayer(layerIndex);
        if (elementIndex < 0 || elementIndex >= layer.elements().size()) {
            throw new IllegalArgumentException("Element index is out of range");
        }
        pushAiUndo();
        layer.removeElement(elementIndex);
        clampSelection();
        dirty = true;
        return "Removed element " + layerIndex + ":" + elementIndex;
    }

    String aiRemoveLayer(int layerIndex) {
        aiLayer(layerIndex);
        pushAiUndo();
        project.removeLayer(layerIndex);
        clampSelection();
        dirty = true;
        return "Removed layer " + layerIndex;
    }

    String aiClearLayerBackground(int layerIndex) {
        ShaderLayer layer = aiLayer(layerIndex);
        pushAiUndo();
        boolean changed = layer.backgroundImagePath() != null;
        layer.setBackgroundImagePath(null);
        dirty |= changed;
        return changed
            ? "Cleared background image from layer " + layerIndex
            : "Layer " + layerIndex + " had no background image";
    }

    String aiClearElementImage(int layerIndex, int elementIndex) {
        ShaderLayer layer = aiLayer(layerIndex);
        if (elementIndex < 0 || elementIndex >= layer.elements().size()) {
            throw new IllegalArgumentException("Element index is out of range");
        }
        ShaderModuleInstance element = layer.elements().get(elementIndex);
        pushAiUndo();
        boolean changed = element.imagePath() != null;
        element.setImagePath(null);
        dirty |= changed;
        return changed
            ? "Cleared image from element " + layerIndex + ":" + elementIndex
            : "Element " + layerIndex + ":" + elementIndex + " had no image";
    }

    String aiResetToEmptyVisualProject() {
        pushAiUndo();
        project.clearVisualContent();
        project.setSourceActive(false);
        project.sourceWorkspace().setVisualOverlayEnabled(false);
        clampSelection();
        dirty = true;
        return "Cleared all visual layers and output effects; editing_mode=visual";
    }

    String aiSetEditingMode(String mode) {
        String normalized = mode == null ? "" : mode.strip().toLowerCase();
        if (!"visual".equals(normalized)
                && !"source".equals(normalized)
                && !"hybrid".equals(normalized)) {
            throw new IllegalArgumentException("Editing mode must be visual, source, or hybrid");
        }
        pushAiUndo();
        if ("visual".equals(normalized)) {
            project.setSourceActive(false);
            project.sourceWorkspace().setVisualOverlayEnabled(false);
        } else {
            ensureSourceWorkspaceInitialized();
            project.setSourceActive(true);
            project.sourceWorkspace().setVisualOverlayEnabled("hybrid".equals(normalized));
        }
        dirty = true;
        return "Selected editing_mode=" + normalized;
    }

    String aiSaveProject() {
        ProjectRef reference = currentProjectRef();
        if (reference == null) throw new IllegalStateException("No shader project is open");
        if (!ShaderProjectCodec.save(reference.modId(), reference.shaderName(), project)) {
            throw new IllegalStateException("Failed to save project " + reference.id());
        }
        return "Saved project " + reference.id() + " to "
            + ShaderProjectCodec.projectPath(reference).toAbsolutePath().normalize();
    }

    String aiExportShaderFiles() {
        ProjectRef reference = currentProjectRef();
        if (reference == null) throw new IllegalStateException("No shader project is open");
        try {
            return "Exported standard shader files to " + writeFiveShaderFiles(reference);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                "Failed to export shader files for " + reference.id(), exception
            );
        }
    }

    private ShaderLayer aiLayer(int layerIndex) {
        if (layerIndex < 0 || layerIndex >= project.layers().size()) {
            throw new IllegalArgumentException("Layer index is out of range");
        }
        return project.layers().get(layerIndex);
    }

    String aiCompilePreview() {
        compileCurrentProject();
        return "success=" + !statusError + "\n" + status.getString();
    }

    String aiUndoTransaction() {
        ShaderCompositionProject snapshot = aiUndoStack.pollLast();
        if (snapshot == null) return "No AI transaction to undo";
        pushAiSnapshot(aiRedoStack);
        project.copyStateFrom(snapshot);
        clampSelection();
        compileCurrentProject();
        rebuildWidgets();
        return "Undid the most recent AI transaction";
    }

    String aiRedoTransaction() {
        ShaderCompositionProject snapshot = aiRedoStack.pollLast();
        if (snapshot == null) return "No AI transaction to redo";
        pushAiSnapshot(aiUndoStack);
        project.copyStateFrom(snapshot);
        clampSelection();
        compileCurrentProject();
        rebuildWidgets();
        return "Redid the most recently undone AI transaction";
    }

    void renderAiPreview(
        GuiGraphics graphics,
        int left,
        int top,
        int right,
        int bottom,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        ShaderPreviewRenderer.render(
            graphics, activeSource(), previewTarget,
            left, top, right, bottom, mouseX, mouseY, partialTick
        );
    }

    void returnFromAiAssistant() {
        rebuildWidgets();
    }

    private ShaderAiSession aiSession() {
        if (aiSession == null) aiSession = new ShaderAiSession();
        return aiSession;
    }

    ShaderMcpSettings mcpSettings() {
        if (mcpSettings == null) mcpSettings = ShaderMcpSettingsCodec.load();
        return mcpSettings;
    }

    void startMcp(ShaderMcpSettings settings) {
        if (mcpServer.isRunning()) return;
        if (!ShaderMcpSettingsCodec.save(settings)) {
            throw new IllegalStateException("Failed to save MCP settings");
        }
        mcpSettings = settings;
        ShaderAiToolContext context = new ShaderProjectToolContext(
            this,
            () -> !closed && mcpServer.isRunning(),
            this::captureToolPreview
        );
        mcpServer.start(settings.port(), context);
    }

    void stopMcp() {
        mcpServer.close();
    }

    boolean isMcpRunning() {
        return mcpServer.isRunning();
    }

    String mcpEndpoint() {
        return mcpServer.isRunning()
            ? mcpServer.endpoint()
            : "http://127.0.0.1:" + mcpSettings().port() + ShaderMcpServer.ENDPOINT_PATH;
    }

    List<ShaderMcpSessionInfo> mcpSessions() {
        return mcpServer.sessions();
    }

    ShaderAiToolResult captureToolPreview() {
        Screen current = minecraft.screen;
        if (current instanceof ShaderAiAssistantScreen assistant) {
            return assistant.capturePreview();
        }
        if (current instanceof ShaderMcpScreen mcpScreen) {
            return mcpScreen.capturePreview();
        }
        if (current instanceof ShaderSourceEditorScreen sourceEditor) {
            return sourceEditor.capturePreview();
        }
        if (current == this) {
            PreviewRect viewport = previewViewport();
            return ShaderPreviewCapture.capture(
                viewport.left(), viewport.top(), viewport.right(), viewport.bottom()
            );
        }
        throw new IllegalStateException("Open an ECA screen with a visible preview first");
    }

    ShaderAiSession resetAiSession() {
        if (aiSession != null) aiSession.close();
        aiSession = new ShaderAiSession();
        return aiSession;
    }

    private ShaderSourceFile aiSourceFile(String serializedFile) {
        ShaderSourceFile file = ShaderSourceFile.fromSerializedName(serializedFile);
        if (file == null) throw new IllegalArgumentException(
            "Unknown shader file " + serializedFile
        );
        return file;
    }

    private JsonObject aiSourceDescriptor(ShaderSourceFile file, String source) {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("file", file.serializedName());
        descriptor.addProperty("version", aiSourceVersion(source));
        descriptor.addProperty("line_count", sourceLines(source).length);
        descriptor.addProperty("character_count", source.length());
        return descriptor;
    }

    private String applyAiSourceMutation(
        ShaderSourceFile file,
        String updatedSource,
        String action
    ) {
        String safeSource = updatedSource == null ? "" : updatedSource;
        String current = project.sourceWorkspace().source(file);
        if (!current.equals(safeSource)) {
            pushAiUndo();
            project.sourceWorkspace().setSource(file, safeSource);
            project.setSourceActive(true);
            if (hasVisualContent()) {
                project.sourceWorkspace().setVisualOverlayEnabled(true);
            }
            dirty = true;
        }
        JsonObject result = aiSourceDescriptor(file, safeSource);
        result.addProperty("changed", !current.equals(safeSource));
        result.addProperty("action", action);
        return result.toString();
    }

    private void requireAiSourceVersion(String source, String expectedVersion) {
        String actualVersion = aiSourceVersion(source);
        if (!actualVersion.equals(expectedVersion)) {
            throw new IllegalArgumentException(
                "Source version conflict; expected " + expectedVersion
                    + " but current version is " + actualVersion + ". Read the file again."
            );
        }
    }

    private void requireUniqueSourceText(String source, String text, String argumentName) {
        int occurrences = countOccurrences(source, text);
        if (occurrences != 1) {
            throw new IllegalArgumentException(
                argumentName + " must occur exactly once but occurred " + occurrences + " times"
            );
        }
    }

    private static String aiSourceVersion(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String[] sourceLines(String source) {
        return source.split("\\r?\\n", -1);
    }

    private static int sourceLineStart(String source, int lineNumber) {
        if (lineNumber == 1) return 0;
        int line = 1;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) != '\n') continue;
            line++;
            if (line == lineNumber) return index + 1;
        }
        throw new IllegalArgumentException("Line " + lineNumber + " does not exist");
    }

    private static int sourceLineContentEnd(String source, int lineNumber) {
        int start = sourceLineStart(source, lineNumber);
        int newline = source.indexOf('\n', start);
        if (newline < 0) return source.length();
        return newline > start && source.charAt(newline - 1) == '\r' ? newline - 1 : newline;
    }

    private static String normalizeRangeReplacement(String content, boolean followedByLine) {
        String replacement = content == null ? "" : content;
        if (!followedByLine) return replacement;
        if (replacement.endsWith("\r\n")) {
            return replacement.substring(0, replacement.length() - 2);
        }
        if (replacement.endsWith("\n") || replacement.endsWith("\r")) {
            return replacement.substring(0, replacement.length() - 1);
        }
        return replacement;
    }

    private static String normalizeSourceLineEndings(String content, String source) {
        String normalized = content == null ? "" : content;
        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
        return source.contains("\r\n") ? normalized.replace("\n", "\r\n") : normalized;
    }

    private static int countOccurrences(String source, String text) {
        if (text.isEmpty()) return 0;
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(text, offset)) >= 0) {
            count++;
            offset += text.length();
        }
        return count;
    }

    private static String abbreviateSourceLine(String line) {
        int maximumLength = 320;
        return line.length() <= maximumLength
            ? line : line.substring(0, maximumLength - 3) + "...";
    }

    private boolean ensureSourceWorkspaceInitialized() {
        ProjectRef reference = currentProjectRef();
        if (reference == null) throw new IllegalStateException("No shader project is open");
        if (project.sourceWorkspace().isInitialized()
                && !ShaderSourceAssembler.hasLegacyNavigation(project.sourceWorkspace())
                && !ShaderSourceAssembler.hasGeneratedNavigation(project.sourceWorkspace())) {
            return false;
        }
        ShaderProject shaderProject = project.toShaderProject(
            reference.modId(), reference.shaderName()
        );
        ShaderExportBundle bundle = ShaderGenerator.standard().generate(new ShaderGenerator.Request(
            shaderProject,
            project.exportMode(),
            Set.of(ShaderTargetProfile.BLOCK, ShaderTargetProfile.NEW_ENTITY)
        ));
        var generatedWorkspace = ShaderSourceAssembler.fromGeneratedBundle(
            reference.modId(), reference.shaderName(), bundle
        );
        boolean replaceGeneratedSnapshot = !project.sourceWorkspace().isInitialized()
            || ShaderSourceAssembler.matchesGeneratedSnapshot(
                project.sourceWorkspace(), generatedWorkspace
            )
            || ShaderSourceAssembler.matchesLegacyGeneratedSnapshot(
                project.sourceWorkspace(), generatedWorkspace
            );
        if (!replaceGeneratedSnapshot) return false;
        boolean overlayEnabled = project.sourceWorkspace().visualOverlayEnabled();
        project.sourceWorkspace().copyFrom(createIndependentBaseWorkspace(reference));
        project.sourceWorkspace().setVisualOverlayEnabled(overlayEnabled);
        return true;
    }

    private ShaderSourceWorkspace createIndependentBaseWorkspace(ProjectRef reference) {
        ShaderProject baseProject = new ShaderProject(
            reference.modId(),
            reference.shaderName(),
            "vec4 renderEffect(vec2 effectUv, vec3 direction, float gameTime) {\n"
                + "    return vec4(0.0);\n"
                + "}",
            Set.of(),
            List.of(),
            List.of()
        );
        ShaderExportBundle baseBundle = ShaderGenerator.standard().generate(
            new ShaderGenerator.Request(
                baseProject,
                project.exportMode(),
                Set.of(ShaderTargetProfile.BLOCK, ShaderTargetProfile.NEW_ENTITY)
            )
        );
        return ShaderSourceAssembler.fromGeneratedBundle(
            reference.modId(), reference.shaderName(), baseBundle
        );
    }

    private boolean hasVisualContent() {
        if (!project.outputEffects().isEmpty()) return true;
        for (ShaderLayer layer : project.layers()) {
            if (!layer.visible()) continue;
            if (layer.baseAlpha() > 0.0F || layer.backgroundImagePath() != null) return true;
            for (ShaderModuleInstance element : layer.elements()) {
                if (element.enabled()) return true;
            }
        }
        return false;
    }

    private String aiEditingMode() {
        if (project.sourceWorkspace().visualOverlayEnabled()) return "hybrid";
        return project.sourceActive() ? "source" : "visual";
    }

    private void pushAiUndo() {
        pushAiSnapshot(aiUndoStack);
        aiRedoStack.clear();
    }

    private void pushAiSnapshot(Deque<ShaderCompositionProject> stack) {
        if (stack.size() >= 20) stack.removeFirst();
        stack.addLast(project.deepCopy());
    }

    private void clearAiHistory() {
        aiUndoStack.clear();
        aiRedoStack.clear();
    }

    private void enableAiVisualOverlayWhenNeeded() {
        if (project.sourceActive()) {
            project.sourceWorkspace().setVisualOverlayEnabled(true);
        }
    }

    private JsonArray applyAiParameters(
        ShaderModuleInstance element,
        JsonObject parameters
    ) {
        JsonArray applied = new JsonArray();
        for (var entry : parameters.entrySet()) {
            ShaderModuleDefinition.Parameter parameter = aiParameter(
                element.definition(), entry.getKey()
            );
            float requested = entry.getValue().getAsFloat();
            element.setValue(entry.getKey(), requested);
            float actual = element.value(entry.getKey());
            ShaderAiModuleMetadata.Guidance guidance =
                ShaderAiModuleMetadata.parameterGuidance(
                    element.definition().id(), parameter
                );
            JsonObject result = new JsonObject();
            result.addProperty("key", entry.getKey());
            result.addProperty("requested", requested);
            result.addProperty("applied", actual);
            result.addProperty("minimum", parameter.minimum());
            result.addProperty("maximum", parameter.maximum());
            result.addProperty("recommended_minimum", guidance.recommendedMinimum());
            result.addProperty("recommended_maximum", guidance.recommendedMaximum());
            boolean clamped = Float.compare(requested, actual) != 0;
            boolean outsideRecommended = actual < guidance.recommendedMinimum()
                || actual > guidance.recommendedMaximum();
            result.addProperty("clamped", clamped);
            result.addProperty("outside_recommended", outsideRecommended);
            if (clamped) {
                result.addProperty(
                    "warning", "Requested value was clamped to the legal range"
                );
            } else if (outsideRecommended) {
                result.addProperty(
                    "warning",
                    "Legal advanced value outside the recommended visual range; verify the preview"
                );
            }
            applied.add(result);
        }
        return applied;
    }

    private void validateAiParameters(
        ShaderModuleDefinition definition,
        JsonObject parameters
    ) {
        for (var entry : parameters.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()
                || !entry.getValue().getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(
                    "Shader parameter must be numeric: " + entry.getKey()
                );
            }
            aiParameter(definition, entry.getKey());
            float value = entry.getValue().getAsFloat();
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                    "Shader parameter must be finite: " + entry.getKey()
                );
            }
        }
    }

    private ShaderModuleDefinition.Parameter aiParameter(
        ShaderModuleDefinition definition,
        String key
    ) {
        for (ShaderModuleDefinition.Parameter parameter : definition.parameters()) {
            if (parameter.key().equals(key)) return parameter;
        }
        throw new IllegalArgumentException(
            "Unknown shader parameter " + key + " for " + definition.id()
        );
    }

    private String aiElementMutationResult(
        String action,
        int layerIndex,
        int elementIndex,
        ShaderModuleInstance element,
        JsonArray applied
    ) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        result.addProperty("layer_index", layerIndex);
        result.addProperty("element_index", elementIndex);
        result.addProperty("definition_id", element.definition().id());
        result.add("parameters", applied);
        ShaderLayer layer = project.layers().get(layerIndex);
        if ("black_hole".equals(element.definition().id())
            && layer.blendMode() != ShaderBlendMode.NORMAL) {
            result.addProperty(
                "layer_warning",
                "black_hole requires a dedicated NORMAL layer to occlude lower layers; "
                    + layer.blendMode().name().toLowerCase(Locale.ROOT)
                    + " blending cannot create an opaque dark event horizon"
            );
        }
        return result.toString();
    }

    private void applyAiLayerProperties(ShaderLayer layer, JsonObject properties) {
        if (properties == null) return;
        float red = aiLayerChannel(properties, "color_r", layer.baseRed());
        float green = aiLayerChannel(properties, "color_g", layer.baseGreen());
        float blue = aiLayerChannel(properties, "color_b", layer.baseBlue());
        float alpha = aiLayerChannel(properties, "color_a", layer.baseAlpha());
        ShaderBlendMode blendMode = aiLayerBlendMode(properties, layer.blendMode());
        layer.setBaseColor(red, green, blue, alpha);
        if (properties.has("visible") && properties.get("visible").isJsonPrimitive()) {
            layer.setVisible(properties.get("visible").getAsBoolean());
        }
        layer.setBlendMode(blendMode);
    }

    private float aiLayerChannel(JsonObject properties, String key, float fallback) {
        if (!properties.has(key) || !properties.get(key).isJsonPrimitive()) return fallback;
        return Mth.clamp(properties.get(key).getAsFloat(), 0.0F, 1.0F);
    }

    private ShaderBlendMode aiLayerBlendMode(
        JsonObject properties,
        ShaderBlendMode fallback
    ) {
        if (!properties.has("blend_mode")
                || !properties.get("blend_mode").isJsonPrimitive()) {
            return fallback;
        }
        String value = properties.get("blend_mode").getAsString();
        try {
            return ShaderBlendMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown layer blend mode " + value);
        }
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
        if (selectedLayerIndex < 0 || selectedLayerIndex >= project.layers().size()) {
            return;
        }
        pushUndo();
        project.removeLayer(selectedLayerIndex);
        if (project.layers().isEmpty()) {
            selectedLayerIndex = -1;
            selectedElementIndex = -1;
            elementParamView = false;
            elementScroll = 0;
            paramScroll = 0;
        } else {
            selectLayerForInspector(Math.min(selectedLayerIndex, project.layers().size() - 1));
            ensureSelectedLayerVisible();
        }
        rightPanelMode = RightPanelMode.LAYER_LIST;
        resetDragState();
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
        if (currentProjectRef() != null && project.sourceWorkspace().isInitialized()
                && (project.sourceActive()
                    || project.sourceWorkspace().visualOverlayEnabled())) {
            ensureSourceWorkspaceInitialized();
        }
        ShaderProject sp = project.toShaderProject("eca_preview", "generated/project_" + rev);
        try {
            boolean useSourceOverlay = project.sourceWorkspace().isInitialized()
                && (project.sourceActive() || project.sourceWorkspace().visualOverlayEnabled());
            GeneratedShaderPreview compiled = useSourceOverlay
                ? GeneratedShaderPreview.compileSource(
                    sp,
                    project.sourceWorkspace(),
                    previewTexturePaths(sp),
                    ShaderPreviewDependencyResolver.resolve(currentProjectRef(), project.sourceWorkspace())
                )
                : GeneratedShaderPreview.compile(
                    sp, project.exportMode(), previewTexturePaths(sp)
                );
            GeneratedShaderPreview prev = generatedPreview;
            generatedPreview = compiled;
            if (prev != null) {
                prev.close();
            }
            sourceIndex = 0;
            dirty = false;
            statusError = false;
            status = Component.translatable("gui.eca.shader_generator.status.compiled");
            EcaLogger.info(
                "[ShaderCompile] preview compiled project={} sourceOverlay={} resources={} atlases={} warnings={}",
                currentProjectRef() == null ? "unsaved" : currentProjectRef().id(),
                useSourceOverlay,
                project.sourceWorkspace().previewBindings().resources().size(),
                project.sourceWorkspace().previewBindings().atlases().size(),
                project.sourceWorkspace().previewBindings().warnings().size()
            );
        } catch (Throwable t) {
            statusError = true;
            status = Component.translatable("gui.eca.shader_generator.status.compile_failed", conciseMessage(t));
            EcaLogger.error("[ShaderCompile] preview failed project={} reason={}",
                currentProjectRef() == null ? "unsaved" : currentProjectRef().id(), conciseMessage(t));
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
            drawOutputEffectRows(g);
        }
        drawUpperScrollbar(g);
        if (rightPanelMode == RightPanelMode.LAYER_LIST) {
            drawOutputEffectScrollbar(g);
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
        int panelLeft = this.width - RIGHT_WIDTH + 8;
        if (rightPanelMode == RightPanelMode.LAYER_LIST) {
            int effectHeaderTop = outputEffectPanelTop();
            g.fill(panelLeft, effectHeaderTop - RIGHT_PANEL_GAP / 2,
                this.width - 8, effectHeaderTop - RIGHT_PANEL_GAP / 2 + 1, BORDER_COLOR);
            g.drawString(this.font, Component.translatable("gui.eca.shader_generator.panel.effects"),
                panelLeft, effectHeaderTop + 4, 0xFFBFC4CC, false);
        }
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
        /* 元素列表：与主界面图层列表一致的色条 + 背景 + 选中轮廓 */
        if (!elementParamView) {
            for (ElementRowVisual row : visibleElementRows) {
                boolean selected = row.elementIndex() == selectedElementIndex;
                int background = (selected ? 0x66000000 : 0x33000000)
                    | (row.color() & 0x00FFFFFF);
                g.fill(row.x(), row.y(), row.x() + row.width(), row.y() + row.height(), background);
                g.fill(row.x(), row.y(), row.x() + 4, row.y() + row.height(), row.color());
                if (selected) {
                    g.renderOutline(row.x(), row.y(), row.width(), row.height(), 0xFFE6E9ED);
                }
            }
        }
        ShaderModuleInstance element = selectedElementInLayer(layer);
        if ((!elementParamView || element == null)
                && layer.elements().isEmpty()
                && inspectorListBottom - inspectorListTop >= font.lineHeight + 2) {
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
        ElementScreenTransform box = elementScreenTransform(element, preview);
        g.pose().pushPose();
        g.pose().translate((float) box.centerX(), (float) box.centerY(), 0.0F);
        g.pose().mulPose(new Quaternionf().rotateZ((float) box.rotationRadians()));
        int left = (int) Math.round(-box.radiusX());
        int top = (int) Math.round(-box.radiusY());
        int width = Math.max(1, (int) Math.round(box.radiusX() * 2.0D));
        int height = Math.max(1, (int) Math.round(box.radiusY() * 2.0D));
        g.renderOutline(left, top, width, height, 0xFFE6E9ED);

        drawHandle(g, left, top);
        drawHandle(g, left + width, top);
        drawHandle(g, left + width, top + height);
        drawHandle(g, left, top + height);
        int handleY = top - CANVAS_ROTATE_HANDLE_OFFSET;
        g.fill(0, handleY, 1, top, 0xFFE6E9ED);
        drawHandle(g, 0, handleY, 0xFF4D8DFF);
        g.pose().popPose();
    }

    /* 元素选框在屏幕空间的矩形，绘制与手柄命中共用以保证几何一致 */
    private ElementScreenTransform elementScreenTransform(ShaderModuleInstance element, PreviewRect content) {
        double rx = elementRadiusX(element);
        double ry = elementRadiusY(element);
        int spanX = content.right() - content.left();
        int spanY = content.bottom() - content.top();
        double centerX = content.left() + element.value("center_x") * spanX;
        double centerY = content.top() + element.value("center_y") * spanY;
        return new ElementScreenTransform(
            centerX,
            centerY,
            rx * spanX,
            ry * spanY,
            elementRotationRadians(element)
        );
    }

    /* 命中选框手柄：0=左上 1=右上 2=右下 3=左下 4=旋转，未命中返回 -1 */
    private int hitCanvasHandle(ShaderModuleInstance element, PreviewRect content, double mouseX, double mouseY) {
        ElementScreenTransform box = elementScreenTransform(element, content);
        double dx = mouseX - box.centerX();
        double dy = mouseY - box.centerY();
        double localX = rotateX(dx, dy, -box.rotationRadians());
        double localY = rotateY(dx, dy, -box.rotationRadians());
        double[][] corners = {
            {-box.radiusX(), -box.radiusY()},
            {box.radiusX(), -box.radiusY()},
            {box.radiusX(), box.radiusY()},
            {-box.radiusX(), box.radiusY()}
        };
        for (int i = 0; i < corners.length; i++) {
            if (nearHandle(localX, localY, corners[i][0], corners[i][1])) {
                return i;
            }
        }
        if (nearHandle(localX, localY, 0.0D, -box.radiusY() - CANVAS_ROTATE_HANDLE_OFFSET)) {
            return 4;
        }
        return -1;
    }

    private static boolean nearHandle(double mouseX, double mouseY, double handleX, double handleY) {
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
        double dx = uvX - centerX;
        double dy = uvY - centerY;
        double localX = rotateX(dx, dy, -elementRotationRadians(element));
        double localY = rotateY(dx, dy, -elementRotationRadians(element));
        return localX >= -radiusX && localX <= radiusX
            && localY >= -radiusY && localY <= radiusY;
    }

    private static double elementRotationRadians(ShaderModuleInstance element) {
        if (!element.values().containsKey("rotation")) {
            return 0.0D;
        }
        return Math.toRadians(element.value("rotation"));
    }

    private static double rotateX(double x, double y, double radians) {
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return x * cosine - y * sine;
    }

    private static double rotateY(double x, double y, double radians) {
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return x * sine + y * cosine;
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

    private enum ScrollbarTarget {
        NONE,
        UPPER,
        OUTPUT_EFFECTS
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

    private record ElementScreenTransform(
        double centerX,
        double centerY,
        double radiusX,
        double radiusY,
        double rotationRadians
    ) {}

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

    private void drawOutputEffectRows(GuiGraphics graphics) {
        for (OutputEffectRowVisual row : visibleOutputEffectRows) {
            int background = 0x33000000 | (row.color() & 0x00FFFFFF);
            graphics.fill(row.x(), row.y(), row.x() + row.width(), row.y() + row.height(), background);
            graphics.fill(row.x(), row.y(), row.x() + 4, row.y() + row.height(), row.color());
        }
        if (project.outputEffects().isEmpty()
                && outputEffectListBottom - outputEffectListTop >= font.lineHeight + 2) {
            int x = this.width - RIGHT_WIDTH + 8;
            graphics.drawCenteredString(this.font,
                Component.translatable("gui.eca.shader_generator.output_effect.empty"),
                x + (RIGHT_WIDTH - 16) / 2, outputEffectListTop + 6, 0xFF9DA3AC);
        }
    }

    private void drawUpperScrollbar(GuiGraphics graphics) {
        ScrollbarGeometry geometry = upperScrollbarGeometry();
        drawScrollbar(graphics, geometry);
    }

    private void drawOutputEffectScrollbar(GuiGraphics graphics) {
        drawScrollbar(graphics, outputEffectScrollbarGeometry());
    }

    private void drawScrollbar(GuiGraphics graphics, ScrollbarGeometry geometry) {
        if (geometry == null) {
            return;
        }
        graphics.fill(geometry.x(), geometry.top(), geometry.x() + SCROLLBAR_WIDTH,
            geometry.bottom(), 0x55212428);
        graphics.fill(geometry.x(), geometry.thumbTop(), geometry.x() + SCROLLBAR_WIDTH,
            geometry.thumbBottom(), 0xFF8B9098);
    }

    private ScrollbarGeometry upperScrollbarGeometry() {
        if (rightPanelMode == RightPanelMode.LAYER_LIST) {
            return scrollbarGeometry(layerListTop, layerListBottom, project.layers().size(),
                visibleLayerRowCount, layerScroll);
        }
        ShaderLayer layer = selectedLayer();
        if (layer == null) {
            return null;
        }
        int total = elementParamView && selectedElementInLayer(layer) != null
            ? visibleParameters(selectedElementInLayer(layer)).size()
            : layer.elements().size();
        int scroll = elementParamView ? paramScroll : elementScroll;
        return scrollbarGeometry(inspectorListTop, inspectorListBottom, total, inspectorVisibleRows, scroll);
    }

    private ScrollbarGeometry outputEffectScrollbarGeometry() {
        if (rightPanelMode != RightPanelMode.LAYER_LIST) return null;
        return scrollbarGeometry(outputEffectListTop, outputEffectListBottom,
            project.outputEffects().size(), outputEffectVisibleRows, outputEffectScroll);
    }

    private ScrollbarGeometry scrollbarGeometry(int top, int bottom, int total, int visible, int scroll) {
        if (bottom <= top || total <= visible) {
            return null;
        }
        int trackHeight = bottom - top;
        int thumbHeight = Math.max(14, trackHeight * visible / Math.max(total, 1));
        thumbHeight = Math.min(trackHeight, thumbHeight);
        int maxScroll = Math.max(1, total - visible);
        int thumbTop = top + (trackHeight - thumbHeight) * scroll / maxScroll;
        return new ScrollbarGeometry(this.width - 8 - SCROLLBAR_WIDTH, top, bottom,
            thumbTop, thumbTop + thumbHeight, total, visible);
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
    public void onClose() {
        closed = true;
        mcpServer.close();
        if (generatedPreview != null) {
            generatedPreview.close();
            generatedPreview = null;
        }
        if (aiSession != null) {
            aiSession.close();
            aiSession = null;
        }
        super.onClose();
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
        int elementIndex,
        int color
    ) {}

    private record OutputEffectRowVisual(
        int x,
        int y,
        int width,
        int height,
        int effectIndex,
        int color
    ) {}

    private record ScrollbarGeometry(
        int x,
        int top,
        int bottom,
        int thumbTop,
        int thumbBottom,
        int total,
        int visible
    ) {}

}
