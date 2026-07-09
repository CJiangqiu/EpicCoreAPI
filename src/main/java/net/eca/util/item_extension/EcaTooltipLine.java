package net.eca.util.item_extension;

import net.minecraft.network.chat.Component;

public record EcaTooltipLine(
    EcaTooltipPosition position,
    Component component,
    int order
) {

    public EcaTooltipLine {
        position = position == null ? EcaTooltipPosition.BODY : position;
        component = component == null ? Component.empty() : component;
    }

    public static EcaTooltipLine head(Component component) {
        return new EcaTooltipLine(EcaTooltipPosition.HEAD, component, 0);
    }

    public static EcaTooltipLine head(EcaText text) {
        return head(text == null ? Component.empty() : text.build());
    }

    public static EcaTooltipLine head(Component component, int order) {
        return new EcaTooltipLine(EcaTooltipPosition.HEAD, component, order);
    }

    public static EcaTooltipLine head(EcaText text, int order) {
        return head(text == null ? Component.empty() : text.build(), order);
    }

    public static EcaTooltipLine body(Component component) {
        return new EcaTooltipLine(EcaTooltipPosition.BODY, component, 0);
    }

    public static EcaTooltipLine body(EcaText text) {
        return body(text == null ? Component.empty() : text.build());
    }

    public static EcaTooltipLine body(Component component, int order) {
        return new EcaTooltipLine(EcaTooltipPosition.BODY, component, order);
    }

    public static EcaTooltipLine body(EcaText text, int order) {
        return body(text == null ? Component.empty() : text.build(), order);
    }

    public static EcaTooltipLine tail(Component component) {
        return new EcaTooltipLine(EcaTooltipPosition.TAIL, component, 0);
    }

    public static EcaTooltipLine tail(EcaText text) {
        return tail(text == null ? Component.empty() : text.build());
    }

    public static EcaTooltipLine tail(Component component, int order) {
        return new EcaTooltipLine(EcaTooltipPosition.TAIL, component, order);
    }

    public static EcaTooltipLine tail(EcaText text, int order) {
        return tail(text == null ? Component.empty() : text.build(), order);
    }
}
