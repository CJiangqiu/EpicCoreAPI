package net.eca.util.shader_generator;

import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ShaderLayer {

    private String name;
    private boolean visible = true;
    private float baseRed;
    private float baseGreen;
    private float baseBlue;
    private float baseAlpha = 1.0F;
    private ShaderBlendMode blendMode = ShaderBlendMode.NORMAL;
    private String backgroundImagePath;
    private final List<ShaderModuleInstance> elements = new ArrayList<>();

    public ShaderLayer(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Layer name must not be blank");
        }
        this.name = name;
    }

    public ShaderLayer(
        String name,
        boolean visible
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Layer name must not be blank");
        }
        this.name = name;
        this.visible = visible;
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

    public float baseRed() {
        return baseRed;
    }

    public float baseGreen() {
        return baseGreen;
    }

    public float baseBlue() {
        return baseBlue;
    }

    public float baseAlpha() {
        return baseAlpha;
    }

    public void setBaseColor(float red, float green, float blue, float alpha) {
        this.baseRed = Mth.clamp(red, 0.0F, 1.0F);
        this.baseGreen = Mth.clamp(green, 0.0F, 1.0F);
        this.baseBlue = Mth.clamp(blue, 0.0F, 1.0F);
        this.baseAlpha = Mth.clamp(alpha, 0.0F, 1.0F);
    }

    public ShaderBlendMode blendMode() {
        return blendMode;
    }

    public void setBlendMode(ShaderBlendMode blendMode) {
        this.blendMode = blendMode == null ? ShaderBlendMode.NORMAL : blendMode;
    }

    public String backgroundImagePath() {
        return backgroundImagePath;
    }

    public void setBackgroundImagePath(String backgroundImagePath) {
        this.backgroundImagePath = backgroundImagePath == null || backgroundImagePath.isBlank()
            ? null
            : backgroundImagePath;
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

    public void addElement(ShaderModuleInstance element) {
        if (element == null) {
            throw new IllegalArgumentException("Shader module instance must not be null");
        }
        elements.add(element);
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
        return new ShaderLayer("Layer 1");
    }

    /* 深拷贝，用于撤销/重做系统 */
    public ShaderLayer copy() {
        ShaderLayer copy = new ShaderLayer(name, visible);
        copy.setBaseColor(baseRed, baseGreen, baseBlue, baseAlpha);
        copy.blendMode = blendMode;
        copy.backgroundImagePath = backgroundImagePath;
        for (ShaderModuleInstance element : elements) {
            copy.elements.add(element.copy());
        }
        return copy;
    }
}
