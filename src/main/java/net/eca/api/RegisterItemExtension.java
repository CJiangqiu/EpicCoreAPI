package net.eca.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an item extension for automatic registration.
 *
 * <p><b>Overview</b></p>
 * The Item Extension System allows mods to add custom shader rendering effects to specific items.
 * Extensions are automatically discovered and registered during mod loading.
 *
 * <p><b>Registration Process</b></p>
 * <ol>
 *   <li>Annotate your extension class with {@code @RegisterItemExtension}</li>
 *   <li>Extend {@link net.eca.util.item_extension.ItemExtension}</li>
 *   <li>Register your instance in a static initializer block</li>
 *   <li>ECA scans all mods during {@code FMLLoadCompleteEvent} and triggers static initialization</li>
 * </ol>
 *
 * <p><b>Complete Example</b></p>
 * <pre>{@code
 * @RegisterItemExtension
 * public class DiamondSwordExtension extends ItemExtension {
 *
 *     static {
 *         ItemExtensionManager.register(new DiamondSwordExtension());
 *     }
 *
 *     public DiamondSwordExtension() {
 *         super(Items.DIAMOND_SWORD);
 *     }
 *
 *     @Override
 *     protected String getModId() {
 *         return "your_mod_id";
 *     }
 *
 *     @Override
 *     public boolean enabled() {
 *         return true;
 *     }
 *
 *     @Override
 *     public List&lt;ShaderMaskPass&gt; getShaderPasses() {
 *         ResourceLocation mask = texture("item/diamond_sword_mask.png");
 *         return List.of(
 *             ShaderMaskPass.masked(ArcaneRenderTypes.ITEM, mask, 0x000000, 0.05f, 1.0f),
 *             ShaderMaskPass.masked(VolcanoRenderTypes.ITEM, mask, 0xFF0000, 0.05f, 1.0f)
 *         );
 *     }
 * }
 * }</pre>
 *
 * <p><b>Important Notes</b></p>
 * <ul>
 *   <li>Each {@code Item} can only have ONE extension. Duplicate registrations will be rejected with an error log.</li>
 *   <li>Item rendering is entirely client-side — no network synchronization is needed.</li>
 *   <li>The shader is rendered as an additional overlay pass on top of the normal item rendering.</li>
 *   <li>Each pass can select a different color from the same mask texture and use a different shader.</li>
 *   <li>Passes render in list order; later passes are drawn over earlier passes when regions overlap.</li>
 *   <li>Legacy Color-Key and single-mask methods remain available but are deprecated compatibility adapters.</li>
 * </ul>
 *
 * @see net.eca.util.item_extension.ItemExtension
 * @see net.eca.util.item_extension.ItemExtensionManager
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterItemExtension {

}
