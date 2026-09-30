package net.eca.blender.client.runtime;

import net.eca.blender.client.model.BlenderModelAsset;

import net.eca.blender.animation.BlenderNodeClock;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.eca.blender.client.runtime.BlendFile.require;

public final class BlendRuntime {
    record Geometry(BlendGeometry base, List<BlendGeometryModifier> modifiers) { }
    private final List<BlendAnimation.Transform> transforms;
    private final List<Geometry> geometry;
    private final Map<String, BlendAnimation.Clip> clips;
    private final Map<Integer, BlenderModelAsset.Mesh> staticMeshes = new HashMap<>();
    private final float fps;
    private final float sceneStart;

    BlendRuntime(List<BlendAnimation.Transform> transforms, List<Geometry> geometry,
                 Map<String, BlendAnimation.Clip> clips, float fps, float sceneStart) {
        this.transforms = List.copyOf(transforms);
        this.geometry = List.copyOf(geometry);
        this.clips = clips;
        this.fps = fps;
        this.sceneStart = sceneStart;
    }

    Map<String, BlenderModelAsset.Animation> animations() {
        Map<String, BlenderModelAsset.Animation> result = new HashMap<>();
        clips.forEach((name, clip) -> result.put(name, new BlenderModelAsset.Animation(name, (clip.end() - clip.start()) / fps, List.of())));
        return Map.copyOf(result);
    }

    public BlenderModelAsset frame(BlenderModelAsset source, String animation, float actionSeconds, float entitySeconds,
                            BlenderNodeClock nodeTimeSource, Map<String, Float> overrides) throws IOException {
        BlendAnimation.Clip clip = clips.get(animation == null || animation.isBlank() ? "__scene__" : animation);
        require(clip != null, "Unknown blend action " + animation);
        require(Float.isFinite(actionSeconds) && Float.isFinite(entitySeconds), "Invalid playback time");
        require(nodeTimeSource != null, "Missing node time source");
        boolean synchronizedTime = nodeTimeSource == BlenderNodeClock.ANIMATION;
        float nodeSeconds = synchronizedTime ? actionSeconds : entitySeconds;
        float nodeStart = synchronizedTime ? clip.start() : sceneStart;
        Map<String, Float> parameters = new HashMap<>(source.definition.parameters());
        require(overrides != null, "Invalid node parameter map");
        for (Map.Entry<String, Float> entry : overrides.entrySet()) {
            require(entry.getKey() != null && entry.getValue() != null && Float.isFinite(entry.getValue()), "Invalid node parameter");
            parameters.put(entry.getKey(), entry.getValue());
        }
        List<BlendAnimation.Transform> pose = transforms.stream().map(BlendAnimation.Transform::copy).toList();
        float frame = clip.start() + actionSeconds * fps;
        float nodeFrame = nodeStart + nodeSeconds * fps;
        require(Float.isFinite(frame) && Float.isFinite(nodeFrame), "Invalid blend frame");
        for (BlendAnimation.Binding binding : clip.bindings()) pose.get(binding.node()).set(binding.property(), binding.component(), binding.curve().sample(frame));
        List<BlenderModelAsset.Node> nodes = new ArrayList<>();
        for (int i = 0; i < source.nodes.size(); i++) {
            BlenderModelAsset.Node node = source.nodes.get(i);
            Matrix4f matrix = pose.get(i).matrix();
            nodes.add(new BlenderModelAsset.Node(node.name(), node.mesh(), node.skin(), node.children(), node.translation(), node.rotation(), node.scale(), matrix));
        }
        List<BlenderModelAsset.Mesh> meshes = new ArrayList<>();
        for (int i = 0; i < geometry.size(); i++) {
            Geometry item = geometry.get(i);
            boolean constant = item.modifiers.stream().noneMatch(BlendGeometryModifier::dynamic);
            BlenderModelAsset.Mesh mesh = constant ? staticMeshes.get(i) : null;
            if (mesh == null) {
                BlendGeometry evaluated = item.base;
                for (BlendGeometryModifier modifier : item.modifiers) evaluated = modifier.evaluate(evaluated, nodeSeconds, fps, nodeStart, parameters);
                mesh = evaluated.renderMesh();
                if (constant) staticMeshes.put(i, mesh);
            }
            meshes.add(mesh);
        }
        return new BlenderModelAsset(source.id, source.definition, nodes, meshes, source.skins, source.materials,
            Map.of(), source.sceneRoots, source.textures, null, nodeFrame);
    }
}
