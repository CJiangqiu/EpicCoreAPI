package net.eca.util.health;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/*
 * ECA 自身写进实体的状态清单。改血分析与写入据此排除自己的注入：血量锁密文、密钥与校验位
 * 在结构上与宿主的自定义血量存储无法区分，一旦被当作候选去解算并写入，锁会被自己破坏。
 * 所有写入方引用本类常量，使新增注入时不会漏登记。
 */
public final class EcaOwnedState {

    private EcaOwnedState() {}

    // 锁血（三字段加密）
    public static final String NBT_HEALTH_LOCK_ENC = "ecaHealthLockEnc";
    public static final String NBT_HEALTH_LOCK_KEY = "ecaHealthLockKey";
    public static final String NBT_HEALTH_LOCK_CHECK = "ecaHealthLockCheck";
    // 最大生命值锁定（三字段加密）
    public static final String NBT_MAX_HEALTH_LOCK_ENC = "ecaMaxHealthLockEnc";
    public static final String NBT_MAX_HEALTH_LOCK_KEY = "ecaMaxHealthLockKey";
    public static final String NBT_MAX_HEALTH_LOCK_CHECK = "ecaMaxHealthLockCheck";
    // 禁疗
    public static final String NBT_HEAL_BAN_VALUE = "ecaHealBanValue";
    // 无敌状态与复活追踪：EntityDataAccessor 缺失时的持久化回退
    public static final String NBT_INVULNERABLE = "ecaInvulnerable";
    public static final String NBT_RESURRECTION_TRACKED = "ecaResurrectionTracked";

    private static final Set<String> NBT_KEYS = Set.of(
            NBT_HEALTH_LOCK_ENC, NBT_HEALTH_LOCK_KEY, NBT_HEALTH_LOCK_CHECK,
            NBT_MAX_HEALTH_LOCK_ENC, NBT_MAX_HEALTH_LOCK_KEY, NBT_MAX_HEALTH_LOCK_CHECK,
            NBT_HEAL_BAN_VALUE, NBT_INVULNERABLE, NBT_RESURRECTION_TRACKED);

    /* 注入 getHealth 取值链的 ECA 类；其嵌套类由分析器按 '$' 前缀一并归属。 */
    private static final Set<String> HOOK_OWNERS = Set.of(
            "net/eca/coremod/LivingEntityHook",
            "net/eca/util/health/HealthLockManager");

    /* ECA 的 EntityDataAccessor 静态字段会被建模成静态字段源，按 label 前缀剥离。 */
    private static final Set<String> STATIC_FIELD_LABELS = Set.of(
            "SF:net.eca.util.EntityUtil.HEALTH_LOCK_VALUE",
            "SF:net.eca.util.EntityUtil.HEALTH_LOCK_KEY",
            "SF:net.eca.util.EntityUtil.HEALTH_LOCK_CHECK",
            "SF:net.eca.util.EntityUtil.HEAL_BAN_VALUE",
            "SF:net.eca.util.EntityUtil.INVULNERABLE",
            "SF:net.eca.util.EntityUtil.RESURRECTION_TRACKED",
            "SF:net.eca.util.EntityUtil.MAX_HEALTH_LOCK_VALUE",
            "SF:net.eca.util.EntityUtil.MAX_HEALTH_LOCK_KEY",
            "SF:net.eca.util.EntityUtil.MAX_HEALTH_LOCK_CHECK");

    /* accessor ID 只有注册后才确定，故运行期填充而非静态常量。 */
    private static final Set<Integer> SYNCHED_DATA_IDS = ConcurrentHashMap.newKeySet();

    public static Set<String> nbtKeys() {
        return NBT_KEYS;
    }

    public static Set<String> hookOwners() {
        return HOOK_OWNERS;
    }

    public static Set<String> staticFieldLabels() {
        return STATIC_FIELD_LABELS;
    }

    public static boolean isOwnedNbtKey(String key) {
        return key != null && NBT_KEYS.contains(key);
    }

    public static void registerSynchedDataId(int id) {
        if (id >= 0) SYNCHED_DATA_IDS.add(id);
    }

    public static boolean isOwnedSynchedDataId(int id) {
        return SYNCHED_DATA_IDS.contains(id);
    }

    public static IntPredicate ownedSynchedDataIds() {
        return EcaOwnedState::isOwnedSynchedDataId;
    }
}
