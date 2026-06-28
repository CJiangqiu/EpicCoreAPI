package net.eca.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class EcaConfiguration {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_ENABLE_RADICAL_LOGIC;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SET_HEALTH_ENABLE_CONST_OVERRIDE;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SET_HEALTH_ENABLE_EXTERNAL_SCAN;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SET_HEALTH_ENABLE_METHOD_PROBE;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SET_HEALTH_ENABLE_WRITE_SITE_BRIDGE;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_SET_HEALTH_ENABLE_NUMERIC_INVERSION;
    public static ForgeConfigSpec.ConfigValue<Boolean> DEFENCE_ENABLE_RADICAL_LOGIC;
    public static ForgeConfigSpec.ConfigValue<Boolean> DEFENCE_INVULNERABLE_UNTARGETABLE;
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
            .comment("Enable radical attack logic: memoryRemove, AllReturn, aggressive setHealth, etc. WARNING: This may cause game instability!",
                     "启用激进攻击逻辑：memoryRemove、AllReturn、激进 setHealth 等。警告：可能导致游戏不稳定！")
            .define("Enable Radical Logic", false);

        BUILDER.push("setHealth");

        ATTACK_SET_HEALTH_ENABLE_CONST_OVERRIDE = BUILDER
            .comment("Enable const override for setHealth after dataflow analysis fails, on entities whose getHealth returns an immutable constant: register an override value that hijacks getHealth's return. Server-side only. Requires Attack.Enable Radical Logic.",
                     "启动 setHealth 常数覆盖：数据流逆向失败、且实体 getHealth 返回不可变常数时，登记覆盖值劫持其 getHealth 返回。仅服务端生效。需要启用 Attack.Enable Radical Logic。")
            .define("Enable Const Override", false);

        ATTACK_SET_HEALTH_ENABLE_EXTERNAL_SCAN = BUILDER
            .comment("Enable external scan for setHealth after const override fails: reverse-analyze isAlive/isDeadOrDying and hurt/actuallyHurt bytecode to locate the real health storage. Requires Attack.Enable Radical Logic.",
                     "启动 setHealth 外部扫描：常数覆盖失败后，逆向分析 isAlive/isDeadOrDying 与 hurt/actuallyHurt 字节码定位真实血量存储。需要启用 Attack.Enable Radical Logic。")
            .define("Enable External Scan", false);

        ATTACK_SET_HEALTH_ENABLE_METHOD_PROBE = BUILDER
            .comment("Enable method probe for setHealth after external scan fails: invoke the entity's own numeric write methods to find the one that actually moves health. Requires Attack.Enable Radical Logic.",
                     "启动 setHealth 方法探针：外部扫描失败后，尝试调用实体自身的数值写入方法定位真正改动血量的那个。需要启用 Attack.Enable Radical Logic。")
            .define("Enable Method Probe", false);

        ATTACK_SET_HEALTH_ENABLE_WRITE_SITE_BRIDGE = BUILDER
            .comment("Enable write site bridge for setHealth after method probe fails: bridge to the real write site already present inside candidate methods. Requires Attack.Enable Radical Logic.",
                     "启动 setHealth 写入点桥接：方法探针失败后，桥接候选方法内部已有的真实写入点。需要启用 Attack.Enable Radical Logic。")
            .define("Enable Write Site Bridge", false);

        ATTACK_SET_HEALTH_ENABLE_NUMERIC_INVERSION = BUILDER
            .comment("NOT RECOMMENDED, may cause lag and crashes. Enable numeric inversion for setHealth as the final fallback after write site bridge fails: perturb numeric values to locate the real health field. Requires Attack.Enable Radical Logic.",
                     "不推荐，可能造成卡顿和崩溃。启动 setHealth 数值反演：作为最后兜底，写入桥接失败后通过数值扰动定位真实血量字段。需要启用 Attack.Enable Radical Logic。")
            .define("Enable Numeric Inversion", false);

        BUILDER.pop();

        BUILDER.pop();

        // Defence Configuration | 防御系统配置
        BUILDER.push("Defence");

        DEFENCE_ENABLE_RADICAL_LOGIC = BUILDER
            .comment("Enable radical defence logic: ReTransformer after all mods loaded, etc.",
                     "启用激进防御逻辑：所有模组加载后执行 ReTransformer 等。")
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

    public static boolean getAttackSetHealthEnableConstOverrideSafely() {
        return safeGet(ATTACK_SET_HEALTH_ENABLE_CONST_OVERRIDE, false);
    }

    public static boolean getAttackSetHealthEnableExternalScanSafely() {
        return safeGet(ATTACK_SET_HEALTH_ENABLE_EXTERNAL_SCAN, false);
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

}
