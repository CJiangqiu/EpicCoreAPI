package net.eca.client.render.blender;

import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

final class BlenderModelAsset {
    final ResourceLocation id;
    final BlenderModelDefinition definition;
    final List<Node> nodes;
    final List<Mesh> meshes;
    final List<Skin> skins;
    final List<Material> materials;
    final Map<String, Animation> animations;
    final int[] sceneRoots;
    final List<TextureData> textures;

    BlenderModelAsset(ResourceLocation id, BlenderModelDefinition definition, List<Node> nodes,
                      List<Mesh> meshes, List<Skin> skins, List<Material> materials,
                      Map<String, Animation> animations, int[] sceneRoots, List<TextureData> textures) {
        this.id = id;
        this.definition = definition;
        this.nodes = List.copyOf(nodes);
        this.meshes = List.copyOf(meshes);
        this.skins = List.copyOf(skins);
        this.materials = List.copyOf(materials);
        this.animations = Map.copyOf(animations);
        this.sceneRoots = sceneRoots;
        this.textures = List.copyOf(textures);
    }

    record Node(String name, int mesh, int skin, int[] children, Vector3f translation,
                Quaternionf rotation, Vector3f scale, Matrix4f matrix) {
    }

    record Mesh(List<Primitive> primitives) {
    }

    record Primitive(float[] positions, float[] normals, float[] texCoords,
                     int[] joints, float[] weights, int[] indices, int material) {
    }

    record Skin(int[] joints, Matrix4f[] inverseBindMatrices) {
    }

    record Material(float red, float green, float blue, float alpha,
                    ResourceLocation texture, boolean translucent) {
    }

    record Animation(String name, float duration, List<Track> tracks) {
    }

    record Track(int node, Path path, Interpolation interpolation, float[] times, float[] values) {
    }

    enum Path {
        TRANSLATION,
        ROTATION,
        SCALE
    }

    enum Interpolation {
        STEP,
        LINEAR
    }

    record TextureData(ResourceLocation location, byte[] bytes) {
    }
}
