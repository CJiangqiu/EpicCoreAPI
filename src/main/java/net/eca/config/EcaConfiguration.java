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

    //（未来扩展）

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

        //（未来扩展）

        BUILDER.pop();

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
}

