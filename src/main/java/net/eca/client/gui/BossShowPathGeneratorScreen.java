package net.eca.client.gui;

import net.eca.util.bossshow.BossShowCameraPath;
import net.eca.util.bossshow.BossShowCameraPath.Facing;
import net.eca.util.bossshow.BossShowCameraPath.Node;
import net.eca.util.bossshow.BossShowCameraPath.Shape;
import net.eca.util.bossshow.BossShowDefinition.Frame;
import net.eca.util.bossshow.BossShowEditorState;
import net.eca.util.bossshow.BossShowInterpolator;
import net.eca.util.bossshow.Curve;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

//参数保存在控件之外，切页、滚动和窗口缩放不会丢失未提交的输入。
final class BossShowPathGeneratorScreen extends Screen implements BossShowEditorSessionScreen {
    private static final String PREFIX = "gui.eca.bossshow.editor.path.";
    private static final Curve[] CURVES = {
        Curve.NONE, Curve.EASE_IN, Curve.EASE_OUT, Curve.EASE_IN_OUT, Curve.EASE_OUT_IN, Curve.BEZIER
    };
    private final Map<String, String> values = new HashMap<>();
    private final List<Map<String, String>> controls = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private boolean twoPoint;
    private Shape shape = Shape.LINE;
    private Facing facing = Facing.INTERPOLATE;
    private Curve easing = Curve.EASE_IN_OUT;
    private boolean speedMode;
    private boolean controlsPage;
    private int selectedControl;
    private int scroll;
    private int contentBottom;
    private int savedEnd = -1;
    private int savedStart = -1;
    private BossShowCameraPath savedRecipe;
    private List<Frame> preview = List.of();
    private long previewStarted;
    private Component error = Component.empty();

    private record Label(Component text, int x, int y) {}

    BossShowPathGeneratorScreen() {
        super(text("title"));
        int start = BossShowEditorState.getPathGenerationStart();
        int end = BossShowEditorState.hasValidRange() ? BossShowEditorState.getOutPoint() : start;
        twoPoint = end > start;
        shape = twoPoint ? Shape.FREE : Shape.LINE;
        put("start_tick", start);
        put("end_tick", end);
        put("duration", end > start ? end - start + 1 : 40);
        put("speed", 4);
        put("forward", 8);
        put("right", 0);
        put("up", 0);
        put("yaw_change", 0);
        put("pitch_change", 0);
        put("center_x", 0);
        put("center_z", 0);
        put("look_height", 1.5);
        put("sweep", 360);
        put("turns", 1);
        put("rise", 6);
        put("radius_change", 0);
        BossShowCameraPath saved = BossShowEditorState.getEditableCameraPath(start);
        if (saved != null) restore(saved, start);
    }

    private static Component text(String key, Object... args) {
        return Component.translatable(PREFIX + key, args);
    }

    private void put(String key, double value) {
        values.put(key, Double.toString(value));
    }

    private void restore(BossShowCameraPath path, int start) {
        savedRecipe = path;
        twoPoint = path.twoPoint();
        shape = path.shape();
        facing = path.facing();
        easing = path.easing();
        savedStart = start;
        savedEnd = start + path.duration() - 1;
        put("end_tick", savedEnd);
        put("duration", path.duration());
        put("center_x", path.centerX());
        put("center_z", path.centerZ());
        put("look_height", path.lookHeight());
        Node first = path.nodes().get(0);
        Node last = path.nodes().get(path.nodes().size() - 1);
        put("yaw_change", last.yaw() - first.yaw());
        put("pitch_change", last.pitch() - first.pitch());
        if (path.orbital()) {
            put("sweep", last.x() - first.x());
            double shortest = Math.toDegrees(Math.atan2(Math.sin(Math.toRadians(last.x() - first.x())),
                Math.cos(Math.toRadians(last.x() - first.x()))));
            put("turns", Math.rint((last.x() - first.x() - shortest) / 360));
            put("rise", last.y() - first.y());
            put("radius_change", last.z() - first.z());
        } else {
            double dx = last.x() - first.x();
            double dy = last.y() - first.y();
            double dz = last.z() - first.z();
            double yaw = Math.toRadians(first.yaw());
            double pitch = Math.toRadians(first.pitch());
            put("forward", -Math.sin(yaw) * Math.cos(pitch) * dx - Math.sin(pitch) * dy
                + Math.cos(yaw) * Math.cos(pitch) * dz);
            put("right", -Math.cos(yaw) * dx - Math.sin(yaw) * dz);
            put("up", -Math.sin(yaw) * Math.sin(pitch) * dx + Math.cos(pitch) * dy
                + Math.cos(yaw) * Math.sin(pitch) * dz);
        }
        for (int i = 1; i < path.nodes().size() - 1; i++) controls.add(fields(path.nodes().get(i)));
    }

    @Override
    protected void init() {
        labels.clear();
        if (BossShowEditorState.frameCount() == 0) {
            closePanel();
            return;
        }
        BossShowEditorState.stopPreviewPlayback();
        BossShowEditorState.setPreviewEnabled(true);
        if (preview.isEmpty()) previewStart();
        int left = this.width / 2 - panelWidth() / 2;
        int half = (panelWidth() - 6) / 2;
        button(left, 22, half, text(twoPoint ? "two_point" : "single_point"), () -> {
            twoPoint = !twoPoint;
            shape = twoPoint ? Shape.FREE : Shape.LINE;
            resetControls();
        }, false);
        button(left + half + 6, 22, half, text(controlsPage ? "settings_page" : "controls_page"), () -> {
            controlsPage = !controlsPage;
            scroll = 0;
            rebuildWidgets();
        }, false);
        int y = 54 - scroll;
        y = controlsPage ? controlWidgets(left, y) : settingWidgets(left, y);
        contentBottom = y + scroll;
        int w = (panelWidth() - 12) / 3;
        button(left, this.height - 24, w, text("preview_path"), this::previewPath, false);
        button(left + w + 6, this.height - 24, w, text("apply"), this::apply, false);
        button(left + (w + 6) * 2, this.height - 24, w, text("cancel"), this::closePanel, false);
    }

    private int panelWidth() {
        return Math.min(440, this.width - 20);
    }

    private int settingWidgets(int left, int y) {
        button(left, y, panelWidth(), text("shape", text("shape_" + shape.name().toLowerCase(Locale.ROOT))), () -> {
            if (twoPoint) shape = shape == Shape.FREE ? Shape.SPIRAL : Shape.FREE;
            else shape = shape == Shape.LINE ? Shape.ORBIT : shape == Shape.ORBIT ? Shape.SPIRAL : Shape.LINE;
            if (orbital()) facing = Facing.TARGET;
            resetControls();
        }, true);
        y += 28;
        y = row(left, y, values, "start_tick", twoPoint ? "end_tick" : null);
        label(text(twoPoint ? "two_point_hint" : "single_point_hint"), left, y);
        y += 22;
        button(left, y, panelWidth(), text("facing", text("facing_" + facing.name().toLowerCase(Locale.ROOT))), () -> {
            facing = Facing.values()[(facing.ordinal() + 1) % Facing.values().length];
            changed();
            rebuildWidgets();
        }, true);
        y += 28;
        button(left, y, panelWidth(), text("curve", Component.translatable(easing.translationKey())), () -> {
            int index = 0;
            while (index < CURVES.length && CURVES[index] != easing) index++;
            easing = CURVES[(index + 1) % CURVES.length];
            changed();
            rebuildWidgets();
        }, true);
        y += 28;
        if (orbital() || facing == Facing.TARGET) {
            y = row(left, y, values, "center_x", "center_z");
            if (facing == Facing.TARGET) y = row(left, y, values, "look_height", null);
        }
        if (twoPoint) {
            if (orbital()) y = row(left, y, values, "turns", null);
        } else if (orbital()) {
            y = row(left, y, values, "sweep", null);
            if (shape == Shape.SPIRAL) y = row(left, y, values, "rise", "radius_change");
        } else {
            y = row(left, y, values, "forward", "right");
            y = row(left, y, values, "up", null);
        }
        if (!twoPoint && facing == Facing.INTERPOLATE) {
            y = row(left, y, values, "yaw_change", "pitch_change");
        }
        button(left, y, panelWidth(), text(speedMode ? "mode_speed" : "mode_duration"), () -> {
            speedMode = !speedMode;
            changed();
            rebuildWidgets();
        }, true);
        y += 28;
        y = row(left, y, values, speedMode ? "speed" : "duration", null);
        label(text("scroll_hint"), left, y);
        return y + 18;
    }

    private int controlWidgets(int left, int y) {
        label(text(orbital() ? "orbit_controls_hint" : "free_controls_hint"), left, y);
        y += 22;
        int w = (panelWidth() - 12) / 3;
        button(left, y, w, text("previous_control"), () -> {
            if (!controls.isEmpty()) selectedControl = Math.floorMod(selectedControl - 1, controls.size());
            rebuildWidgets();
        }, true);
        button(left + w + 6, y, w, text("add_control"), this::addControl, true);
        button(left + (w + 6) * 2, y, w, text("next_control"), () -> {
            if (!controls.isEmpty()) selectedControl = (selectedControl + 1) % controls.size();
            rebuildWidgets();
        }, true);
        y += 28;
        if (controls.isEmpty()) {
            label(text("no_controls"), left, y);
            return y + 24;
        }
        selectedControl = Math.min(selectedControl, controls.size() - 1);
        label(text("control_index", selectedControl + 1, controls.size()), left, y);
        y += 20;
        Map<String, String> control = controls.get(selectedControl);
        y = row(left, y, control, "progress", null);
        y = controlRow(left, y, control, "x", orbital() ? "angle" : "local_x", "y", "local_y");
        y = controlRow(left, y, control, "z", orbital() ? "radius" : "local_z", null, null);
        if (facing == Facing.INTERPOLATE) y = controlRow(left, y, control, "yaw", "yaw", "pitch", "pitch");
        button(left, y, panelWidth(), text("delete_control"), () -> {
            controls.remove(selectedControl);
            selectedControl = Math.max(0, selectedControl - 1);
            changed();
            rebuildWidgets();
        }, true);
        return y + 30;
    }

    private int row(int left, int y, Map<String, String> source, String a, String b) {
        return controlRow(left, y, source, a, a, b, b);
    }

    private int controlRow(int left, int y, Map<String, String> source,
                           String a, String aLabel, String b, String bLabel) {
        int half = (panelWidth() - 6) / 2;
        field(left, y, half, source, a, aLabel);
        if (b != null) field(left + half + 6, y, half, source, b, bLabel);
        return y + 40;
    }

    private void field(int x, int y, int width, Map<String, String> source, String key, String labelKey) {
        label(text(labelKey), x, y);
        EditBox field = new EditBox(this.font, x, y + 12, width, 18, text(labelKey));
        field.setMaxLength(32);
        field.setValue(source.getOrDefault(key, "0"));
        field.setTooltip(Tooltip.create(text(key.equals("forward") || key.equals("right") || key.equals("up")
            ? "basis" : key.equals("progress") ? "progress_hint" : labelKey)));
        field.setResponder(value -> {
            source.put(key, value);
            changed();
        });
        field.visible = y >= 48 && y + 30 <= this.height - 48;
        field.active = field.visible;
        addRenderableWidget(field);
    }

    private void label(Component text, int x, int y) {
        labels.add(new Label(text, x, y));
    }

    private void button(int x, int y, int width, Component title, Runnable action, boolean scrolling) {
        Button button = Button.builder(title, b -> action.run()).bounds(x, y, width, 20).build();
        button.visible = !scrolling || y >= 48 && y + 20 <= this.height - 48;
        button.active = button.visible;
        addRenderableWidget(button);
    }

    private boolean orbital() {
        return shape == Shape.ORBIT || shape == Shape.SPIRAL;
    }

    private void changed() {
        error = Component.empty();
        preview = List.of();
        previewStart();
    }

    private void previewStart() {
        try {
            int tick = integer("start_tick");
            Frame frame = BossShowEditorState.getFrames().get(tick);
            BossShowEditorState.setGeneratedPreview(List.of(frame), 0);
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            BossShowEditorState.setGeneratedPreview(List.of(), 0);
        }
    }

    private void resetControls() {
        controls.clear();
        selectedControl = 0;
        scroll = 0;
        changed();
        rebuildWidgets();
    }

    private static double number(Map<String, String> source, String key) {
        double value = Double.parseDouble(source.get(key).trim());
        if (!Double.isFinite(value)) throw new IllegalArgumentException();
        return value;
    }

    private double number(String key) {
        return number(values, key);
    }

    private int integer(String key) {
        double value = number(key);
        if (value != Math.rint(value) || value < 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException();
        return (int) value;
    }

    private Frame sourceFrame(String key) {
        int index = integer(key);
        if (index >= BossShowEditorState.frameCount()) throw new IllegalArgumentException();
        //复用原始控制朝向，防止注视模式烘焙后的朝向改变再次生成的局部轴。
        if (savedRecipe != null && (index == savedStart || index == savedEnd)) {
            boolean first = index == savedStart;
            Node node = savedRecipe.nodes().get(first ? 0 : savedRecipe.nodes().size() - 1);
            Frame frame = savedRecipe.sample(first ? 0 : 1);
            return new Frame(frame.dx(), frame.dy(), frame.dz(), (float) node.yaw(), (float) node.pitch(), null);
        }
        return BossShowEditorState.getFrames().get(index);
    }

    private BossShowCameraPath recipe(boolean resolveSpeed) {
        Frame first = sourceFrame("start_tick");
        Frame last;
        if (twoPoint) {
            if (integer("end_tick") <= integer("start_tick")) throw new IllegalArgumentException();
            last = sourceFrame("end_tick");
        } else {
            double yaw = Math.toRadians(first.yaw());
            double pitch = Math.toRadians(first.pitch());
            double forward = number("forward");
            double right = number("right");
            double up = number("up");
            last = new Frame(first.dx() - Math.sin(yaw) * Math.cos(pitch) * forward - Math.cos(yaw) * right
                    - Math.sin(yaw) * Math.sin(pitch) * up,
                first.dy() - Math.sin(pitch) * forward + Math.cos(pitch) * up,
                first.dz() + Math.cos(yaw) * Math.cos(pitch) * forward - Math.sin(yaw) * right
                    + Math.cos(yaw) * Math.sin(pitch) * up,
                first.yaw() + (float) number("yaw_change"), first.pitch() + (float) number("pitch_change"), null);
        }
        double cx = number("center_x");
        double cz = number("center_z");
        double endYaw = twoPoint ? BossShowInterpolator.lerpYaw(first.yaw(), last.yaw(), 1) : last.yaw();
        Node a;
        Node b;
        if (orbital()) {
            double angle = Math.toDegrees(Math.atan2(first.dz() - cz, first.dx() - cx));
            double radius = Math.hypot(first.dx() - cx, first.dz() - cz);
            double endAngle;
            double endRadius;
            double endHeight;
            if (twoPoint) {
                double targetAngle = Math.toDegrees(Math.atan2(last.dz() - cz, last.dx() - cx));
                double delta = Math.toDegrees(Math.atan2(Math.sin(Math.toRadians(targetAngle - angle)),
                    Math.cos(Math.toRadians(targetAngle - angle))));
                double turns = number("turns");
                if (turns != Math.rint(turns)) throw new IllegalArgumentException();
                endAngle = angle + delta + turns * 360;
                endRadius = Math.hypot(last.dx() - cx, last.dz() - cz);
                endHeight = last.dy();
            } else {
                endAngle = angle + number("sweep");
                endRadius = radius + (shape == Shape.SPIRAL ? number("radius_change") : 0);
                endHeight = first.dy() + (shape == Shape.SPIRAL ? number("rise") : 0);
            }
            a = new Node(0, angle, first.dy(), radius, first.yaw(), first.pitch());
            b = new Node(1, endAngle, endHeight, endRadius, endYaw, last.pitch());
        } else {
            a = new Node(0, first.dx(), first.dy(), first.dz(), first.yaw(), first.pitch());
            b = new Node(1, last.dx(), last.dy(), last.dz(), endYaw, last.pitch());
        }
        List<Node> nodes = new ArrayList<>();
        nodes.add(a);
        for (Map<String, String> fields : controls) {
            double progress = number(fields, "progress") / 100;
            if (progress <= 0 || progress >= 1) throw new IllegalArgumentException();
            nodes.add(new Node(progress, number(fields, "x"), number(fields, "y"), number(fields, "z"),
                number(fields, "yaw"), number(fields, "pitch")));
        }
        nodes.add(b);
        nodes.sort(Comparator.comparingDouble(Node::progress));
        BossShowCameraPath path = new BossShowCameraPath(twoPoint, shape, facing, easing,
            speedMode ? 40 : integer("duration"), cx, cz, number("look_height"), nodes);
        if (speedMode && resolveSpeed) {
            double speed = number("speed");
            double distance = path.length();
            if (speed <= 0 || distance <= 0) throw new IllegalArgumentException();
            double intervals = Math.ceil(distance * 20 / speed);
            if (!Double.isFinite(intervals) || intervals >= BossShowCameraPath.MAX_DURATION) throw new IllegalArgumentException();
            path = path.withDuration(Math.max(2, (int) intervals + 1));
        }
        return path;
    }

    private static Map<String, String> fields(Node node) {
        Map<String, String> fields = new HashMap<>();
        fields.put("progress", Double.toString(node.progress() * 100));
        fields.put("x", Double.toString(node.x()));
        fields.put("y", Double.toString(node.y()));
        fields.put("z", Double.toString(node.z()));
        fields.put("yaw", Double.toString(node.yaw()));
        fields.put("pitch", Double.toString(node.pitch()));
        return fields;
    }

    private void addControl() {
        try {
            if (controls.size() >= BossShowCameraPath.MAX_NODES - 2) throw new IllegalArgumentException();
            BossShowCameraPath path = recipe(false);
            List<Node> nodes = path.nodes();
            int gap = 0;
            for (int i = 1; i < nodes.size() - 1; i++) {
                if (nodes.get(i + 1).progress() - nodes.get(i).progress()
                    > nodes.get(gap + 1).progress() - nodes.get(gap).progress()) gap = i;
            }
            double progress = (nodes.get(gap).progress() + nodes.get(gap + 1).progress()) / 2;
            controls.add(fields(path.controlAt(progress)));
            selectedControl = controls.size() - 1;
            if (shape == Shape.LINE) shape = Shape.FREE;
            changed();
            rebuildWidgets();
        } catch (IllegalArgumentException exception) {
            error = text("invalid");
        }
    }

    private void previewPath() {
        try {
            BossShowCameraPath path = recipe(true);
            preview = path.generate();
            previewStarted = System.nanoTime();
            BossShowEditorState.setGeneratedPreview(preview, 0);
            error = text("preview_info", path.duration());
        } catch (IllegalArgumentException exception) {
            error = text("invalid");
        }
    }

    private void apply() {
        try {
            BossShowCameraPath path = recipe(true);
            int start = integer("start_tick");
            int end = twoPoint ? integer("end_tick") : start == savedStart ? savedEnd : start;
            if (BossShowEditorState.generateCameraPath(start, end, path)) closePanel();
            else error = text("invalid");
        } catch (IllegalArgumentException exception) {
            error = text("invalid");
        }
    }

    private void closePanel() {
        this.minecraft.setScreen(new BossShowEditorScreen());
    }

    @Override
    public void removed() {
        BossShowEditorState.setPreviewEnabled(false);
        super.removed();
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) {
            closePanel();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int next = Math.max(0, Math.min(Math.max(0, contentBottom - (this.height - 52)), scroll - (int) (delta * 32)));
        if (next != scroll) {
            scroll = next;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void tick() {
        for (var child : children()) {
            if (child instanceof EditBox field) field.tick();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!preview.isEmpty()) {
            double cursor = Math.min(preview.size() - 1, (System.nanoTime() - previewStarted) / 50000000.0);
            BossShowEditorState.setGeneratedPreview(preview, cursor);
        }
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 7, 0xFFFFFF);
        for (Label label : labels) {
            if (label.y >= 48 && label.y + 10 <= this.height - 48) {
                graphics.drawString(this.font, label.text, label.x, label.y, 0xCCCCCC, false);
            }
        }
        Component status = error.getString().isEmpty() ? text("scroll_hint") : error;
        List<FormattedCharSequence> lines = this.font.split(status, panelWidth());
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            graphics.drawCenteredString(this.font, lines.get(i), this.width / 2, this.height - 46 + i * 10, 0xFFCC88);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
