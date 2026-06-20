package net.eca.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a custom shader preset holder for automatic registration.
 *
 * <p><b>Overview</b></p>
 * A shader preset is a complete, standard Minecraft core shader (a {@code .vsh} + {@code .fsh} + {@code .json}
 * three-file set) placed under {@code assets/<namespace>/shaders/core/<path>.*}. ECA wraps it into an
 * Oculus/Iris-compatible shader instance and exposes five ready-made {@link net.minecraft.client.renderer.RenderType}s
 * (boss bar, boss layer, skybox, item, entity effect) so the preset can be used directly in Entity/Item extensions.
 *
 * <p>The shader files themselves are fully portable and have <b>no</b> dependency on ECA — anyone can register them
 * through a vanilla {@code RegisterShadersEvent}. Going through ECA additionally provides: shaderpack (Iris) depth-color
 * unlock, the standard ECA uniform feeding (camera follow / color key / item-atlas UV), and the five turnkey RenderTypes.</p>
 *
 * <p><b>Registration Process</b></p>
 * <ol>
 *   <li>Annotate a class with {@code @RegisterShaderPreset}</li>
 *   <li>In a static initializer block, call
 *       {@code ShaderPresetRegistry.register(ResourceLocation.fromNamespaceAndPath("your_mod", "my_nebula"))}</li>
 *   <li>ECA scans all mods during {@code FMLLoadCompleteEvent} (client only) and triggers static initialization</li>
 *   <li>Reference it from an extension via {@code EcaAPI.shaderPreset(id).skybox()} (or {@code bossBar()},
 *       {@code bossLayer()}, {@code item()}, {@code entityEffect(texture)})</li>
 * </ol>
 *
 * <p><b>Requirements on the shader files</b></p>
 * <ul>
 *   <li>Must use the standard ECA vertex format (Position, Color, UV0, UV2, Normal), i.e. {@code DefaultVertexFormat.BLOCK}.</li>
 *   <li>The ECA-specific uniforms ({@code CameraYaw/CameraPitch}, {@code ColorKeyColor/ColorKeyTolerance},
 *       {@code LocalUvMin/LocalUvScale}) must declare identity/no-op default values in the {@code .json}, so the
 *       shader still renders correctly when used outside ECA (where nobody feeds them).</li>
 * </ul>
 *
 * <p><b>Example</b></p>
 * <pre>{@code
 * @RegisterShaderPreset
 * public class MyShaders {
 *     static {
 *         ShaderPresetRegistry.register(ResourceLocation.fromNamespaceAndPath("your_mod", "my_nebula"));
 *     }
 * }
 * }</pre>
 *
 * @see net.eca.client.render.preset.ShaderPresetRegistry
 * @see net.eca.client.render.preset.ShaderPreset
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterShaderPreset {

}
