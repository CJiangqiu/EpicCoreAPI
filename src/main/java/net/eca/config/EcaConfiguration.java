package net.eca.config;

import net.eca.compat.FriendModCheck;
import net.minecraftforge.common.ForgeConfigSpec;

public class EcaConfiguration {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static ForgeConfigSpec.ConfigValue<Boolean> FORCE_COMPATIBILITY_MODE;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_ENABLE_RADICAL_LOGIC;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SETHEALTH_ENABLE_CONST_OVERRIDE;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SETHEALTH_ENABLE_EXTERNAL_SCAN;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SETHEALTH_ENABLE_METHOD_PROBE;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SETHEALTH_ENABLE_NUMERIC_INVERSION;
    public static ForgeConfigSpec.ConfigValue<Boolean> DEFENCE_ENABLE_RADICAL_LOGIC;
    public static ForgeConfigSpec.ConfigValue<Boolean> DEFENCE_INVULNERABLE_UNTARGETABLE;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTRIBUTE_UNLOCK_LIMITS;
    public static ForgeConfigSpec.ConfigValue<Boolean> ENABLE_CUSTOM_LOADING_BACKGROUND;
    public static ForgeConfigSpec.IntValue FORCE_LOADING_MAX_RENDER_DISTANCE;
    public static ForgeConfigSpec.IntValue BOSSSHOW_MAX_SUBTITLE_DURATION_TICKS;
    public static ForgeConfigSpec.IntValue BOSSSHOW_RANGE_SCAN_INTERVAL_TICKS;
    public static ForgeConfigSpec.IntValue BOSSSHOW_ENTITY_SELECTION_RANGE;

    // Faction Configuration | 阵营系统配置
    public static ForgeConfigSpec.BooleanValue FACTION_ACTION_BAR_MESSAGES;
    public static ForgeConfigSpec.BooleanValue FACTION_GLOW_ENABLED;
    public static ForgeConfigSpec.IntValue FACTION_GLOW_RANGE;
    public static ForgeConfigSpec.IntValue FACTION_GLOW_UPDATE_INTERVAL_TICKS;
    public static ForgeConfigSpec.ConfigValue<String> FACTION_GLOW_HOSTILE_COLOR;
    public static ForgeConfigSpec.ConfigValue<String> FACTION_GLOW_FRIENDLY_COLOR;
    public static ForgeConfigSpec.ConfigValue<String> FACTION_GLOW_NEUTRAL_COLOR;
    public static ForgeConfigSpec.ConfigValue<String> FACTION_GLOW_SAME_FACTION_COLOR;
    public static ForgeConfigSpec.BooleanValue FACTION_ALERT_ENABLED;
    public static ForgeConfigSpec.IntValue FACTION_ALERT_RANGE;

    static {
        // Compatibility Configuration | 兼容性配置
        FORCE_COMPATIBILITY_MODE = BUILDER
            .comment("Force compatibility mode: when enabled, most ECA capabilities will be disabled to reduce potential compatibility issues with other mods."
                    + " All bytecode transformations, retransforms, and dataflow warmup are skipped entirely.",
                     "强制兼容模式：开启后，ECA 的大部分能力将会失效，用于减少可能导致的兼容问题。"
                     + " 所有字节码转换、重转换、数据流预热均被跳过。")
            .define("Force Compatibility Mode", false);

        // Attack Configuration | 攻击系统配置
        BUILDER.push("Attack");

        ATTACK_ENABLE_RADICAL_LOGIC = BUILDER
            .comment("Enable radical attack logic: memoryRemove, AllReturn, etc. WARNING: This may cause game instability!",
                     "启用激进攻击逻辑：memoryRemove、AllReturn 等。警告：可能导致游戏不稳定！")
            .define("Enable Radical Logic", false);

        BUILDER.push("setHealth");

        ATTACK_SETHEALTH_ENABLE_CONST_OVERRIDE = BUILDER
            .comment("Enable setHealth constant-override: patch constant-returning getHealth to consult a per-entity override table.",
                     "启用 setHealth 常数覆写：将返回常数的 getHealth 改写为查询按实体的覆写表。")
            .define("Enable Const Override", false);

        ATTACK_SETHEALTH_ENABLE_EXTERNAL_SCAN = BUILDER
            .comment("Enable setHealth external scan: when getHealth is defended, also reverse isAlive/isDeadOrDying/hurt/actuallyHurt to locate the real health storage. Requires radical logic.",
                     "启用 setHealth 外部扫描：getHealth 被防守时，额外逆向 isAlive/isDeadOrDying/hurt/actuallyHurt 定位真实血量存储。需同时开启激进逻辑。")
            .define("Enable External Scan", false);

        ATTACK_SETHEALTH_ENABLE_METHOD_PROBE = BUILDER
            .comment("Enable setHealth method-probe: when storage is unreachable, drive the entity's own health-writing method (behavioral direct call, or a HEAD-injected token/writer bridge). Requires radical logic.",
                     "启用 setHealth 方法探针：存储不可达时，驱动实体自身的血量写方法(行为直调，或 HEAD 注入的 token/writer 桥)。需同时开启激进逻辑。")
            .define("Enable Method Probe", false);

        ATTACK_SETHEALTH_ENABLE_NUMERIC_INVERSION = BUILDER
            .comment("Enable setHealth numeric inversion: at the dead-end of dataflow reversal (custom non-invertible decode), descend into the live object graph and perturb primitive cells to drive getHealth to target. Requires radical logic.",
                     "启用 setHealth 数值反演：数据流逆向在自定义非可逆解码处死角时，深入运行期对象图扰动原始 cell，令 getHealth 逼近目标。需同时开启激进逻辑。")
            .define("Enable Numeric Inversion", false);

        BUILDER.pop();

        BUILDER.pop();

        // Defence Configuration | 防御系统配置
        BUILDER.push("Defence");

        DEFENCE_ENABLE_RADICAL_LOGIC = BUILDER
            .comment("Enable radical defence logic: when the Java agent is unavailable, start JVMTI to perform retransformation.",
                     "启用激进防御逻辑：当 Java agent 不可用时，将会启动 JVMTI 进行重转换。")
            .define("Enable Radical Logic", false);

        DEFENCE_INVULNERABLE_UNTARGETABLE = BUILDER
            .comment("Prevent mobs from targeting invulnerable entities via setTarget.",
                     "启用后无敌实体不可被其他实体通过 setTarget 锁定为目标。")
            .define("Invulnerable Entity Untargetable", true);

        BUILDER.pop();

        ATTRIBUTE_UNLOCK_LIMITS = BUILDER
            .comment("Unlock vanilla attribute upper limit to Double.MAX_VALUE (1.7976931348623157E308)",
                     "解除原版属性上限至 Double.MAX_VALUE（1.7976931348623157E308）")
            .define("Unlock Attribute Limits", true);

        ENABLE_CUSTOM_LOADING_BACKGROUND = BUILDER
            .comment("Enable custom loading background rendered by agent transform",
                     "启用由 agent transform 渲染的自定义加载背景")
            .define("Enable Custom Loading Background", true);

        FORCE_LOADING_MAX_RENDER_DISTANCE = BUILDER
            .comment("Maximum render distance (in blocks) for force-loaded entities.",
                     "强制加载实体的最大渲染（方块）")
            .defineInRange("Force Loading Max Render Distance", 128, 2, Integer.MAX_VALUE);

        // BossShow Configuration | 演出系统配置
        BUILDER.push("BossShow");

        BOSSSHOW_MAX_SUBTITLE_DURATION_TICKS = BUILDER
            .comment("Maximum duration (in ticks) a subtitle stays on screen.",
                     "字幕在屏幕上的最长保留时间（tick）")
            .defineInRange("Max Subtitle Duration Ticks", 100, 1, 72000);

        BOSSSHOW_RANGE_SCAN_INTERVAL_TICKS = BUILDER
            .comment("Interval (in ticks) between trigger scans for Range-type BossShow entries.",
                     "范围触发类型的 BossShow 进行触发扫描的间隔（tick）")
            .defineInRange("Range Scan Interval Ticks", 10, 1, 200);

        BOSSSHOW_ENTITY_SELECTION_RANGE = BUILDER
            .comment("Reach distance (in blocks) for the entity-bind raytrace during recording selection mode. Default 64.",
                     "录制选择模式下实体绑定射线追踪的触及距离（方块）。默认 64。")
            .defineInRange("Entity Selection Range", 64, 4, 256);

        BUILDER.pop();

        // Faction Configuration | 阵营系统配置
        BUILDER.push("Faction");

        FACTION_ACTION_BAR_MESSAGES = BUILDER
            .comment("Show an action-bar message when a player attempts to attack a same-faction or friendly entity.",
                     "玩家尝试攻击同阵营或友好阵营实体时，在动作栏显示提示消息。")
            .define("Action Bar Messages", true);

        FACTION_GLOW_ENABLED = BUILDER
            .comment("Enable faction-based entity glow outlines. Entities within range glow with a color that " +
                     "reflects their faction relation to the observing player. Disabled by default to reduce " +
                     "server-side scanning overhead.",
                     "启用阵营实体发光描边：范围内实体根据与观察玩家的阵营关系显示不同颜色发光。" +
                     "默认关闭以减少服务端扫描性能损耗。")
            .define("Entity Glow Enabled", false);

        FACTION_GLOW_RANGE = BUILDER
            .comment("Maximum range (in blocks) for faction glow scanning.",
                     "阵营发光扫描的最大范围（方块）。")
            .defineInRange("Entity Glow Range", 32, 2, 128);

        FACTION_GLOW_UPDATE_INTERVAL_TICKS = BUILDER
            .comment("Interval (in ticks) between faction glow scans for each player.",
                     "每位玩家阵营发光扫描的间隔（tick）。")
            .defineInRange("Glow Update Interval Ticks", 20, 5, 200);

        FACTION_GLOW_HOSTILE_COLOR = BUILDER
            .comment("ARGB hex color for entities in a hostile faction (e.g. \"FFFF0000\" for red).",
                     "敌对阵营实体的发光颜色，ARGB 十六进制（如 \"FFFF0000\" 红色）。")
            .define("Hostile Glow Color", "FFFF0000");

        FACTION_GLOW_FRIENDLY_COLOR = BUILDER
            .comment("ARGB hex color for entities in a friendly (allied but different) faction (e.g. \"FF0000FF\" for blue).",
                     "友好阵营（不同阵营但结盟）实体的发光颜色，ARGB 十六进制（如 \"FF0000FF\" 蓝色）。")
            .define("Friendly Glow Color", "FF0000FF");

        FACTION_GLOW_NEUTRAL_COLOR = BUILDER
            .comment("ARGB hex color for entities in a neutral faction (e.g. \"FFFFFF00\" for yellow).",
                     "中立阵营实体的发光颜色，ARGB 十六进制（如 \"FFFFFF00\" 黄色）。")
            .define("Neutral Glow Color", "FFFFFF00");

        FACTION_GLOW_SAME_FACTION_COLOR = BUILDER
            .comment("ARGB hex color for entities in the same faction (e.g. \"FF00FF00\" for green).",
                     "同阵营实体的发光颜色，ARGB 十六进制（如 \"FF00FF00\" 绿色）。")
            .define("Same Faction Glow Color", "FF00FF00");

        FACTION_ALERT_ENABLED = BUILDER
            .comment("When enabled, attacking a faction member causes nearby same-faction mobs to target the attacker.",
                     "开启后，攻击阵营成员会导致附近同阵营生物将攻击者设为目标。")
            .define("Alert Enabled", true);

        FACTION_ALERT_RANGE = BUILDER
            .comment("Maximum range (in blocks) for faction alert — how far away allies will respond to an attack.",
                     "阵营求援最大范围（方块）：友方实体响应攻击的最大距离。")
            .defineInRange("Alert Range", 32, 2, 128);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    // Safe Config Access Methods | 安全的配置访问方法
    private static <T> T safeGet(ForgeConfigSpec.ConfigValue<T> configValue, T defaultValue) {
        try {
            return configValue != null ? configValue.get() : defaultValue;
        } catch (IllegalStateException | NullPointerException e) {
            return defaultValue;
        }
    }

    public static boolean getForceCompatibilityModeSafely() {
        return safeGet(FORCE_COMPATIBILITY_MODE, false);
    }

    public static boolean getAttackEnableRadicalLogicSafely() {
        // 优先级链第 1 级：强制兼容模式 → 全部关闭
        if (getForceCompatibilityModeSafely()) return false;
        // 优先级链第 2 级：联动 mod 存在时强制开启，无视配置
        if (FriendModCheck.hasRadicalCompatModLoaded()) return true;
        // 优先级链第 3 级：按配置
        return safeGet(ATTACK_ENABLE_RADICAL_LOGIC, false);
    }

    public static boolean getAttackSetHealthEnableConstOverrideSafely() {
        return safeGet(ATTACK_SETHEALTH_ENABLE_CONST_OVERRIDE, false);
    }

    public static boolean getAttackSetHealthEnableExternalScanSafely() {
        return safeGet(ATTACK_SETHEALTH_ENABLE_EXTERNAL_SCAN, false);
    }

    public static boolean getAttackSetHealthEnableMethodProbeSafely() {
        return safeGet(ATTACK_SETHEALTH_ENABLE_METHOD_PROBE, false);
    }

    public static boolean getAttackSetHealthEnableNumericInversionSafely() {
        return safeGet(ATTACK_SETHEALTH_ENABLE_NUMERIC_INVERSION, false);
    }

    public static boolean getDefenceEnableRadicalLogicSafely() {
        // 优先级链第 1 级：强制兼容模式 → 全部关闭
        if (getForceCompatibilityModeSafely()) return false;
        // 优先级链第 2 级：联动 mod 存在时强制开启，无视配置
        if (FriendModCheck.hasRadicalCompatModLoaded()) return true;
        // 优先级链第 3 级：按配置
        return safeGet(DEFENCE_ENABLE_RADICAL_LOGIC, false);
    }

    public static boolean getDefenceInvulnerableUntargetableSafely() {
        return safeGet(DEFENCE_INVULNERABLE_UNTARGETABLE, true);
    }

    public static boolean getAttributeUnlockLimitsSafely() {
        return safeGet(ATTRIBUTE_UNLOCK_LIMITS, true);
    }

    public static boolean getEnableCustomLoadingBackgroundSafely() {
        return safeGet(ENABLE_CUSTOM_LOADING_BACKGROUND, true);
    }

    public static int getForceLoadingMaxRenderDistanceSafely() {
        return safeGet(FORCE_LOADING_MAX_RENDER_DISTANCE, 128);
    }

    public static int getBossShowMaxSubtitleDurationTicksSafely() {
        return safeGet(BOSSSHOW_MAX_SUBTITLE_DURATION_TICKS, 100);
    }

    public static int getBossShowRangeScanIntervalTicksSafely() {
        return safeGet(BOSSSHOW_RANGE_SCAN_INTERVAL_TICKS, 10);
    }

    public static int getBossShowEntitySelectionRangeSafely() {
        return safeGet(BOSSSHOW_ENTITY_SELECTION_RANGE, 64);
    }

    // Faction Configuration Safe Access Methods | 阵营系统安全访问方法

    public static boolean getFactionActionBarMessagesSafely() {
        return safeGet(FACTION_ACTION_BAR_MESSAGES, true);
    }

    public static boolean getFactionGlowEnabledSafely() {
        return safeGet(FACTION_GLOW_ENABLED, false);
    }

    public static int getFactionGlowRangeSafely() {
        return safeGet(FACTION_GLOW_RANGE, 32);
    }

    public static int getFactionGlowUpdateIntervalTicksSafely() {
        return safeGet(FACTION_GLOW_UPDATE_INTERVAL_TICKS, 20);
    }

    public static String getFactionGlowHostileColorSafely() {
        return safeGet(FACTION_GLOW_HOSTILE_COLOR, "FFFF0000");
    }

    public static String getFactionGlowFriendlyColorSafely() {
        return safeGet(FACTION_GLOW_FRIENDLY_COLOR, "FF00FF00");
    }

    public static String getFactionGlowNeutralColorSafely() {
        return safeGet(FACTION_GLOW_NEUTRAL_COLOR, "FFFFFF00");
    }

    public static String getFactionGlowSameFactionColorSafely() {
        return safeGet(FACTION_GLOW_SAME_FACTION_COLOR, "FF00FF00");
    }

    public static boolean getFactionAlertEnabledSafely() {
        return safeGet(FACTION_ALERT_ENABLED, true);
    }

    public static int getFactionAlertRangeSafely() {
        return safeGet(FACTION_ALERT_RANGE, 32);
    }

    // 将配置中的十六进制颜色字符串解析为 ARGB int
    /**
     * Parse a config hex color string (e.g. "FFFF0000") to an ARGB int.
     * Returns the fallback if parsing fails.
     *
     * @param hexStr   the hex color string
     * @param fallback fallback ARGB int
     * @return parsed ARGB int
     */
    public static int parseHexColor(String hexStr, int fallback) {
        if (hexStr == null || hexStr.isEmpty()) return fallback;
        try {
            return (int) Long.parseLong(hexStr, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

}
