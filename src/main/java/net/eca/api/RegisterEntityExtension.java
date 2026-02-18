package net.eca.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an entity extension for automatic registration.
 *
 * <p><b>Overview</b></p>
 * The Entity Extension System allows mods to enhance specific entity types with custom rendering effects,
 * boss bars, fog, skyboxes, and combat music. Extensions are automatically discovered and registered
 * during mod loading.
 *
 * <p><b>Registration Process</b></p>
 * <ol>
 *   <li>Annotate your extension class with {@code @RegisterEntityExtension}</li>
 *   <li>Extend {@link net.eca.util.entity_extension.EntityExtension}</li>
 *   <li>Register your instance in a static initializer block</li>
 *   <li>ECA scans all mods during {@code FMLLoadCompleteEvent} and triggers static initialization</li>
 * </ol>
 *
 * <p><b>Component Types</b></p>
 * <ul>
 *   <li><b>Entity-Level</b> (always active for each entity instance):
 *     <ul>
 *       <li>{@code BossBarExtension} - Custom boss health bar with shader support</li>
 *       <li>{@code EntityLayerExtension} - Additional render layer on entity model</li>
 *     </ul>
 *   </li>
 *   <li><b>Global-Level</b> (per-dimension, highest priority wins):
 *     <ul>
 *       <li>{@code GlobalFogExtension} - Custom fog color and distance</li>
 *       <li>{@code GlobalSkyboxExtension} - Custom skybox rendering</li>
 *       <li>{@code CombatMusicExtension} - Combat music playback</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><b>Priority System</b></p>
 * When multiple extension entities exist in the same dimension, the one with the highest priority
 * controls global-level effects. If priorities are equal, the most recently spawned entity wins.
 *
 * <p><b>Complete Example</b></p>
 * <pre>{@code
 * @RegisterEntityExtension
 * public class WardenExtension extends EntityExtension {
 *
 *     static {
 *         EntityExtensionManager.register(new WardenExtension());
 *     }
 *
 *     public WardenExtension() {
 *         super(EntityType.WARDEN, 7);
 *     }
 *
 *     @Override
 *     protected String getModId() {
 *         return "your_mod_id";
 *     }
 *
 *     @Override
 *     public BossBarExtension bossBarExtension() {
 *         return new BossBarExtension() {
 *             @Override
 *             public boolean enabled() {
 *                 return true;
 *             }
 *
 *             @Override
 *             public ResourceLocation getFrameTexture() {
 *                 return texture("screen/custom_bar_frame.png");
 *             }
 *
 *             @Override
 *             public RenderType getFillRenderType() {
 *                 return YourCustomRenderTypes.BOSS_BAR_FILL;
 *             }
 *
 *             @Override
 *             public int getFillWidth() {
 *                 return 420;
 *             }
 *
 *             @Override
 *             public int getFillHeight() {
 *                 return 40;
 *             }
 *
 *             @Override
 *             public int getFrameOffsetY() {
 *                 return -10;
 *             }
 *
 *             @Override
 *             public int getFillOffsetY() {
 *                 return -10;
 *             }
 *         };
 *     }
 *
 *     @Override
 *     public EntityLayerExtension entityLayerExtension() {
 *         return new EntityLayerExtension() {
 *             @Override
 *             public boolean enabled() {
 *                 return true;
 *             }
 *
 *             @Override
 *             public RenderType getRenderType() {
 *                 return YourCustomRenderTypes.ENTITY_LAYER;
 *             }
 *
 *             @Override
 *             public float getAlpha() {
 *                 return 0.5f;
 *             }
 *         };
 *     }
 *
 *     @Override
 *     public GlobalFogExtension globalFogExtension() {
 *         return new GlobalFogExtension() {
 *             @Override
 *             public boolean enabled() {
 *                 return true;
 *             }
 *
 *             @Override
 *             public boolean globalMode() {
 *                 return true;
 *             }
 *
 *             @Override
 *             public float radius() {
 *                 return 64.0f;
 *             }
 *
 *             @Override
 *             public float fogRed() {
 *                 return 0.22f;
 *             }
 *
 *             @Override
 *             public float fogGreen() {
 *                 return 0.17f;
 *             }
 *
 *             @Override
 *             public float fogBlue() {
 *                 return 0.30f;
 *             }
 *
 *             @Override
 *             public float terrainFogStart(float renderDistance) {
 *                 return renderDistance * 0.03f;
 *             }
 *
 *             @Override
 *             public float terrainFogEnd(float renderDistance) {
 *                 return renderDistance * 0.28f;
 *             }
 *         };
 *     }
 *
 *     @Override
 *     public GlobalSkyboxExtension globalSkyboxExtension() {
 *         return new GlobalSkyboxExtension() {
 *             @Override
 *             public boolean enabled() {
 *                 return true;
 *             }
 *
 *             @Override
 *             public boolean enableTexture() {
 *                 return true;
 *             }
 *
 *             @Override
 *             public ResourceLocation texture() {
 *                 return WardenExtension.this.texture("shader/starfield.png");
 *             }
 *
 *             @Override
 *             public boolean enableShader() {
 *                 return true;
 *             }
 *
 *             @Override
 *             public RenderType shaderRenderType() {
 *                 return YourCustomRenderTypes.SKYBOX;
 *             }
 *
 *             @Override
 *             public float alpha() {
 *                 return 0.55f;
 *             }
 *
 *             @Override
 *             public float textureUvScale() {
 *                 return 8.0f;
 *             }
 *
 *             @Override
 *             public float textureRed() {
 *                 return 0.58f;
 *             }
 *
 *             @Override
 *             public float textureGreen() {
 *                 return 0.72f;
 *             }
 *
 *             @Override
 *             public float textureBlue() {
 *                 return 1.0f;
 *             }
 *         };
 *     }
 *
 *     @Override
 *     public CombatMusicExtension combatMusicExtension() {
 *         return new CombatMusicExtension() {
 *             @Override
 *             public boolean enabled() {
 *                 return true;
 *             }
 *
 *             @Override
 *             public ResourceLocation soundEventId() {
 *                 return ResourceLocation.parse("your_mod_id:epic_battle_music");
 *             }
 *
 *             @Override
 *             public float volume() {
 *                 return 1.0f;
 *             }
 *
 *             @Override
 *             public boolean loop() {
 *                 return true;
 *             }
 *
 *             @Override
 *             public boolean strictMusicLock() {
 *                 return true;
 *             }
 *         };
 *     }
 * }
 * }</pre>
 *
 * <p><b>Important Notes</b></p>
 * <ul>
 *   <li>Each {@code EntityType} can only have ONE extension. Duplicate registrations will be rejected with an error log.</li>
 *   <li>Boss bars are automatically created for entities without native {@code ServerBossEvent} fields. For entities with native boss bars (Wither, Ender Dragon), custom rendering replaces the vanilla appearance.</li>
 *   <li>Global effects only activate when at least one entity of that type is alive in the dimension.</li>
 *   <li>The {@code texture()} helper automatically prepends "textures/" and uses your mod ID.</li>
 *   <li>All client-side methods are annotated with {@code @OnlyIn(Dist.CLIENT)} - do not call them on the server.</li>
 * </ul>
 *
 * @see net.eca.util.entity_extension.EntityExtension
 * @see net.eca.util.entity_extension.EntityExtensionManager
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterEntityExtension {

}
