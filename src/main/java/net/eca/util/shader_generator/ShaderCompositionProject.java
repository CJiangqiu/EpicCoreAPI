package net.eca.util.shader_generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

public final class ShaderCompositionProject {

    private final List<ShaderLayer> layers = new ArrayList<>();
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

    public ShaderProject toShaderProject(String namespace, String path) {
        return new ShaderProject(
            namespace,
            path,
            ShaderLayerComposer.compose(layers),
            EnumSet.allOf(ShaderProject.Capability.class)
        );
    }

    /* 深拷贝，用于撤销系统保存工程快照 */
    public ShaderCompositionProject deepCopy() {
        ShaderCompositionProject copy = new ShaderCompositionProject();
        copy.layers.clear();
        copy.exportMode = this.exportMode;
        for (ShaderLayer layer : layers) {
            copy.layers.add(layer.copy());
        }
        return copy;
    }

    /* 从快照恢复状态（替换 layers 和 exportMode，不清除 undo 引用） */
    public void copyStateFrom(ShaderCompositionProject source) {
        layers.clear();
        exportMode = source.exportMode;
        for (ShaderLayer layer : source.layers) {
            layers.add(layer.copy());
        }
    }
}
