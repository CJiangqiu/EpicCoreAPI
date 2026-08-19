package net.eca.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/*
 * 单个被追踪实体的复活记录。实体实例可能被彻底销毁——容器全清、removalReason 置位、
 * 乃至外部反射抹掉 ECA 挂在实体上的状态，因此除 instance 这个纯优化用的快路径引用外，
 * 本类所有字段都是脱离实体的自足数据：仅凭一条记录即可在零引用前提下把实体重建出来。
 * 维度存 ResourceKey 而非 ServerLevel，避免记录钉住一个已卸载的世界。
 */
public final class ResurrectionRecord {

    final UUID uuid;

    volatile ResourceKey<Level> dimension;
    volatile CompoundTag nbt;
    volatile ResourceLocation typeId;
    volatile Vec3 position;
    volatile float yRot;
    volatile float xRot;
    volatile float health;

    /* ECA 状态意图值。只在读到有效值时更新，读不到一律保留旧值：
       第三方抹掉锁血/禁疗后若跟着把 null 写进记录，等于把篡改结果存档，
       再拿它去"还原"就是替对方把篡改固化。解除这些状态只能走显式接口。 */
    volatile Float healthLock;
    volatile Float maxHealthLock;
    volatile Float healBan;
    volatile boolean invulnerable;

    /* 快路径与诊断用，均不作判据：网络 ID 在实体重建后会变，实例引用随时可能失效。 */
    volatile int lastNetworkId = -1;
    volatile Entity instance;

    volatile long lastSnapshotAt;
    volatile long lastClientProbeAt;
    volatile long lastRebuildAt;
    volatile long lastLevelScanAt;

    ResurrectionRecord(UUID uuid) {
        this.uuid = uuid;
    }

    // 记录是否已具备重建实体所需的全部数据
    boolean hasSnapshot() {
        return nbt != null && dimension != null;
    }
}
