package net.eca.util.shader_generator;

import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ShaderLayer {

    private String name;
    private boolean visible = true;
    private ShaderLayerBlendMode blendMode = ShaderLayerBlendMode.NORMAL;
    private float opacity = 1.0F;
    private final List<ShaderModuleInstance> elements = new ArrayList<>();

    public ShaderLayer(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Layer name must not be blank");
        }
        this.name = name;
    }

    public ShaderLayer(
        String name,
        boolean visible,
        ShaderLayerBlendMode blendMode,
        float opacity
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Layer name must not be blank");
        }
        if (blendMode == null) {
            throw new IllegalArgumentException("Layer blend mode must not be null");
        }
        this.name = name;
        this.visible = visible;
        this.blendMode = blendMode;
        this.opacity = Mth.clamp(opacity, 0.0F, 1.0F);
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Layer name must not be blank");
        }
        this.name = name;
    }

    public boolean visible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public ShaderLayerBlendMode blendMode() {
        return blendMode;
    }

    public void setBlendMode(ShaderLayerBlendMode blendMode) {
        if (blendMode != null) {
            this.blendMode = blendMode;
        }
    }

    public float opacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = Mth.clamp(opacity, 0.0F, 1.0F);
    }

    /** 仅 ShaderProjectCodec 反序列化时使用 */
    List<ShaderModuleInstance> getElementsInternal() {
        return elements;
    }

    public List<ShaderModuleInstance> elements() {
        return Collections.unmodifiableList(elements);
    }

    public ShaderModuleInstance addElement(ShaderModuleDefinition definition) {
        ShaderModuleInstance element = definition.createInstance();
        elements.add(element);
        return element;
    }

    public void removeElement(int index) {
        if (index >= 0 && index < elements.size()) {
            elements.remove(index);
        }
    }

    public void moveElement(int index, int offset) {
        int target = index + offset;
        if (index < 0 || index >= elements.size() || target < 0 || target >= elements.size()) {
            return;
        }
        ShaderModuleInstance element = elements.remove(index);
        elements.add(target, element);
    }

    public static ShaderLayer createDefault() {
        ShaderLayer layer = new ShaderLayer("Layer 1");
        layer.addElement(ShaderModuleRegistry.get("gradient"));
        layer.addElement(ShaderModuleRegistry.get("rings"));
        return layer;
    }

    /* 深拷贝，用于撤销/重做系统 */
    public ShaderLayer copy() {
        ShaderLayer copy = new ShaderLayer(name, visible, blendMode, opacity);
        for (ShaderModuleInstance element : elements) {
            copy.elements.add(element.copy());
        }
        return copy;
    }
}
