package net.eca.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class EcaConfiguration {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_ENABLE_RADICAL_LOGIC;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SET_HEALTH_ENABLE_METHOD_PROBE;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SET_HEALTH_ENABLE_WRITE_SITE_BRIDGE;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SET_HEALTH_ENABLE_NUMERIC_INVERSION;
    public static ForgeConfigSpec.ConfigValue<Boolean> DEFENCE_ENABLE_RADICAL_LOGIC;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTRIBUTE_UNLOCK_LIMITS;
    public static ForgeConfigSpec.ConfigValue<Boolean> ENABLE_CUSTOM_LOADING_BACKGROUND;
    public static ForgeConfigSpec.IntValue FORCE_LOADING_MAX_RENDER_DISTANCE;
    public static ForgeConfigSpec.IntValue BOSSSHOW_MAX_SUBTITLE_DURATION_TICKS;
    public static ForgeConfigSpec.IntValue BOSSSHOW_RANGE_SCAN_INTERVAL_TICKS;
    public static ForgeConfigSpec.IntValue BOSSSHOW_ENTITY_SELECTION_RANGE;

    static {
        // Attack Configuration | 攻击系统配置
        BUILDER.push("Attack");

        ATTACK_ENABLE_RADICAL_LOGIC = BUILDER
            .comment("Enable radical attack logic: memoryRemove, AllReturn, aggressive setHealth, etc. WARNING: This may cause game instability!")
            .define("Enable Radical Logic", false);

        BUILDER.push("setHealth");

        ATTACK_SET_HEALTH_ENABLE_METHOD_PROBE = BUILDER
            .comment("启动 setHealth 方法探针：数据流逆向失败后尝试调用实体自身的数值写入方法。需要 Attack.Enable Radical Logic。",
                     "Enable method probe for setHealth after dataflow analysis fails. Requires Attack.Enable Radical Logic.")
            .define("Enable Method Probe", false);

        ATTACK_SET_HEALTH_ENABLE_WRITE_SITE_BRIDGE = BUILDER
            .comment("启动 setHealth 写入点桥接：方法探针失败后尝试桥接候选方法内部已有的真实写入点。需要 Attack.Enable Radical Logic。",
                     "Enable write site bridge for setHealth after method probe fails. Requires Attack.Enable Radical Logic.")
            .define("Enable Write Site Bridge", false);

        ATTACK_SET_HEALTH_ENABLE_NUMERIC_INVERSION = BUILDER
            .comment("启动 setHealth 数值反演：数据流逆向和方法探针失败后尝试通过数值扰动定位真实血量。需要 Attack.Enable Radical Logic。",
                     "Enable numeric inversion for setHealth after dataflow analysis fails. Requires Attack.Enable Radical Logic.")
            .define("Enable Numeric Inversion", false);

        BUILDER.pop();

        BUILDER.pop();

        // Defence Configuration | 防御系统配置
        BUILDER.push("Defence");

        DEFENCE_ENABLE_RADICAL_LOGIC = BUILDER
            .comment("Enable radical defence logic: ReTransformer after all mods loaded, etc.")
            .define("Enable Radical Logic", false);

        BUILDER.pop();

        ATTRIBUTE_UNLOCK_LIMITS = BUILDER
            .comment("Unlock vanilla attribute min/max limits to ±Double.MAX_VALUE (±1.7976931348623157E308)")
            .define("Unlock Attribute Limits", true);

        ENABLE_CUSTOM_LOADING_BACKGROUND = BUILDER
            .comment("Enable custom loading background rendered by agent transform")
            .define("Enable Custom Loading Background", true);

        FORCE_LOADING_MAX_RENDER_DISTANCE = BUILDER
            .comment("Maximum render/tracking distance (in blocks) for force-loaded entities. Prevents unlimited packet sending.")
            .defineInRange("Force Loading Max Render Distance", 128, 32, 1024);

        // BossShow Configuration | 演出系统配置
        BUILDER.push("BossShow");

        BOSSSHOW_MAX_SUBTITLE_DURATION_TICKS = BUILDER
            .comment("Maximum duration (in ticks, 20 per second) a subtitle line stays on screen before auto-clearing.",
                     "Default 100 = 5 seconds.")
            .defineInRange("Max Subtitle Duration Ticks", 100, 1, 72000);

        BOSSSHOW_RANGE_SCAN_INTERVAL_TICKS = BUILDER
            .comment("Interval (in ticks) between server-side scans for Range-trigger entities.",
                     "Lower = snappier triggering, higher CPU cost. Default 10 = twice per second.")
            .defineInRange("Range Scan Interval Ticks", 10, 1, 200);

        BOSSSHOW_ENTITY_SELECTION_RANGE = BUILDER
            .comment("Reach distance (in blocks) for the entity-bind raytrace during recording selection mode.",
                     "Default 64.")
            .defineInRange("Entity Selection Range", 64, 4, 256);

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

    public static boolean getAttackEnableRadicalLogicSafely() {
        return safeGet(ATTACK_ENABLE_RADICAL_LOGIC, false);
    }

    public static boolean getAttackSetHealthEnableMethodProbeSafely() {
        return safeGet(ATTACK_SET_HEALTH_ENABLE_METHOD_PROBE, false);
    }

    public static boolean getAttackSetHealthEnableWriteSiteBridgeSafely() {
        return safeGet(ATTACK_SET_HEALTH_ENABLE_WRITE_SITE_BRIDGE, false);
    }

    public static boolean getAttackSetHealthEnableNumericInversionSafely() {
        return safeGet(ATTACK_SET_HEALTH_ENABLE_NUMERIC_INVERSION, false);
    }

    public static boolean getDefenceEnableRadicalLogicSafely() {
        return safeGet(DEFENCE_ENABLE_RADICAL_LOGIC, false);
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

}

