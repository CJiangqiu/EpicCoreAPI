package net.eca.compat;

import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/*
 * 友方 mod 检测：当联动 mod 存在时，激进逻辑必须在转换级强制开启(无视配置关闭)，
 * 否则这些 mod 的实体无法被 ECA 改血通道正确写入。
 * 此检测位于配置优先级链的第 2 级——高于激进配置，低于强制兼容模式。
 */
public final class FriendModCheck {

    private static final List<String> RADICAL_COMPAT_MODS = List.of(
        "the_last_sword",
        "ultimateskeletons",
        "dream_sakura"
    );

    private static volatile Boolean cached = null;

    private FriendModCheck() {}

    /* 检测任一联动 mod 是否已加载。结果在首次调用后缓存(这些 mod 不会在运行期热增删)。 */
    public static boolean hasRadicalCompatModLoaded() {
        Boolean result = cached;
        if (result != null) return result;
        try {
            ModList modList = ModList.get();
            if (modList == null) {
                cached = false;
                return false;
            }
            for (String modId : RADICAL_COMPAT_MODS) {
                if (modList.isLoaded(modId)) {
                    cached = true;
                    return true;
                }
            }
        } catch (IllegalStateException | NullPointerException ignored) {
            // ModList 尚未初始化(极早期调用)：保守返回 false，后续调用会重新求值
            return false;
        }
        cached = false;
        return false;
    }

    /* 返回当前实际加载且会强制开启激进逻辑的联动 mod。 */
    public static List<String> getLoadedRadicalCompatModIds() {
        List<String> loaded = new ArrayList<>();
        try {
            ModList modList = ModList.get();
            if (modList == null) return loaded;
            for (String modId : RADICAL_COMPAT_MODS) {
                if (modList.isLoaded(modId)) {
                    loaded.add(modId);
                }
            }
        } catch (IllegalStateException | NullPointerException ignored) {
            // 玩家登录前 ModList 应已就绪；异常时不发送可能误导玩家的提示。
        }
        return loaded;
    }

    /* 每个联动使用独立消息键，使整行文本可以拥有各自的颜色。 */
    public static String getForcedMessageTranslationKey(String modId) {
        return "message.eca.radical_forced." + modId;
    }

    /* 强制清除缓存，供测试或运行期重新检测 */
    public static void clearCache() {
        cached = null;
    }
}
