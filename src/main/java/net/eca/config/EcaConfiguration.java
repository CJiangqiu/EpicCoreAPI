package net.eca.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class EcaConfiguration {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static ForgeConfigSpec.ConfigValue<Boolean> ATTACK_ENABLE_RADICAL_LOGIC;
    public static ForgeConfigSpec.ConfigValue<Boolean> DEFENCE_ENABLE_RADICAL_LOGIC;
    public static ForgeConfigSpec.ConfigValue<Boolean> ATTRIBUTE_UNLOCK_LIMITS;

    static {
        // Attack Configuration | 攻击系统配置
        BUILDER.push("Attack");

        ATTACK_ENABLE_RADICAL_LOGIC = BUILDER
            .comment("Enable radical attack logic: memoryRemove, AllReturn, aggressive setHealth, etc. WARNING: This may cause game instability!")
            .define("Enable Radical Logic", false);

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

    public static boolean getDefenceEnableRadicalLogicSafely() {
        return safeGet(DEFENCE_ENABLE_RADICAL_LOGIC, false);
    }

    public static boolean getAttributeUnlockLimitsSafely() {
        return safeGet(ATTRIBUTE_UNLOCK_LIMITS, true);
    }

}

