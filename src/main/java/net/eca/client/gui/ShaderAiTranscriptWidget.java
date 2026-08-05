package net.eca.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class ShaderAiTranscriptWidget extends AbstractScrollWidget {

    private static final Pattern TABLE_SEPARATOR = Pattern.compile(":?-{3,}:?");
    private static final int CARD_GAP = 6;
    private static final int CARD_PADDING = 6;
    private final Font font;
    private List<ShaderAiSession.Message> messages = List.of();
    private List<MessageLayout> layouts = List.of();
    private String selectableText = "";
    private int innerHeight;
    private int selectionAnchor = -1;
    private int selectionCaret = -1;
    private boolean selecting;

    ShaderAiTranscriptWidget(Font font, int x, int y, int width, int height) {
        super(
            x, y, width, height,
            Component.translatable("gui.eca.shader_generator.ai.transcript")
        );
        this.font = font;
    }

    void setMessages(List<ShaderAiSession.Message> updated) {
        List<ShaderAiSession.Message> safe = updated == null ? List.of() : List.copyOf(updated);
        if (safe.equals(messages)) return;
        boolean followBottom = messages.isEmpty()
            || getMaxScrollAmount() - scrollAmount() <= font.lineHeight * 2.0D;
        messages = safe;
        rebuildLayout();
        if (followBottom) setScrollAmount(getMaxScrollAmount());
    }

    private void rebuildLayout() {
        List<MessageLayout> rebuilt = new ArrayList<>();
        StringBuilder visibleText = new StringBuilder();
        int y = innerPadding();
        int cardWidth = Math.max(80, getWidth() - totalInnerPadding() - 4);
        for (ShaderAiSession.Message message : messages) {
            ContentLayout content = layoutMarkdown(message.markdown(), cardWidth - CARD_PADDING * 2);
            indexTextCommands(content.commands, visibleText);
            int cardHeight = 18 + content.height + CARD_PADDING;
            rebuilt.add(new MessageLayout(y, cardHeight, message.role(), content.commands));
            y += cardHeight + CARD_GAP;
        }
        layouts = List.copyOf(rebuilt);
        selectableText = visibleText.toString();
        if (selectionAnchor > selectableText.length() || selectionCaret > selectableText.length()) {
            clearSelection();
        }
        innerHeight = Math.max(1, y);
    }

    private void indexTextCommands(List<RenderCommand> commands, StringBuilder visibleText) {
        for (RenderCommand command : commands) {
            if (!(command instanceof TextCommand textCommand)) continue;
            if (!visibleText.isEmpty()) visibleText.append('\n');
            textCommand.selectionStart = visibleText.length();
            visibleText.append(textCommand.plainText);
            textCommand.selectionEnd = visibleText.length();
        }
    }

    private ContentLayout layoutMarkdown(String markdown, int contentWidth) {
        String[] lines = markdown.replace("\r", "").split("\n", -1);
        List<RenderCommand> commands = new ArrayList<>();
        int y = 0;
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            if (line.stripLeading().startsWith("```")) {
                int start = index++;
                List<String> code = new ArrayList<>();
                while (index < lines.length && !lines[index].stripLeading().startsWith("```")) {
                    code.add(lines[index++]);
                }
                if (index < lines.length) index++;
                int blockStart = y;
                List<FormattedCharSequence> rendered = new ArrayList<>();
                for (String codeLine : code) {
                    List<FormattedCharSequence> wrapped = font.split(
                        Component.literal(codeLine.isEmpty() ? " " : codeLine)
                            .withStyle(ChatFormatting.AQUA),
                        Math.max(20, contentWidth - 8)
                    );
                    rendered.addAll(wrapped);
                }
                if (rendered.isEmpty()) {
                    rendered.add(FormattedCharSequence.forward(" ", Style.EMPTY));
                }
                int height = rendered.size() * font.lineHeight + 8;
                commands.add(new FillCommand(0, blockStart, contentWidth, height, 0xFF111820));
                commands.add(new OutlineCommand(0, blockStart, contentWidth, height, 0xFF365267));
                for (FormattedCharSequence sequence : rendered) {
                    commands.add(new TextCommand(sequence, 4, y + 4, 0xFFD4EDF8));
                    y += font.lineHeight;
                }
                y = blockStart + height + 3;
                if (start == index) index++;
                continue;
            }
            if (isTableStart(lines, index)) {
                TableLayout table = layoutTable(lines, index, contentWidth, y);
                commands.addAll(table.commands);
                y += table.height + 3;
                index = table.nextLine;
                continue;
            }
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                y += 5;
                index++;
                continue;
            }
            if (stripped.matches("(?:-{3,}|\\*{3,}|_{3,})")) {
                commands.add(new FillCommand(0, y + 3, contentWidth, 1, 0xFF555B63));
                y += 8;
                index++;
                continue;
            }
            int headingLevel = headingLevel(stripped);
            String content = headingLevel > 0 ? stripped.substring(headingLevel).strip() : stripped;
            int indent = 0;
            String prefix = "";
            int color = 0xFFE6E9ED;
            if (headingLevel > 0) {
                color = headingLevel == 1 ? 0xFF9DDCFF : 0xFFC5E9FF;
            } else if (content.startsWith(">")) {
                prefix = "▌ ";
                content = content.substring(1).strip();
                color = 0xFFB8C0CA;
                indent = 4;
            } else if (content.matches("[-*+]\\s+.*")) {
                prefix = "• ";
                content = content.substring(1).strip();
                indent = 8;
            } else if (content.matches("\\d+[.)]\\s+.*")) {
                int separator = Math.max(content.indexOf('.'), content.indexOf(')'));
                prefix = content.substring(0, separator + 1) + " ";
                content = content.substring(separator + 1).strip();
                indent = 8;
            }
            MutableComponent component = Component.empty();
            if (!prefix.isEmpty()) component.append(Component.literal(prefix).withStyle(ChatFormatting.GRAY));
            component.append(inlineMarkdown(content));
            if (headingLevel > 0) component.withStyle(ChatFormatting.BOLD);
            List<FormattedCharSequence> wrapped = font.split(
                component,
                Math.max(20, contentWidth - indent)
            );
            for (FormattedCharSequence sequence : wrapped) {
                commands.add(new TextCommand(sequence, indent, y, color));
                y += font.lineHeight;
            }
            y += headingLevel > 0 ? 3 : 1;
            index++;
        }
        return new ContentLayout(List.copyOf(commands), y);
    }

    private TableLayout layoutTable(String[] lines, int start, int width, int initialY) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(splitTableRow(lines[start]));
        int index = start + 2;
        while (index < lines.length && lines[index].contains("|") && !lines[index].isBlank()) {
            rows.add(splitTableRow(lines[index++]));
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        int columnWidth = Math.max(1, width / columns);
        List<RenderCommand> commands = new ArrayList<>();
        int y = initialY;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<List<FormattedCharSequence>> wrappedCells = new ArrayList<>();
            int lineCount = 1;
            for (int column = 0; column < columns; column++) {
                String value = column < rows.get(rowIndex).size()
                    ? rows.get(rowIndex).get(column) : "";
                List<FormattedCharSequence> wrapped = font.split(
                    inlineMarkdown(value),
                    Math.max(12, columnWidth - 6)
                );
                wrappedCells.add(wrapped);
                lineCount = Math.max(lineCount, wrapped.size());
            }
            int rowHeight = lineCount * font.lineHeight + 6;
            for (int column = 0; column < columns; column++) {
                int x = column * columnWidth;
                int cellWidth = column == columns - 1 ? width - x : columnWidth;
                int background = rowIndex == 0 ? 0xFF29313A
                    : (rowIndex & 1) == 0 ? 0xFF1B1E22 : 0xFF15171A;
                commands.add(new FillCommand(x, y, cellWidth, rowHeight, background));
                commands.add(new OutlineCommand(x, y, cellWidth, rowHeight, 0xFF59616B));
                int lineY = y + 3;
                for (FormattedCharSequence sequence : wrappedCells.get(column)) {
                    commands.add(new TextCommand(
                        sequence,
                        x + 3,
                        lineY,
                        rowIndex == 0 ? 0xFFFFFFFF : 0xFFD9DDE2
                    ));
                    lineY += font.lineHeight;
                }
            }
            y += rowHeight;
        }
        return new TableLayout(commands, y - initialY, index);
    }

    private boolean isTableStart(String[] lines, int index) {
        if (index + 1 >= lines.length || !lines[index].contains("|")) return false;
        List<String> separators = splitTableRow(lines[index + 1]);
        return !separators.isEmpty() && separators.stream()
            .map(String::strip)
            .allMatch(value -> TABLE_SEPARATOR.matcher(value).matches());
    }

    private List<String> splitTableRow(String line) {
        String value = line.strip();
        if (value.startsWith("|")) value = value.substring(1);
        if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
        String[] cells = value.split("\\|", -1);
        List<String> output = new ArrayList<>();
        for (String cell : cells) output.add(cell.strip());
        return output;
    }

    private int headingLevel(String line) {
        int count = 0;
        while (count < line.length() && count < 6 && line.charAt(count) == '#') count++;
        return count > 0 && count < line.length() && Character.isWhitespace(line.charAt(count))
            ? count : 0;
    }

    private MutableComponent inlineMarkdown(String source) {
        MutableComponent output = Component.empty();
        int cursor = 0;
        while (cursor < source.length()) {
            int bold = source.indexOf("**", cursor);
            int code = source.indexOf('`', cursor);
            int italic = source.indexOf('*', cursor);
            int next = minimumPositive(bold, code, italic);
            if (next < 0) {
                output.append(source.substring(cursor));
                break;
            }
            if (next > cursor) output.append(source.substring(cursor, next));
            if (next == bold) {
                int end = source.indexOf("**", next + 2);
                if (end > next + 2) {
                    output.append(Component.literal(source.substring(next + 2, end))
                        .withStyle(ChatFormatting.BOLD));
                    cursor = end + 2;
                    continue;
                }
            } else if (next == code) {
                int end = source.indexOf('`', next + 1);
                if (end > next + 1) {
                    output.append(Component.literal(source.substring(next + 1, end))
                        .withStyle(ChatFormatting.AQUA));
                    cursor = end + 1;
                    continue;
                }
            } else {
                int end = source.indexOf('*', next + 1);
                if (end > next + 1) {
                    output.append(Component.literal(source.substring(next + 1, end))
                        .withStyle(ChatFormatting.ITALIC));
                    cursor = end + 1;
                    continue;
                }
            }
            output.append(source.substring(next, next + 1));
            cursor = next + 1;
        }
        return output;
    }

    private int minimumPositive(int... values) {
        int result = Integer.MAX_VALUE;
        for (int value : values) {
            if (value >= 0) result = Math.min(result, value);
        }
        return result == Integer.MAX_VALUE ? -1 : result;
    }

    @Override
    protected int getInnerHeight() {
        return innerHeight;
    }

    @Override
    protected double scrollRate() {
        return font.lineHeight * 3.0D;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !withinContentAreaPoint(mouseX, mouseY)) {
            return handled;
        }
        int caret = caretAt(mouseX, mouseY);
        if (caret < 0) {
            clearSelection();
            return true;
        }
        selectionAnchor = caret;
        selectionCaret = caret;
        selecting = true;
        return true;
    }

    @Override
    public boolean mouseDragged(
        double mouseX,
        double mouseY,
        int button,
        double dragX,
        double dragY
    ) {
        if (selecting && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int caret = caretAt(mouseX, mouseY);
            if (caret >= 0) selectionCaret = caret;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) selecting = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_C && Screen.hasControlDown() && hasSelection()) {
            Minecraft.getInstance().keyboardHandler.setClipboard(selectedText());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    void clearSelection() {
        selectionAnchor = -1;
        selectionCaret = -1;
        selecting = false;
    }

    boolean containsInteractionPoint(double mouseX, double mouseY) {
        return mouseX >= getX() && mouseX <= getX() + getWidth() + 8
            && mouseY >= getY() && mouseY < getY() + getHeight();
    }

    private boolean hasSelection() {
        return selectionAnchor >= 0 && selectionCaret >= 0
            && selectionAnchor != selectionCaret;
    }

    private String selectedText() {
        int start = Math.min(selectionAnchor, selectionCaret);
        int end = Math.max(selectionAnchor, selectionCaret);
        return selectableText.substring(start, end);
    }

    private int caretAt(double mouseX, double mouseY) {
        double contentMouseY = mouseY + scrollAmount();
        TextCommand closest = null;
        int closestDistance = Integer.MAX_VALUE;
        int cardX = getX() + innerPadding() + 1;
        int contentX = cardX + CARD_PADDING;
        for (MessageLayout layout : layouts) {
            int contentY = getY() + layout.y + 18;
            for (RenderCommand command : layout.commands) {
                if (!(command instanceof TextCommand textCommand)) continue;
                int lineY = contentY + textCommand.y;
                int distance = contentMouseY < lineY
                    ? lineY - (int) contentMouseY
                    : contentMouseY > lineY + font.lineHeight
                        ? (int) contentMouseY - lineY - font.lineHeight : 0;
                if (distance < closestDistance) {
                    closest = textCommand;
                    closestDistance = distance;
                }
            }
        }
        if (closest == null) return -1;
        int localX = (int) mouseX - contentX - closest.x;
        return closest.selectionStart + characterOffset(closest.plainText, localX);
    }

    private int characterOffset(String text, int targetX) {
        if (targetX <= 0) return 0;
        int previousWidth = 0;
        for (int offset = 0; offset < text.length();) {
            int next = text.offsetByCodePoints(offset, 1);
            int width = font.width(text.substring(0, next));
            if (targetX < (previousWidth + width) / 2) return offset;
            previousWidth = width;
            offset = next;
        }
        return text.length();
    }

    @Override
    protected void renderContents(
        GuiGraphics graphics,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        int cardX = getX() + innerPadding() + 1;
        int cardWidth = Math.max(80, getWidth() - totalInnerPadding() - 4);
        for (MessageLayout layout : layouts) {
            int top = getY() + layout.y;
            int background = switch (layout.role) {
                case USER -> 0xFF182C3B;
                case ASSISTANT -> 0xFF20242A;
                case STATUS -> 0xFF2A2620;
            };
            int border = switch (layout.role) {
                case USER -> 0xFF3E7799;
                case ASSISTANT -> 0xFF59616B;
                case STATUS -> 0xFF806C42;
            };
            graphics.fill(cardX, top, cardX + cardWidth, top + layout.height, background);
            graphics.renderOutline(cardX, top, cardWidth, layout.height, border);
            graphics.drawString(
                font,
                roleLabel(layout.role),
                cardX + CARD_PADDING,
                top + 5,
                0xFFFFFFFF,
                false
            );
            int contentX = cardX + CARD_PADDING;
            int contentY = top + 18;
            for (RenderCommand command : layout.commands) {
                if (command instanceof TextCommand textCommand) {
                    textCommand.renderSelection(
                        graphics,
                        font,
                        contentX,
                        contentY,
                        selectionAnchor,
                        selectionCaret
                    );
                }
                command.render(graphics, font, contentX, contentY);
            }
        }
    }

    private Component roleLabel(ShaderAiSession.Role role) {
        return Component.translatable(switch (role) {
            case USER -> "gui.eca.shader_generator.ai.role.user";
            case ASSISTANT -> "gui.eca.shader_generator.ai.role.assistant";
            case STATUS -> "gui.eca.shader_generator.ai.role.status";
        });
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }

    private interface RenderCommand {

        void render(GuiGraphics graphics, Font font, int baseX, int baseY);
    }

    private static final class TextCommand implements RenderCommand {

        private final FormattedCharSequence text;
        private final String plainText;
        private final int x;
        private final int y;
        private final int color;
        private int selectionStart;
        private int selectionEnd;

        private TextCommand(FormattedCharSequence text, int x, int y, int color) {
            this.text = text;
            this.plainText = plainText(text);
            this.x = x;
            this.y = y;
            this.color = color;
        }

        private static String plainText(FormattedCharSequence text) {
            StringBuilder output = new StringBuilder();
            text.accept((index, style, codePoint) -> {
                output.appendCodePoint(codePoint);
                return true;
            });
            return output.toString();
        }

        private void renderSelection(
            GuiGraphics graphics,
            Font font,
            int baseX,
            int baseY,
            int anchor,
            int caret
        ) {
            if (anchor < 0 || caret < 0 || anchor == caret) return;
            int selectedStart = Math.min(anchor, caret);
            int selectedEnd = Math.max(anchor, caret);
            int overlapStart = Math.max(selectedStart, selectionStart);
            int overlapEnd = Math.min(selectedEnd, selectionEnd);
            if (overlapStart >= overlapEnd) return;
            int localStart = overlapStart - selectionStart;
            int localEnd = overlapEnd - selectionStart;
            int highlightX = baseX + x + font.width(plainText.substring(0, localStart));
            int highlightWidth = font.width(plainText.substring(localStart, localEnd));
            graphics.fill(
                highlightX,
                baseY + y - 1,
                highlightX + Math.max(1, highlightWidth),
                baseY + y + font.lineHeight,
                0xFF2F72A8
            );
        }

        @Override
        public void render(GuiGraphics graphics, Font font, int baseX, int baseY) {
            graphics.drawString(font, text, baseX + x, baseY + y, color, false);
        }
    }

    private record FillCommand(int x, int y, int width, int height, int color)
        implements RenderCommand {

        @Override
        public void render(GuiGraphics graphics, Font font, int baseX, int baseY) {
            graphics.fill(baseX + x, baseY + y, baseX + x + width, baseY + y + height, color);
        }
    }

    private record OutlineCommand(int x, int y, int width, int height, int color)
        implements RenderCommand {

        @Override
        public void render(GuiGraphics graphics, Font font, int baseX, int baseY) {
            graphics.renderOutline(baseX + x, baseY + y, width, height, color);
        }
    }

    private record MessageLayout(
        int y,
        int height,
        ShaderAiSession.Role role,
        List<RenderCommand> commands
    ) {}

    private record ContentLayout(List<RenderCommand> commands, int height) {}

    private record TableLayout(List<RenderCommand> commands, int height, int nextLine) {}
}
