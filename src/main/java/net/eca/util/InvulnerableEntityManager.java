package net.eca.util;

import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//无敌实体管理器（用于测试和验证）
public class InvulnerableEntityManager {

    //存储所有无敌实体的 UUID
    private static final Set<UUID> INVULNERABLE_ENTITIES = ConcurrentHashMap.newKeySet();

    //添加无敌实体记录
    public static void addInvulnerable(Entity entity) {
        if (entity == null) return;
        INVULNERABLE_ENTITIES.add(entity.getUUID());
    }

    //移除无敌实体记录
    public static void removeInvulnerable(Entity entity) {
        if (entity == null) return;
        INVULNERABLE_ENTITIES.remove(entity.getUUID());
    }

    //检查是否是无敌实体（通过UUID）
    public static boolean isInvulnerable(UUID uuid) {
        return INVULNERABLE_ENTITIES.contains(uuid);
    }

    //获取所有无敌实体的 UUID
    public static Set<UUID> getAllInvulnerableUUIDs() {
        return Set.copyOf(INVULNERABLE_ENTITIES);
    }

    //获取无敌实体数量
    public static int getInvulnerableCount() {
        return INVULNERABLE_ENTITIES.size();
    }

    //清空所有记录
    public static void clearAll() {
        INVULNERABLE_ENTITIES.clear();
    }

}
