package net.eca.client.render.blender;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.eca.util.EcaLogger;
import net.eca.util.entity_extension.BlenderModelExtension;
import net.eca.util.entity_extension.BlenderAnimationState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("removal")
public final class BlenderModelRenderer {
    private static final ResourceLocation WHITE_TEXTURE = new ResourceLocation("textures/misc/white.png");
    private static final BlenderModelAsset.Material DEFAULT_MATERIAL =
        new BlenderModelAsset.Material(1.0f, 1.0f, 1.0f, 1.0f, null, false);
    private static final Set<Class<?>> LOGGED_EXTENSION_FAILURES = ConcurrentHashMap.newKeySet();

    private BlenderModelRenderer() {
    }

    public static boolean render(LivingEntity entity, BlenderModelExtension extension, PoseStack poseStack,
                                 MultiBufferSource buffers, int packedLight, int packedOverlay,
                                 float partialTick) {
        try {
            return renderSafely(entity, extension, poseStack, buffers, packedLight, packedOverlay, partialTick);
        } catch (Throwable throwable) {
            if (extension != null && LOGGED_EXTENSION_FAILURES.add(extension.getClass())) {
                EcaLogger.error("Blender model extension failed during rendering", throwable);
            }
            return false;
        }
    }

    private static boolean renderSafely(LivingEntity entity, BlenderModelExtension extension, PoseStack poseStack,
                                        MultiBufferSource buffers, int packedLight, int packedOverlay,
                                        float partialTick) {
        if (entity == null || extension == null || !extension.enabled() || !extension.shouldRender(entity)) {
            return false;
        }
        BlenderModelAsset asset = BlenderModelManager.INSTANCE.get(extension.modelId());
        if (asset == null) {
            return false;
        }

        BlenderModelDefinition definition = asset.definition;
        float scale = definition.scale() * extension.scale(entity);
        poseStack.pushPose();
        try {
            poseStack.translate(definition.translation().x + extension.offsetX(entity),
                definition.translation().y + extension.offsetY(entity),
                definition.translation().z + extension.offsetZ(entity));
            poseStack.mulPose(Axis.ZP.rotationDegrees(definition.rotation().z));
            poseStack.mulPose(Axis.YP.rotationDegrees(definition.rotation().y));
            poseStack.mulPose(Axis.XP.rotationDegrees(definition.rotation().x));
            poseStack.scale(scale, -scale, scale);

            BlenderAnimationState playback = BlenderAnimationClientState.get(entity);
            String animationName = playback == null ? extension.animation(entity) : playback.animation();
            if (animationName == null || animationName.isBlank()) {
                animationName = definition.defaultAnimation();
            }
            BlenderModelAsset.Animation animation = animationName == null ? null : asset.animations.get(animationName);
            float animationTime = playback == null
                ? animationTime(entity, partialTick, extension.animationSpeed(entity), animation, definition.loop())
                : animationTime(playback, entity.level().getGameTime(), partialTick, animation);
            Map<Integer, AnimatedTransform> animated = animation == null
                ? Map.of() : evaluateAnimation(animation, animationTime);
            Matrix4f[] globalTransforms = evaluateGlobalTransforms(asset, animated);
            for (int root : asset.sceneRoots) {
                renderNode(asset, root, animated, globalTransforms,
                    poseStack, buffers, packedLight, packedOverlay);
            }
        } finally {
            poseStack.popPose();
        }
        return true;
    }

    private static float animationTime(LivingEntity entity, float partialTick, float speed,
                                       BlenderModelAsset.Animation animation, boolean loop) {
        if (animation == null || animation.duration() <= 0.0f) {
            return 0.0f;
        }
        float time = (entity.tickCount + partialTick) / 20.0f * speed;
        if (loop) {
            float duration = animation.duration();
            return Mth.positiveModulo(time, duration);
        }
        return Mth.clamp(time, 0.0f, animation.duration());
    }

    private static float animationTime(BlenderAnimationState playback, long gameTime, float partialTick,
                                       BlenderModelAsset.Animation animation) {
        if (animation == null || animation.duration() <= 0.0f) {
            return 0.0f;
        }
        float time = playback.playbackTime(gameTime, partialTick);
        if (playback.loop()) {
            return Mth.positiveModulo(time, animation.duration());
        }
        return Mth.clamp(time, 0.0f, animation.duration());
    }

    private static Map<Integer, AnimatedTransform> evaluateAnimation(BlenderModelAsset.Animation animation,
                                                                     float time) {
        Map<Integer, AnimatedTransform> result = new HashMap<>();
        for (BlenderModelAsset.Track track : animation.tracks()) {
            AnimatedTransform transform = result.computeIfAbsent(track.node(), ignored -> new AnimatedTransform());
            int components = track.path() == BlenderModelAsset.Path.ROTATION ? 4 : 3;
            float[] value = sample(track, time, components);
            switch (track.path()) {
                case TRANSLATION -> transform.translation = new Vector3f(value[0], value[1], value[2]);
                case SCALE -> transform.scale = new Vector3f(value[0], value[1], value[2]);
                case ROTATION -> transform.rotation = new Quaternionf(value[0], value[1], value[2], value[3]);
            }
        }
        return result;
    }

    private static float[] sample(BlenderModelAsset.Track track, float time, int components) {
        float[] times = track.times();
        float[] values = track.values();
        if (times.length == 0) {
            return new float[components];
        }
        int right = 1;
        while (right < times.length && times[right] <= time) {
            right++;
        }
        int left = Math.max(0, right - 1);
        right = Math.min(right, times.length - 1);
        float factor = right == left || track.interpolation() == BlenderModelAsset.Interpolation.STEP
            ? 0.0f : (time - times[left]) / (times[right] - times[left]);
        factor = Mth.clamp(factor, 0.0f, 1.0f);
        float[] result = new float[components];
        if (track.path() == BlenderModelAsset.Path.ROTATION) {
            Quaternionf start = quaternion(values, left * components);
            Quaternionf end = quaternion(values, right * components);
            Quaternionf sampled = start.slerp(end, factor).normalize();
            result[0] = sampled.x;
            result[1] = sampled.y;
            result[2] = sampled.z;
            result[3] = sampled.w;
            return result;
        }
        for (int i = 0; i < components; i++) {
            result[i] = Mth.lerp(factor, values[left * components + i], values[right * components + i]);
        }
        return result;
    }

    private static Quaternionf quaternion(float[] values, int offset) {
        return new Quaternionf(values[offset], values[offset + 1], values[offset + 2], values[offset + 3]);
    }

    private static void renderNode(BlenderModelAsset asset, int nodeIndex,
                                   Map<Integer, AnimatedTransform> animated, Matrix4f[] globalTransforms,
                                   PoseStack poseStack,
                                   MultiBufferSource buffers, int packedLight, int overlay) {
        if (nodeIndex < 0 || nodeIndex >= asset.nodes.size()) {
            return;
        }
        BlenderModelAsset.Node node = asset.nodes.get(nodeIndex);
        if (asset.definition.hiddenNodes().contains(node.name())) {
            return;
        }
        poseStack.pushPose();
        try {
            poseStack.mulPoseMatrix(nodeMatrix(node, animated.get(nodeIndex)));
            if (node.mesh() >= 0 && node.mesh() < asset.meshes.size()) {
                SkinPose skinPose = createSkinPose(asset, nodeIndex, node.skin(), globalTransforms);
                renderMesh(asset, asset.meshes.get(node.mesh()), skinPose,
                    poseStack, buffers, packedLight, overlay);
            }
            for (int child : node.children()) {
                renderNode(asset, child, animated, globalTransforms,
                    poseStack, buffers, packedLight, overlay);
            }
        } finally {
            poseStack.popPose();
        }
    }

    private static Matrix4f nodeMatrix(BlenderModelAsset.Node node, AnimatedTransform animated) {
        if (animated == null && node.matrix() != null) {
            return new Matrix4f(node.matrix());
        }
        Vector3f translation = animated != null && animated.translation != null
            ? animated.translation : node.translation();
        Quaternionf rotation = animated != null && animated.rotation != null
            ? animated.rotation : node.rotation();
        Vector3f scale = animated != null && animated.scale != null ? animated.scale : node.scale();
        return new Matrix4f().translationRotateScale(translation, rotation, scale);
    }

    private static Matrix4f[] evaluateGlobalTransforms(BlenderModelAsset asset,
                                                        Map<Integer, AnimatedTransform> animated) {
        Matrix4f[] result = new Matrix4f[asset.nodes.size()];
        int[] parents = new int[asset.nodes.size()];
        Arrays.fill(parents, -1);
        for (int parent = 0; parent < asset.nodes.size(); parent++) {
            for (int child : asset.nodes.get(parent).children()) {
                if (child >= 0 && child < parents.length) {
                    parents[child] = parent;
                }
            }
        }
        for (int i = 0; i < result.length; i++) {
            evaluateGlobalTransform(asset, i, parents, animated, result);
        }
        return result;
    }

    private static Matrix4f evaluateGlobalTransform(BlenderModelAsset asset, int nodeIndex, int[] parents,
                                                    Map<Integer, AnimatedTransform> animated,
                                                    Matrix4f[] output) {
        if (output[nodeIndex] != null) {
            return output[nodeIndex];
        }
        BlenderModelAsset.Node node = asset.nodes.get(nodeIndex);
        Matrix4f parent = parents[nodeIndex] < 0 ? new Matrix4f()
            : evaluateGlobalTransform(asset, parents[nodeIndex], parents, animated, output);
        Matrix4f global = new Matrix4f(parent).mul(nodeMatrix(node, animated.get(nodeIndex)));
        output[nodeIndex] = global;
        return global;
    }

    private static SkinPose createSkinPose(BlenderModelAsset asset, int nodeIndex, int skinIndex,
                                           Matrix4f[] globalTransforms) {
        if (skinIndex < 0 || skinIndex >= asset.skins.size()
            || nodeIndex < 0 || nodeIndex >= globalTransforms.length
            || globalTransforms[nodeIndex] == null) {
            return null;
        }
        BlenderModelAsset.Skin skin = asset.skins.get(skinIndex);
        Matrix4f meshInverse = new Matrix4f(globalTransforms[nodeIndex]).invert();
        Matrix4f[] positionMatrices = new Matrix4f[skin.joints().length];
        Matrix4f[] normalMatrices = new Matrix4f[skin.joints().length];
        for (int i = 0; i < skin.joints().length; i++) {
            int jointNode = skin.joints()[i];
            Matrix4f jointGlobal = jointNode >= 0 && jointNode < globalTransforms.length
                ? globalTransforms[jointNode] : null;
            Matrix4f position = jointGlobal == null ? new Matrix4f()
                : new Matrix4f(meshInverse).mul(jointGlobal).mul(skin.inverseBindMatrices()[i]);
            positionMatrices[i] = position;
            normalMatrices[i] = new Matrix4f(position).invert().transpose();
        }
        return new SkinPose(positionMatrices, normalMatrices);
    }

    private static void renderMesh(BlenderModelAsset asset, BlenderModelAsset.Mesh mesh, SkinPose skinPose,
                                   PoseStack poseStack,
                                   MultiBufferSource buffers, int packedLight, int overlay) {
        for (BlenderModelAsset.Primitive primitive : mesh.primitives()) {
            BlenderModelAsset.Material material = primitive.material() >= 0
                && primitive.material() < asset.materials.size()
                ? asset.materials.get(primitive.material()) : DEFAULT_MATERIAL;
            ResourceLocation texture = material.texture() == null ? WHITE_TEXTURE : material.texture();
            RenderType type = material.translucent()
                ? RenderType.entityTranslucent(texture) : RenderType.entityCutoutNoCull(texture);
            VertexConsumer consumer = buffers.getBuffer(type);
            DeformedVertices deformed = deformVertices(primitive, skinPose);
            int[] indices = primitive.indices();
            for (int i = 0; i + 2 < indices.length; i += 3) {
                emitVertex(consumer, poseStack.last(), primitive, deformed,
                    indices[i], material, packedLight, overlay);
                emitVertex(consumer, poseStack.last(), primitive, deformed,
                    indices[i + 1], material, packedLight, overlay);
                emitVertex(consumer, poseStack.last(), primitive, deformed,
                    indices[i + 2], material, packedLight, overlay);
                emitVertex(consumer, poseStack.last(), primitive, deformed,
                    indices[i + 2], material, packedLight, overlay);
            }
        }
    }

    private static DeformedVertices deformVertices(BlenderModelAsset.Primitive primitive, SkinPose skinPose) {
        if (skinPose == null || primitive.joints().length == 0 || primitive.weights().length == 0) {
            return new DeformedVertices(primitive.positions(), primitive.normals());
        }
        float[] positions = new float[primitive.positions().length];
        float[] normals = new float[primitive.normals().length];
        int vertexCount = primitive.positions().length / 3;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            deformVertex(primitive, skinPose, vertex, positions, normals);
        }
        return new DeformedVertices(positions, normals);
    }

    private static void deformVertex(BlenderModelAsset.Primitive primitive, SkinPose skinPose, int vertex,
                                     float[] positions, float[] normals) {
        int positionOffset = vertex * 3;
        int influenceOffset = vertex * 4;
        float x = primitive.positions()[positionOffset];
        float y = primitive.positions()[positionOffset + 1];
        float z = primitive.positions()[positionOffset + 2];
        float nx = primitive.normals()[positionOffset];
        float ny = primitive.normals()[positionOffset + 1];
        float nz = primitive.normals()[positionOffset + 2];
        float px = 0.0f;
        float py = 0.0f;
        float pz = 0.0f;
        float tx = 0.0f;
        float ty = 0.0f;
        float tz = 0.0f;
        float totalWeight = 0.0f;
        for (int influence = 0; influence < 4; influence++) {
            float weight = primitive.weights()[influenceOffset + influence];
            int joint = primitive.joints()[influenceOffset + influence];
            if (weight <= 0.0f || joint < 0 || joint >= skinPose.positionMatrices().length) {
                continue;
            }
            Matrix4f position = skinPose.positionMatrices()[joint];
            Matrix4f normal = skinPose.normalMatrices()[joint];
            px += weight * (position.m00() * x + position.m10() * y + position.m20() * z + position.m30());
            py += weight * (position.m01() * x + position.m11() * y + position.m21() * z + position.m31());
            pz += weight * (position.m02() * x + position.m12() * y + position.m22() * z + position.m32());
            tx += weight * (normal.m00() * nx + normal.m10() * ny + normal.m20() * nz);
            ty += weight * (normal.m01() * nx + normal.m11() * ny + normal.m21() * nz);
            tz += weight * (normal.m02() * nx + normal.m12() * ny + normal.m22() * nz);
            totalWeight += weight;
        }
        if (totalWeight <= 0.0f) {
            positions[positionOffset] = x;
            positions[positionOffset + 1] = y;
            positions[positionOffset + 2] = z;
            normals[positionOffset] = nx;
            normals[positionOffset + 1] = ny;
            normals[positionOffset + 2] = nz;
            return;
        }
        float inverseWeight = 1.0f / totalWeight;
        positions[positionOffset] = px * inverseWeight;
        positions[positionOffset + 1] = py * inverseWeight;
        positions[positionOffset + 2] = pz * inverseWeight;
        float normalLength = Mth.sqrt(tx * tx + ty * ty + tz * tz);
        if (normalLength > 0.0f) {
            normals[positionOffset] = tx / normalLength;
            normals[positionOffset + 1] = ty / normalLength;
            normals[positionOffset + 2] = tz / normalLength;
        }
    }

    private static void emitVertex(VertexConsumer consumer, PoseStack.Pose pose,
                                   BlenderModelAsset.Primitive primitive, DeformedVertices deformed, int index,
                                   BlenderModelAsset.Material material, int packedLight, int overlay) {
        int positionOffset = index * 3;
        int uvOffset = index * 2;
        if (positionOffset + 2 >= deformed.positions().length) {
            return;
        }
        float nx = positionOffset + 2 < deformed.normals().length ? deformed.normals()[positionOffset] : 0.0f;
        float ny = positionOffset + 2 < deformed.normals().length ? deformed.normals()[positionOffset + 1] : 1.0f;
        float nz = positionOffset + 2 < deformed.normals().length ? deformed.normals()[positionOffset + 2] : 0.0f;
        float u = uvOffset + 1 < primitive.texCoords().length ? primitive.texCoords()[uvOffset] : 0.0f;
        float v = uvOffset + 1 < primitive.texCoords().length ? primitive.texCoords()[uvOffset + 1] : 0.0f;
        Matrix4f position = pose.pose();
        Matrix3f normal = pose.normal();
        consumer.vertex(position, deformed.positions()[positionOffset], deformed.positions()[positionOffset + 1],
                deformed.positions()[positionOffset + 2])
            .color(material.red(), material.green(), material.blue(), material.alpha())
            .uv(u, v)
            .overlayCoords(overlay)
            .uv2(packedLight)
            .normal(normal, nx, ny, nz)
            .endVertex();
    }

    private static final class AnimatedTransform {
        private Vector3f translation;
        private Quaternionf rotation;
        private Vector3f scale;
    }

    private record SkinPose(Matrix4f[] positionMatrices, Matrix4f[] normalMatrices) {
    }

    private record DeformedVertices(float[] positions, float[] normals) {
    }
}
