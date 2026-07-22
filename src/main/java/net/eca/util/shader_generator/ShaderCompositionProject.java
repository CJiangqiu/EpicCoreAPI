package net.eca.util.shader_generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

public final class ShaderCompositionProject {

    private final List<ShaderLayer> layers = new ArrayList<>();
    private final List<ShaderOutputEffectInstance> outputEffects = new ArrayList<>();
    private ShaderExportMode exportMode = ShaderExportMode.PORTABLE_WITH_ECA_HINTS;

    public ShaderCompositionProject() {
        layers.add(ShaderLayer.createDefault());
    }

    public List<ShaderLayer> layers() {
        return Collections.unmodifiableList(layers);
    }

    public ShaderLayer addLayer() {
        ShaderLayer layer = new ShaderLayer("Layer " + (layers.size() + 1));
        layers.add(layer);
        return layer;
    }

    public List<ShaderOutputEffectInstance> outputEffects() {
        return Collections.unmodifiableList(outputEffects);
    }

    public void addOutputEffect(ShaderOutputEffectInstance effect) {
        if (effect != null) {
            outputEffects.add(effect);
        }
    }

    public void removeOutputEffect(int index) {
        if (index >= 0 && index < outputEffects.size()) {
            outputEffects.remove(index);
        }
    }

    public void replaceOutputEffect(int index, ShaderOutputEffectInstance effect) {
        if (effect != null && index >= 0 && index < outputEffects.size()) {
            outputEffects.set(index, effect);
        }
    }

    public void moveOutputEffect(int index, int offset) {
        int target = index + offset;
        if (index < 0 || index >= outputEffects.size() || target < 0 || target >= outputEffects.size()) {
            return;
        }
        ShaderOutputEffectInstance effect = outputEffects.remove(index);
        outputEffects.add(target, effect);
    }

    public ShaderLayer insertLayer(int index, ShaderLayer layer) {
        if (layer == null) {
            throw new IllegalArgumentException("Layer must not be null");
        }
        int clamped = Math.max(0, Math.min(index, layers.size()));
        layers.add(clamped, layer);
        return layer;
    }

    public void removeLayer(int index) {
        if (index >= 0 && index < layers.size() && layers.size() > 1) {
            layers.remove(index);
        }
    }

    public void moveLayer(int index, int offset) {
        int target = index + offset;
        if (index < 0 || index >= layers.size() || target < 0 || target >= layers.size()) {
            return;
        }
        ShaderLayer layer = layers.remove(index);
        layers.add(target, layer);
    }

    public ShaderExportMode exportMode() {
        return exportMode;
    }

    public void setExportMode(ShaderExportMode exportMode) {
        if (exportMode != null) {
            this.exportMode = exportMode;
        }
    }

    /** 仅 ShaderProjectCodec 反序列化时使用，外部不可直接修改 */
    List<ShaderLayer> getLayersInternal() {
        return layers;
    }

    List<ShaderOutputEffectInstance> getOutputEffectsInternal() {
        return outputEffects;
    }

    public ShaderProject toShaderProject(String namespace, String path) {
        return new ShaderProject(
            namespace,
            path,
            ShaderLayerComposer.compose(layers),
            EnumSet.allOf(ShaderProject.Capability.class),
            collectTextures(),
            outputEffects.stream().map(ShaderOutputEffectInstance::copy).toList()
        );
    }

    private List<ShaderProject.TextureBinding> collectTextures() {
        List<ShaderProject.TextureBinding> textures = new ArrayList<>();
        for (int layerIndex = 0; layerIndex < layers.size(); layerIndex++) {
            ShaderLayer layer = layers.get(layerIndex);
            if (layer.backgroundImagePath() != null) {
                textures.add(new ShaderProject.TextureBinding(
                    ShaderLayerComposer.layerSamplerName(layerIndex),
                    layer.backgroundImagePath()
                ));
            }
            for (int elementIndex = 0; elementIndex < layer.elements().size(); elementIndex++) {
                ShaderModuleInstance element = layer.elements().get(elementIndex);
                if (element.imagePath() != null) {
                    textures.add(new ShaderProject.TextureBinding(
                        ShaderLayerComposer.elementSamplerName(layerIndex, elementIndex),
                        element.imagePath()
                    ));
                }
            }
        }
        return textures;
    }

    /* 深拷贝，用于撤销系统保存工程快照 */
    public ShaderCompositionProject deepCopy() {
        ShaderCompositionProject copy = new ShaderCompositionProject();
        copy.layers.clear();
        copy.outputEffects.clear();
        copy.exportMode = this.exportMode;
        for (ShaderLayer layer : layers) {
            copy.layers.add(layer.copy());
        }
        for (ShaderOutputEffectInstance effect : outputEffects) {
            copy.outputEffects.add(effect.copy());
        }
        return copy;
    }

    /* 从快照恢复状态（替换 layers 和 exportMode，不清除 undo 引用） */
    public void copyStateFrom(ShaderCompositionProject source) {
        layers.clear();
        outputEffects.clear();
        exportMode = source.exportMode;
        for (ShaderLayer layer : source.layers) {
            layers.add(layer.copy());
        }
        for (ShaderOutputEffectInstance effect : source.outputEffects) {
            outputEffects.add(effect.copy());
        }
    }
}
