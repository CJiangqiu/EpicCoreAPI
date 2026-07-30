package net.eca.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a block extension class for automatic client-side discovery.
 *
 * <p>The annotated class should register one {@link net.eca.util.block_extension.BlockExtension}
 * from a static initializer. ECA loads annotated classes during the load-complete phase.</p>
 *
 * <pre>{@code
 * @RegisterBlockExtension
 * public final class AmethystBlockExtension extends BlockExtension {
 *     static {
 *         BlockExtensionManager.register(new AmethystBlockExtension());
 *     }
 *
 *     private AmethystBlockExtension() {
 *         super(Blocks.AMETHYST_BLOCK);
 *     }
 *
 *     @Override
 *     public boolean enabled() {
 *         return true;
 *     }
 *
 *     @Override
 *     public ResourceLocation getShaderPresetId() {
 *         return new ResourceLocation("example", "amethyst_glow");
 *     }
 *
 *     @Override
 *     public List&lt;ShaderMaskPass&gt; getBlockShaderPasses() {
 *         ResourceLocation mask = new ResourceLocation("example", "textures/block/amethyst_mask.png");
 *         return List.of(
 *             ShaderMaskPass.masked(getBlockRenderType(), mask, 0x000000, 0.05f, 1.0f),
 *             ShaderMaskPass.masked(CustomRenderTypes.BLOCK_FIRE, mask, 0xFF0000, 0.05f, 1.0f)
 *         );
 *     }
 * }
 * }</pre>
 *
 * @see net.eca.util.block_extension.BlockExtension
 * @see net.eca.util.block_extension.BlockExtensionManager
 * @see net.eca.client.render.ShaderMaskPass
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterBlockExtension {
}
