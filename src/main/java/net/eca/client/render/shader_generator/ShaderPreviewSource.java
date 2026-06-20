package net.eca.client.render.shader_generator;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public interface ShaderPreviewSource {

    Component displayName();

    RenderType bossBar();

    RenderType skybox();

    RenderType item();

    RenderType entity(ResourceLocation texture);
}
