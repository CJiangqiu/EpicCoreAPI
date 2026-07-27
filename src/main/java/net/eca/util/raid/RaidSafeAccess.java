package net.eca.util.raid;

import net.eca.client.render.EcaBossBarRenderer;
import net.eca.util.EcaLogger;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 袭击扩展安全调用层：框架内部调用模组作者重写的 RaidDefinition / RaidBossBarExtension
 * 方法时一律经此类，不直接调用。重写代码抛出的任何异常都会被捕获并退回安全默认值，
 * 避免渲染线程因第三方袭击实现而崩溃。
 *
 * 与 EntityExtensionSafeAccess 同一职责，针对袭击的血条外观接口。
 */
@OnlyIn(Dist.CLIENT)
public final class RaidSafeAccess {

    // 已记录异常的 类#方法 组合，去重避免每帧刷屏
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private RaidSafeAccess() {}

    public static RaidBossBarExtension bossBarExtension(RaidDefinition definition) {
        if (definition == null) {
            return null;
        }
        try {
            return definition.bossBarExtension();
        } catch (Throwable t) {
            logOnce(definition.getClass(), "bossBarExtension", t);
            return null;
        }
    }

    public static boolean enabled(RaidBossBarExtension bar) {
        if (bar == null) {
            return false;
        }
        try {
            return bar.enabled();
        } catch (Throwable t) {
            logOnce(bar.getClass(), "enabled", t);
            return false;
        }
    }

    public static boolean shouldRender(RaidBossBarExtension bar, RaidBarState state) {
        if (bar == null) {
            return false;
        }
        try {
            return bar.shouldRender(state);
        } catch (Throwable t) {
            logOnce(bar.getClass(), "shouldRender", t);
            return true;
        }
    }

    public static float progress(RaidBossBarExtension bar, RaidBarState state, float barProgress) {
        if (bar == null) {
            return barProgress;
        }
        try {
            return bar.getProgress(state, barProgress);
        } catch (Throwable t) {
            logOnce(bar.getClass(), "getProgress", t);
            return barProgress;
        }
    }

    // 解析全部外观参数；任一项抛异常时该项退回安全默认值，不影响其余项
    /**
     * Resolve every appearance property of a raid bar, isolating each getter so one broken
     * override cannot take down the whole bar.
     *
     * @param bar   the appearance provider
     * @param state the current raid snapshot
     * @return the resolved appearance, never null
     */
    public static EcaBossBarRenderer.BarAppearance resolveAppearance(RaidBossBarExtension bar, RaidBarState state) {
        EcaBossBarRenderer.BarAppearance appearance = new EcaBossBarRenderer.BarAppearance();
        if (bar == null) {
            return appearance;
        }

        Class<?> type = bar.getClass();
        try {
            appearance.frameTexture = bar.getFrameTexture(state);
        } catch (Throwable t) {
            logOnce(type, "getFrameTexture", t);
        }
        try {
            appearance.fillTexture = bar.getFillTexture(state);
        } catch (Throwable t) {
            logOnce(type, "getFillTexture", t);
        }
        try {
            appearance.frameRenderType = bar.getFrameRenderType(state);
        } catch (Throwable t) {
            logOnce(type, "getFrameRenderType", t);
        }
        try {
            appearance.fillRenderType = bar.getFillRenderType(state);
        } catch (Throwable t) {
            logOnce(type, "getFillRenderType", t);
        }
        try {
            appearance.frameWidth = bar.getFrameWidth();
            appearance.frameHeight = bar.getFrameHeight();
        } catch (Throwable t) {
            logOnce(type, "getFrameWidth/Height", t);
        }
        try {
            appearance.fillWidth = bar.getFillWidth();
            appearance.fillHeight = bar.getFillHeight();
        } catch (Throwable t) {
            logOnce(type, "getFillWidth/Height", t);
        }
        try {
            appearance.frameOffsetX = bar.getFrameOffsetX();
            appearance.frameOffsetY = bar.getFrameOffsetY();
        } catch (Throwable t) {
            logOnce(type, "getFrameOffsetX/Y", t);
        }
        try {
            appearance.fillOffsetX = bar.getFillOffsetX();
            appearance.fillOffsetY = bar.getFillOffsetY();
        } catch (Throwable t) {
            logOnce(type, "getFillOffsetX/Y", t);
        }
        try {
            appearance.frameAlpha = bar.getFrameAlpha(state);
        } catch (Throwable t) {
            logOnce(type, "getFrameAlpha", t);
        }
        try {
            appearance.fillAlpha = bar.getFillAlpha(state);
        } catch (Throwable t) {
            logOnce(type, "getFillAlpha", t);
        }
        return appearance;
    }

    private static void logOnce(Class<?> type, String method, Throwable t) {
        String key = type.getName() + "#" + method;
        if (LOGGED.add(key)) {
            EcaLogger.error("Raid bar extension " + type.getName()
                + " threw in " + method + ", falling back to safe default", t);
        }
    }
}
