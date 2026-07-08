package net.eca.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a shader preset for automatic registration and MCR discovery.
 *
 * <p>The {@code value} is the preset resource id (e.g. {@code "mymod:my_nebula"}).
 * ECA resolves the corresponding five standard core shader files from
 * {@code assets/<namespace>/shaders/core/<path>.*} at startup. The annotated class
 * itself serves as a discoverable marker — MCreator plugins can enumerate
 * {@code @EcaShaderPreset} classes to populate preset dropdowns.
 *
 * <p><b>Usage</b></p>
 * <pre>{@code
 * @EcaShaderPreset("mymod:my_nebula")
 * public final class MyNebulaPreset {}
 * }</pre>
 *
 * <p>At runtime, use {@link net.eca.client.render.preset.EcaPresets} to obtain
 * RenderTypes:
 * <pre>{@code
 * EcaPresets.bossBar("mymod:my_nebula")
 * EcaPresets.skybox("mymod:my_nebula")
 * }</pre>
 *
 * @see net.eca.client.render.preset.ShaderPresetRegistry
 * @see net.eca.client.render.preset.EcaPresets
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EcaShaderPreset {

    /** 预设资源 id，格式 "namespace:path"（如 "mymod:my_nebula"） */
    String value();
}
