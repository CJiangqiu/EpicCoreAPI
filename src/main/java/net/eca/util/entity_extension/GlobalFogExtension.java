package net.eca.util.entity_extension;

import com.mojang.blaze3d.shaders.FogShape;

public interface GlobalFogExtension {

    default boolean enabled() {
        return false;
    }

    default boolean globalMode() {
        return false;
    }

    default float radius() {
        return 48.0f;
    }

    default float fogRed() {
        return 0.5f;
    }

    default float fogGreen() {
        return 0.5f;
    }

    default float fogBlue() {
        return 0.5f;
    }

    default float terrainFogStart(float renderDistance) {
        return renderDistance * 0.1f;
    }

    default float terrainFogEnd(float renderDistance) {
        return renderDistance * 0.55f;
    }

    default float skyFogStart(float renderDistance) {
        return 0.0f;
    }

    default float skyFogEnd(float renderDistance) {
        return renderDistance;
    }

    default FogShape fogShape() {
        return FogShape.CYLINDER;
    }
}
