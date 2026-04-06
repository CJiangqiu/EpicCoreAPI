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
 *     public RenderType getRenderType() {
 *         return ArcaneRenderTypes.ITEM;
 *     }
 *
 *     @Override
 *     public float[] getColorKey() {
 *         return new float[]{1.0f, 0.0f, 1.0f};  // target magenta pixels
 *     }
 *
 *     @Override
 *     public float getColorKeyTolerance() {
 *         return 0.15f;
 *     }
 * }
 * }</pre>
 *
 * <p><b>Important Notes</b></p>
 * <ul>
 *   <li>Each {@code Item} can only have ONE extension. Duplicate registrations will be rejected with an error log.</li>
 *   <li>Item rendering is entirely client-side — no network synchronization is needed.</li>
 *   <li>The shader is rendered as an additional overlay pass on top of the normal item rendering.</li>
 *   <li>Use Color-Key masking to apply the shader effect only to specific colored regions of the item texture.</li>
 * </ul>
 *
 * @see net.eca.util.item_extension.ItemExtension
 * @see net.eca.util.item_extension.ItemExtensionManager
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterItemExtension {

}
