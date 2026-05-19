package net.eca.util.entity_extension;

import net.eca.util.EcaLogger;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 实体扩展安全调用层：框架内部调用模组作者重写的 EntityExtension 方法时一律经此类，不直接调用。
 * 带参重载在 entity 为 null 时不会把 null 交给重写代码，而是退回无参版；重写代码抛出的任何异常
 * 都会被捕获并退回安全默认值，避免渲染 / 网络 / tick 线程因第三方扩展实现而崩溃。
 */
@OnlyIn(Dist.CLIENT)
public final class EntityExtensionSafeAccess {

    // 已记录异常的 扩展类#方法 组合，去重避免每帧刷屏
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private EntityExtensionSafeAccess() {}

    public static BossBarExtension bossBarExtension(EntityExtension ext, LivingEntity entity) {
        if (ext == null) {
            return null;
        }
        if (entity != null) {
            try {
                return ext.bossBarExtension(entity);
            } catch (Throwable t) {
                logOnce(ext, "bossBarExtension(LivingEntity)", t);
            }
        }
        try {
            return ext.bossBarExtension();
        } catch (Throwable t) {
            logOnce(ext, "bossBarExtension()", t);
            return null;
        }
    }

    public static EntityLayerExtension entityLayerExtension(EntityExtension ext, LivingEntity entity) {
        if (ext == null) {
            return null;
        }
        if (entity != null) {
            try {
                return ext.entityLayerExtension(entity);
            } catch (Throwable t) {
                logOnce(ext, "entityLayerExtension(LivingEntity)", t);
            }
        }
        try {
            return ext.entityLayerExtension();
        } catch (Throwable t) {
            logOnce(ext, "entityLayerExtension()", t);
            return null;
        }
    }

    public static GlobalFogExtension globalFogExtension(EntityExtension ext, LivingEntity entity) {
        if (ext == null) {
            return null;
        }
        if (entity != null) {
            try {
                return ext.globalFogExtension(entity);
            } catch (Throwable t) {
                logOnce(ext, "globalFogExtension(LivingEntity)", t);
            }
        }
        try {
            return ext.globalFogExtension();
        } catch (Throwable t) {
            logOnce(ext, "globalFogExtension()", t);
            return null;
        }
    }

    public static GlobalSkyboxExtension globalSkyboxExtension(EntityExtension ext, LivingEntity entity) {
        if (ext == null) {
            return null;
        }
        if (entity != null) {
            try {
                return ext.globalSkyboxExtension(entity);
            } catch (Throwable t) {
                logOnce(ext, "globalSkyboxExtension(LivingEntity)", t);
            }
        }
        try {
            return ext.globalSkyboxExtension();
        } catch (Throwable t) {
            logOnce(ext, "globalSkyboxExtension()", t);
            return null;
        }
    }

    public static CombatMusicExtension combatMusicExtension(EntityExtension ext, LivingEntity entity) {
        if (ext == null) {
            return null;
        }
        if (entity != null) {
            try {
                return ext.combatMusicExtension(entity);
            } catch (Throwable t) {
                logOnce(ext, "combatMusicExtension(LivingEntity)", t);
            }
        }
        try {
            return ext.combatMusicExtension();
        } catch (Throwable t) {
            logOnce(ext, "combatMusicExtension()", t);
            return null;
        }
    }

    // entity 为 null 视为门控通过，与 resolveConditionalEffects 原有 (entity == null || ...) 语义一致
    public static boolean shouldEnableFog(EntityExtension ext, LivingEntity entity) {
        if (ext == null || entity == null) {
            return true;
        }
        try {
            return ext.shouldEnableFog(entity);
        } catch (Throwable t) {
            logOnce(ext, "shouldEnableFog", t);
            return true;
        }
    }

    public static boolean shouldEnableSkybox(EntityExtension ext, LivingEntity entity) {
        if (ext == null || entity == null) {
            return true;
        }
        try {
            return ext.shouldEnableSkybox(entity);
        } catch (Throwable t) {
            logOnce(ext, "shouldEnableSkybox", t);
            return true;
        }
    }

    public static boolean shouldEnableMusic(EntityExtension ext, LivingEntity entity) {
        if (ext == null || entity == null) {
            return true;
        }
        try {
            return ext.shouldEnableMusic(entity);
        } catch (Throwable t) {
            logOnce(ext, "shouldEnableMusic", t);
            return true;
        }
    }

    private static void logOnce(EntityExtension ext, String method, Throwable t) {
        String key = ext.getClass().getName() + "#" + method;
        if (LOGGED.add(key)) {
            EcaLogger.error("EntityExtension " + ext.getClass().getName()
                + " threw in " + method + ", falling back to safe default", t);
        }
    }
}
