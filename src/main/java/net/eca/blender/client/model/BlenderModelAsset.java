package net.eca.blender.client.model;

import net.eca.blender.model.BlenderModelDefinition;
import net.eca.blender.client.runtime.BlendMaterialProgram;
import net.eca.blender.client.runtime.BlendRuntime;

import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

public final class BlenderModelAsset {
    public final ResourceLocation id;
    public final BlenderModelDefinition definition;
    public final List<Node> nodes;
    public final List<Mesh> meshes;
    public final List<Skin> skins;
    public final List<Material> materials;
    public final Map<String, Animation> animations;
    public final int[] sceneRoots;
    public final List<TextureData> textures;
    public final BlendRuntime blendRuntime;
    public final float nodeFrame;

    public BlenderModelAsset(ResourceLocation id, BlenderModelDefinition definition, List<Node> nodes,
                      List<Mesh> meshes, List<Skin> skins, List<Material> materials,
                      Map<String, Animation> animations, int[] sceneRoots, List<TextureData> textures) {
        this(id, definition, nodes, meshes, skins, materials, animations, sceneRoots, textures, null);
    }

    public BlenderModelAsset(ResourceLocation id, BlenderModelDefinition definition, List<Node> nodes,
                      List<Mesh> meshes, List<Skin> skins, List<Material> materials,
                      Map<String, Animation> animations, int[] sceneRoots, List<TextureData> textures,
                      BlendRuntime blendRuntime) {
        this(id, definition, nodes, meshes, skins, materials, animations, sceneRoots, textures, blendRuntime, 0);
    }

    public BlenderModelAsset(ResourceLocation id, BlenderModelDefinition definition, List<Node> nodes,
                      List<Mesh> meshes, List<Skin> skins, List<Material> materials,
                      Map<String, Animation> animations, int[] sceneRoots, List<TextureData> textures,
                      BlendRuntime blendRuntime, float nodeFrame) {
        this.id = id;
        this.definition = definition;
        this.nodes = List.copyOf(nodes);
        this.meshes = List.copyOf(meshes);
        this.skins = List.copyOf(skins);
        this.materials = List.copyOf(materials);
        this.animations = Map.copyOf(animations);
        this.sceneRoots = sceneRoots;
        this.textures = List.copyOf(textures);
        this.blendRuntime = blendRuntime;
        this.nodeFrame = nodeFrame;
    }

    public record Node(String name, int mesh, int skin, int[] children, Vector3f translation,
                Quaternionf rotation, Vector3f scale, Matrix4f matrix) {
    }

    public record Mesh(List<Primitive> primitives) {
    }

    public record Primitive(float[] positions, float[] normals, float[] texCoords,
                     int[] joints, float[] weights, int[] indices, int material) {
    }

    public record Skin(int[] joints, Matrix4f[] inverseBindMatrices) {
    }

    public record Material(float red, float green, float blue, float alpha,
                    ResourceLocation texture, boolean translucent, BlendMaterialProgram program) {
        public Material(float red, float green, float blue, float alpha, ResourceLocation texture, boolean translucent) {
            this(red, green, blue, alpha, texture, translucent, null);
        }
    }

    public record Animation(String name, float duration, List<Track> tracks) {
    }

    public record Track(int node, Path path, Interpolation interpolation, float[] times, float[] values) {
    }

    public enum Path {
        TRANSLATION,
        ROTATION,
        SCALE
    }

    public enum Interpolation {
        STEP,
        LINEAR
    }

    public record TextureData(ResourceLocation location, byte[] bytes) {
    }
}
