package net.eca.client.gui;

import net.eca.network.BossShowDeleteEditorPacket;
import net.eca.network.BossShowExitEditorPacket;
import net.eca.network.BossShowOpenEditorHomePacket;
import net.eca.network.NetworkHandler;
import net.eca.util.bossshow.BossShowDefinition;
import net.eca.util.bossshow.BossShowEditorState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

//BossShow 编辑器 Home 界面：选择已有定义或进入实体选择模式新建
public class BossShowEditorHomeScreen extends Screen {

    private DefList defList;
    private Button createBtn;
    private Button closeBtn;

    public BossShowEditorHomeScreen() {
        super(Component.translatable("gui.eca.bossshow.home.title"));
    }

    public static void openFromPacket(BossShowOpenEditorHomePacket msg) {
        BossShowEditorState.beginSession(msg.definitions());
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new BossShowEditorHomeScreen()));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        //=== 顶部 New cutscene 按钮 ===
        createBtn = Button.builder(Component.translatable("gui.eca.bossshow.home.new"), b -> startSelectionMode())
            .bounds(centerX - 130, 50, 260, 24)
            .build();
        this.addRenderableWidget(createBtn);

        //=== 中间 Existing list ===
        int listTop = 110;
        int listBottom = this.height - 40;
        defList = new DefList(this.minecraft, this.width - 40, listBottom - listTop, listTop, listBottom, 24);
        defList.setLeftPos(20);
        defList.setRenderBackground(false);
        defList.setRenderTopAndBottom(false);
        for (BossShowDefinition def : BossShowEditorState.getAvailableDefs()) {
            defList.addDef(def);
        }
        this.addWidget(defList);

        //=== 底部 Close ===
        closeBtn = Button.builder(Component.translatable("gui.eca.bossshow.home.close"), b -> doClose())
            .bounds(centerX - 60, this.height - 28, 120, 20)
            .build();
        this.addRenderableWidget(closeBtn);
    }

    //进入实体选择模式：关闭 Screen 让游戏恢复运行，事件处理器接管
    private void startSelectionMode() {
        BossShowEditorState.enterSelectionMode();
        this.minecraft.setScreen(null);
    }

    //进入"播放选择"模式：和 selection 类似，但右键命中后客户端发包让服务端播放
    private void startPlaySelection(BossShowDefinition def) {
        BossShowEditorState.enterPlaySelection(def.id());
        this.minecraft.setScreen(null);
    }

    private void editExisting(BossShowDefinition def) {
        BossShowEditorState.enter(def);
        //没有锚点时，把 anchor 落到玩家前方 4 格的空间坐标上（不绑定实体）
        //非空 def 必须保留 enter() 已经从 def 恢复的 yaw，否则新录 sample 和旧 sample 坐标系错乱
        if (!BossShowEditorState.hasAnchor()) {
            LocalPlayer p = this.minecraft.player;
            if (p != null) {
                Vec3 look = p.getViewVector(1.0f);
                double ax = p.getX() + look.x * 4.0;
                double ay = p.getY();
                double az = p.getZ() + look.z * 4.0;
                if (def.samples().isEmpty()) {
                    BossShowEditorState.setAnchor(null, ax, ay, az, 0f);
                } else {
                    BossShowEditorState.setAnchorPositionKeepYaw(null, ax, ay, az);
                }
            }
        }
        this.minecraft.setScreen(new BossShowEditorScreen());
    }

    //请求删除：弹二次确认 → 发包 → 服务端删完会重发 Home 包刷新列表
    private void requestDelete(BossShowDefinition def) {
        ConfirmScreen confirm = new ConfirmScreen(
            confirmed -> {
                if (confirmed) {
                    NetworkHandler.sendToServer(new BossShowDeleteEditorPacket(def.id()));
                    //服务端会重发 Home 包，届时 openFromPacket 会重建本界面
                }
                this.minecraft.setScreen(this);
            },
            Component.translatable("gui.eca.bossshow.home.delete.title"),
            Component.translatable("gui.eca.bossshow.home.delete.body", def.id().toString())
        );
        this.minecraft.setScreen(confirm);
    }

    private void doClose() {
        NetworkHandler.sendToServer(new BossShowExitEditorPacket());
        BossShowEditorState.exit();
        this.minecraft.setScreen(null);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 /* GLFW_KEY_ESCAPE */) {
            if (this.getFocused() instanceof EditBox eb && eb.isFocused()) {
                eb.setFocused(false);
                this.setFocused(null);
                return true;
            }
            doClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        //完全透明，世界透出来

        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        g.drawCenteredString(this.font,
            Component.translatable("gui.eca.bossshow.home.subtitle"),
            this.width / 2, 30, 0xAAAAAA);

        //创建按钮下方说明
        g.drawCenteredString(this.font,
            Component.translatable("gui.eca.bossshow.home.new_hint"),
            this.width / 2, 80, 0xFF888888);

        //列表标题
        int listHeaderY = 96;
        g.drawCenteredString(this.font,
            Component.translatable("gui.eca.bossshow.home.existing",
                BossShowEditorState.getAvailableDefs().size()),
            this.width / 2, listHeaderY, 0xFFAAAAAA);

        //列表
        if (defList != null) defList.render(g, mouseX, mouseY, partialTick);

        super.render(g, mouseX, mouseY, partialTick);
    }

    //=== 内嵌 def 列表 widget ===
    private class DefList extends ObjectSelectionList<DefList.DefEntry> {
        DefList(Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
        }

        public void addDef(BossShowDefinition def) {
            this.addEntry(new DefEntry(def));
        }

        @Override
        public int getRowWidth() {
            return this.width - 12;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.x1 - 6;
        }

        @Override
        public void updateNarration(NarrationElementOutput output) {
            super.updateNarration(output);
        }

        class DefEntry extends ObjectSelectionList.Entry<DefEntry> {
            private final BossShowDefinition def;
            private final Button playBtn;
            private final Button editBtn;
            private final Button deleteBtn;

            DefEntry(BossShowDefinition def) {
                this.def = def;
                this.playBtn = Button.builder(Component.translatable("gui.eca.bossshow.home.play"), b -> startPlaySelection(this.def))
                    .bounds(0, 0, 40, 18)
                    .build();
                this.editBtn = Button.builder(Component.translatable("gui.eca.bossshow.home.edit"), b -> editExisting(this.def))
                    .bounds(0, 0, 40, 18)
                    .build();
                this.deleteBtn = Button.builder(Component.translatable("gui.eca.bossshow.home.delete"), b -> requestDelete(this.def))
                    .bounds(0, 0, 40, 18)
                    .build();
            }

            @Override
            public Component getNarration() {
                return Component.literal(def.id().toString());
            }

            @Override
            public void render(GuiGraphics g, int entryIdx, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean isHovering, float partialTick) {
                ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(def.targetType());
                String idLine = def.id().toString();
                String meta = (typeId != null ? typeId.toString() : "?")
                    + "    " + def.samples().size() + " samples    "
                    + def.markers().size() + " markers    " + def.trigger().type();
                g.drawString(Minecraft.getInstance().font, idLine, left + 4, top + 2, 0xFFFFFF, false);
                g.drawString(Minecraft.getInstance().font, meta, left + 4, top + 12, 0xAAAAAA, false);

                //右侧按钮（从右到左）：删除 | 编辑 | 播放
                deleteBtn.setX(left + width - 44);
                deleteBtn.setY(top + 2);
                editBtn.setX(left + width - 88);
                editBtn.setY(top + 2);
                playBtn.setX(left + width - 132);
                playBtn.setY(top + 2);
                deleteBtn.render(g, mouseX, mouseY, partialTick);
                editBtn.render(g, mouseX, mouseY, partialTick);
                playBtn.render(g, mouseX, mouseY, partialTick);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (deleteBtn.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
                if (editBtn.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
                if (playBtn.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }
        }
    }
}
