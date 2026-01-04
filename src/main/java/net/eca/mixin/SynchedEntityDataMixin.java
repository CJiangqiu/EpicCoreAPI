package net.eca.mixin;

import net.eca.config.EcaConfiguration;
import net.eca.util.EcaLogger;
import net.eca.util.EntityUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EntityData 安全防护 Mixin
 * 防止其他 mod 通过暴力修改破坏 ECA 的关键数据
 * 通过 Defence.EnableRadicalLogic 配置项控制
 */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataMixin {

    @Unique
    private static final String MOD_PACKAGE = "net.eca.";
    @Unique
    private static boolean eca$warningLogged = false;

    /**
     * 拦截 EntityData.set() 方法，保护 ECA 的关键数据
     * 仅在 Defence Radical Logic 启用时生效
     */
    @Inject(method = "set*", at = @At("HEAD"), cancellable = true)
    private <T> void eca$onSet(EntityDataAccessor<T> accessor, T value, CallbackInfo ci) {
        // 检查是否启用激进防御模式
        if (!EcaConfiguration.getDefenceEnableRadicalLogicSafely()) {
            return;
        }

        // 只保护 ECA 的关键数据
        if (!eca$isECAData(accessor)) {
            return;
        }

        // 检查调用栈是否来自 ECA 内部
        if (!eca$isECACall()) {
            // 非 ECA 调用，拒绝修改
            String caller = eca$getCallerInfo();

            // 只记录一次警告避免刷屏
            if (!eca$warningLogged) {
                EcaLogger.warn("[Security] Blocked unauthorized modification of ECA EntityData!");
                EcaLogger.warn("[Security] Accessor: {}", accessor);
                EcaLogger.warn("[Security] Caller: {}", caller);
                EcaLogger.warn("[Security] This message will only be shown once per session.");
                eca$warningLogged = true;
            }

            // 取消修改
            ci.cancel();
        }
    }

    /**
     * 检查是否是 ECA 的关键数据
     */
    @Unique
    private static boolean eca$isECAData(EntityDataAccessor<?> accessor) {
        return accessor == EntityUtil.HEALTH_LOCK_ENABLED ||
               accessor == EntityUtil.HEALTH_LOCK_VALUE ||
               accessor == EntityUtil.INVULNERABLE;
    }

    /**
     * 检查当前调用栈是否来自 ECA 内部
     */
    @Unique
    private static boolean eca$isECACall() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();

        // 从栈帧3开始检查（跳过 getStackTrace/isECACall/eca$onSet）
        for (int i = 3; i < Math.min(stack.length, 15); i++) {
            String className = stack[i].getClassName();

            // 只允许 ECA 自己的代码（包括 API、Util、Mixin 等）
            if (className.startsWith(MOD_PACKAGE)) {
                return true;
            }
        }

        // 不在 ECA 包内的调用全部拒绝
        return false;
    }

    /**
     * 获取调用者信息（用于日志）
     */
    @Unique
    private static String eca$getCallerInfo() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();

        // 查找第一个非 ECA 的调用者
        for (int i = 3; i < Math.min(stack.length, 15); i++) {
            String className = stack[i].getClassName();
            if (!className.startsWith(MOD_PACKAGE)) {
                return className + "." + stack[i].getMethodName() +
                       "(" + stack[i].getFileName() + ":" + stack[i].getLineNumber() + ")";
            }
        }

        return "Unknown";
    }
}
