package net.eca.util.raid;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side appearance of a raid's boss bar.
 *
 * <p>Return one from {@link RaidDefinition#bossBarExtension()} and override {@link #enabled()}
 * to replace the vanilla raid bar with custom textures and shaders. The frame is drawn at full
 * width as the backing layer; the fill is drawn on top, clipped horizontally by the bar's
 * progress. Supplying a texture and a RenderType for the same layer overlays the shader onto
 * the texture's opaque pixels.</p>
 *
 * <p>Visual properties come in two forms: a no-argument version, and an overload receiving a
 * {@link RaidBarState} snapshot so the bar can change with the raid — a different frame on the
 * final wave, a different fill once the raid is won or lost. The overloads default to
 * delegating to the no-argument version, so overriding only the simple form is enough for a
 * static appearance.</p>
 *
 * <p>Sizes and offsets are deliberately state-independent: changing them mid-raid would make
 * the bar jump around on screen, so they are resolved once per frame from the no-argument
 * getters.</p>
 *
 * <p>This is a raid-specific counterpart to the entity extension's boss bar system. It is not
 * interchangeable with it — the entity version's condition methods take a {@code LivingEntity},
 * which a raid has no equivalent of.</p>
 */
@OnlyIn(Dist.CLIENT)
public class RaidBossBarExtension {

    // 是否启用自定义血条；返回 false 时使用原版血条
    /**
     * @return true to take over rendering of this raid's boss bar
     */
    public boolean enabled() {
        return false;
    }

    // 当前状态下是否绘制血条；返回 false 时隐藏血条且不绘制原版
    /**
     * @param state the current raid snapshot
     * @return false to hide the bar entirely for this frame
     */
    public boolean shouldRender(RaidBarState state) {
        return true;
    }

    // ==================== 外框 ====================

    // 外框贴图
    /**
     * @return the frame texture, or null for none
     */
    public ResourceLocation getFrameTexture() {
        return null;
    }

    // 外框贴图（按袭击状态）
    /**
     * @param state the current raid snapshot
     * @return the frame texture for this state; defaults to {@link #getFrameTexture()}
     */
    public ResourceLocation getFrameTexture(RaidBarState state) {
        return getFrameTexture();
    }

    // 外框着色器
    /**
     * @return the frame RenderType, or null for none
     */
    public RenderType getFrameRenderType() {
        return null;
    }

    // 外框着色器（按袭击状态）
    /**
     * @param state the current raid snapshot
     * @return the frame RenderType for this state; defaults to {@link #getFrameRenderType()}
     */
    public RenderType getFrameRenderType(RaidBarState state) {
        return getFrameRenderType();
    }

    // 外框宽度（使用 RenderType 而非贴图时必须设置）
    /**
     * @return frame width in pixels; required when a frame RenderType is used without a texture
     */
    public int getFrameWidth() {
        return 0;
    }

    // 外框高度（使用 RenderType 而非贴图时必须设置）
    /**
     * @return frame height in pixels; required when a frame RenderType is used without a texture
     */
    public int getFrameHeight() {
        return 0;
    }

    // 外框横向偏移
    public int getFrameOffsetX() {
        return 0;
    }

    // 外框纵向偏移
    public int getFrameOffsetY() {
        return 0;
    }

    // 外框不透明度
    /**
     * @return frame alpha in range [0, 1]; 1.0 is fully opaque
     */
    public float getFrameAlpha() {
        return 1.0f;
    }

    // 外框不透明度（按袭击状态）
    /**
     * @param state the current raid snapshot
     * @return frame alpha for this state; defaults to {@link #getFrameAlpha()}
     */
    public float getFrameAlpha(RaidBarState state) {
        return getFrameAlpha();
    }

    // ==================== 填充 ====================

    // 填充贴图
    /**
     * @return the fill texture, or null for none
     */
    public ResourceLocation getFillTexture() {
        return null;
    }

    // 填充贴图（按袭击状态）
    /**
     * @param state the current raid snapshot
     * @return the fill texture for this state; defaults to {@link #getFillTexture()}
     */
    public ResourceLocation getFillTexture(RaidBarState state) {
        return getFillTexture();
    }

    // 填充着色器
    /**
     * @return the fill RenderType, or null for none
     */
    public RenderType getFillRenderType() {
        return null;
    }

    // 填充着色器（按袭击状态）
    /**
     * @param state the current raid snapshot
     * @return the fill RenderType for this state; defaults to {@link #getFillRenderType()}
     */
    public RenderType getFillRenderType(RaidBarState state) {
        return getFillRenderType();
    }

    // 填充宽度（使用 RenderType 而非贴图时必须设置）
    /**
     * @return fill width in pixels; required when a fill RenderType is used without a texture
     */
    public int getFillWidth() {
        return 0;
    }

    // 填充高度（使用 RenderType 而非贴图时必须设置）
    /**
     * @return fill height in pixels; required when a fill RenderType is used without a texture
     */
    public int getFillHeight() {
        return 0;
    }

    // 填充横向偏移
    public int getFillOffsetX() {
        return 0;
    }

    // 填充纵向偏移
    public int getFillOffsetY() {
        return 0;
    }

    // 填充不透明度
    /**
     * @return fill alpha in range [0, 1]; 1.0 is fully opaque
     */
    public float getFillAlpha() {
        return 1.0f;
    }

    // 填充不透明度（按袭击状态）
    /**
     * @param state the current raid snapshot
     * @return fill alpha for this state; defaults to {@link #getFillAlpha()}
     */
    public float getFillAlpha(RaidBarState state) {
        return getFillAlpha();
    }

    // ==================== 进度 ====================

    /*
     * 血条填充比例的来源。
     *
     * 默认使用同步过来的原版 BossEvent progress（即当前波次的剩余比例）。
     * 覆写可改为其他语义，例如按总波次进度、按存活人数绝对值等。
     */
    /**
     * Resolve the fill ratio of the bar.
     * <p>
     * Defaults to the synced boss bar progress, which tracks the current wave's remaining
     * fraction. Override to use a different meaning — overall wave progress, for instance.
     *
     * @param state       the current raid snapshot
     * @param barProgress the progress carried by the vanilla boss event
     * @return fill ratio, clamped by the renderer to [0, 1]
     */
    public float getProgress(RaidBarState state, float barProgress) {
        return barProgress;
    }
}
