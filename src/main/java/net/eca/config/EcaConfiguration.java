package net.eca.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class EcaConfiguration {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    // ═══════════════════════════════════════════════════════════════════════════════════
    // Attack Configuration | 攻击系统配置
    // ═══════════════════════════════════════════════════════════════════════════════════

    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_ENABLE_RADICAL_LOGIC;

    // ═══════════════════════════════════════════════════════════════════════════════════
    // Defence Configuration | 防御系统配置
    // ═══════════════════════════════════════════════════════════════════════════════════

    public static ForgeConfigSpec.ConfigValue<Boolean> DEFENCE_ENABLE_RADICAL_LOGIC;

    // ═══════════════════════════════════════════════════════════════════════════════════
    // Attribute Configuration | 属性系统配置
    // ═══════════════════════════════════════════════════════════════════════════════════

    public static ForgeConfigSpec.ConfigValue<Boolean> ATTRIBUTE_UNLOCK_LIMITS;

    static {
        // ═══════════════════════════════════════════════════════════════════════════════
        // Attack Configuration | 攻击系统配置
        // ═══════════════════════════════════════════════════════════════════════════════
        BUILDER.push("Attack");

        ATTACK_ENABLE_RADICAL_LOGIC = BUILDER
            .comment("Enable radical logic for attack system, which increases attack processing strength but may cause performance overhead and mod conflicts")
            .define("Enable Radical Logic", false);

        BUILDER.pop();

        // ═══════════════════════════════════════════════════════════════════════════════
        // Defence Configuration | 防御系统配置
        // ═══════════════════════════════════════════════════════════════════════════════
        BUILDER.push("Defence");

        DEFENCE_ENABLE_RADICAL_LOGIC = BUILDER
            .comment(
                "Enable radical logic for defence system",
                "When enabled:",
                "  - Increases defence processing strength",
                "  - Enables EntityData protection to block unauthorized modifications from other mods",
                "WARNING: May cause performance overhead and mod conflicts"
            )
            .define("Enable Radical Logic", false);

        BUILDER.pop();

        ATTRIBUTE_UNLOCK_LIMITS = BUILDER
            .comment(
                "Unlock vanilla attribute limits to allow extreme values",
                "When enabled, all vanilla attributes (health, armor, damage, etc.) can exceed their normal limits",
                "Limits will be extended to Double.MAX_VALUE (±1.7976931348623157E308)",
                "WARNING: This may cause compatibility issues with some mods or game instability",
                "Requires game restart to take effect"
            )
            .define("Unlock Attribute Limits", true);

        SPEC = BUILDER.build();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════
    // Safe Config Access Methods | 安全的配置访问方法
    // ═══════════════════════════════════════════════════════════════════════════════════

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

    public static boolean getDefenceEnableRadicalLogicSafely() {
        return safeGet(DEFENCE_ENABLE_RADICAL_LOGIC, false);
    }

    public static boolean getAttributeUnlockLimitsSafely() {
        return safeGet(ATTRIBUTE_UNLOCK_LIMITS, true);
    }
}

