package net.eca.util.selector;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.command.IEntitySelectorType;

public class EcaGlobalSelectorType implements IEntitySelectorType {

    private final String token;
    private final EcaEntitySelector.SelectorMode mode;
    private final Component tooltip;

    public EcaGlobalSelectorType(String token, EcaEntitySelector.SelectorMode mode, Component tooltip) {
        this.token = token;
        this.mode = mode;
        this.tooltip = tooltip;
    }

    @Override
    public EntitySelector build(EntitySelectorParser parser) throws CommandSyntaxException {
        StringReader reader = parser.getReader();
        String options = readOptions(reader);
        String rawSelector = "@" + token + options;
        return new EcaGlobalEntitySelector(rawSelector, mode);
    }

    @Override
    public Component getSuggestionTooltip() {
        return tooltip;
    }

    private static String readOptions(StringReader reader) throws CommandSyntaxException {
        if (!reader.canRead() || reader.peek() != '[') {
            return "";
        }

        int start = reader.getCursor();
        int depth = 0;
        while (reader.canRead()) {
            char c = reader.read();
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return reader.getString().substring(start, reader.getCursor());
                }
            }
        }

        throw EntitySelectorParser.ERROR_EXPECTED_END_OF_OPTIONS.createWithContext(reader);
    }
}
