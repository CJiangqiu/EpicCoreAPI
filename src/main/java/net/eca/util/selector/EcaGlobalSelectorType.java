package net.eca.util.selector;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.commands.arguments.selector.options.EntitySelectorOptions;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.command.IEntitySelectorType;

public class EcaGlobalSelectorType implements IEntitySelectorType {

    private final EcaEntitySelector.SelectorMode mode;
    private final Component tooltip;

    public EcaGlobalSelectorType(String token, EcaEntitySelector.SelectorMode mode, Component tooltip) {
        this.mode = mode;
        this.tooltip = tooltip;
    }

    @Override
    public EntitySelector build(EntitySelectorParser parser) throws CommandSyntaxException {
        switch (mode) {
            case ALL_ENTITIES -> {
                parser.setIncludesEntities(true);
                parser.setMaxResults(Integer.MAX_VALUE);
                parser.setOrder(EntitySelector.ORDER_ARBITRARY);
            }
            case NEAREST_PLAYER -> {
                parser.setIncludesEntities(false);
                parser.setMaxResults(1);
                parser.setOrder(EntitySelectorParser.ORDER_NEAREST);
            }
            case ALL_PLAYERS -> {
                parser.setIncludesEntities(false);
                parser.setMaxResults(Integer.MAX_VALUE);
                parser.setOrder(EntitySelector.ORDER_ARBITRARY);
            }
            case RANDOM_PLAYER -> {
                parser.setIncludesEntities(false);
                parser.setMaxResults(1);
                parser.setOrder(EntitySelectorParser.ORDER_RANDOM);
            }
            case SELF -> {
                parser.setIncludesEntities(true);
                parser.setMaxResults(1);
                parser.setOrder(EntitySelector.ORDER_ARBITRARY);
            }
        }

        // 让原生 parser 处理 [options]，这样建议系统能正确提示 type=, distance= 等选项
        parser.setSuggestions((builder, consumer) -> {
            builder.suggest(String.valueOf('['));
            return builder.buildFuture();
        });

        if (parser.getReader().canRead() && parser.getReader().peek() == '[') {
            parser.getReader().skip();
            parser.setSuggestions((builder, consumer) -> {
                builder.suggest(String.valueOf(']'));
                EntitySelectorOptions.suggestNames(parser, builder);
                return builder.buildFuture();
            });
            parser.parseOptions();
        }

        parser.finalizePredicates();
        EntitySelector vanilla = parser.getSelector();
        return new EcaGlobalEntitySelector(vanilla, mode);
    }

    @Override
    public Component getSuggestionTooltip() {
        return tooltip;
    }
}
