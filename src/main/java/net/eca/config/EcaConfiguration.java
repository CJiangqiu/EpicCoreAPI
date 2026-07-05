package net.eca.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class EcaConfiguration {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

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

    static {
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
