# EpicCoreAPI

A library mod made by CJiangqiu for his other mods. Despite the name "Core API", this is **NOT** a Core Mod.

This mod unlocks vanilla attribute limits to ±Double.MAX_VALUE by default. You can disable this in the config file with `"Unlock Attribute Limits"` option.

## Usage for Players

Use `/eca` command (requires OP permission level 2):

- `/eca setHealth <targets> <health>` - Set entity health
- `/eca setInvulnerable <targets> <true|false>` - Set entity invulnerability
- `/eca kill <targets>` - Kill entities
- `/eca remove <targets> [reason]` - Remove entities from world
- `/eca teleport <targets> <x> <y> <z>` - Teleport entities

## Usage for Developers

1. **Health**: Get and set entity health, supports custom entities with non-standard health storage (`getHealth`, `setHealth`, `modifyEntityHealth`, `triggerHealthAnalysis`, `getHealthCache`). You can customize health modification keyword lists via: `addHealthWhitelistKeyword`, `addHealthBlacklistKeyword`
2. **Entity Control**: Kill, revive, teleport, and completely remove entities (`killEntity`, `reviveEntity`, `teleportEntity`, `removeEntity`)
3. **Invulnerability**: Manage entity invulnerable state via NBT (`isInvulnerable`, `setInvulnerable`)
4. **Clear External Entity Data**: This mod provides functionality to clear entity data added by external mods (`clearExternalEntityData`). You can modify the data clearing blacklist via: `addDataClearBlacklistKeyword`

```java
import net.eca.api.EcaAPI;

// Health
float hp = EcaAPI.getHealth(entity);
EcaAPI.setHealth(entity, 100.0f);
EcaAPI.modifyEntityHealth(entity, 50.0f);
EcaAPI.addHealthWhitelistKeyword("mana");
EcaAPI.addHealthBlacklistKeyword("timer");

// Entity control
EcaAPI.killEntity(entity, damageSource);
EcaAPI.reviveEntity(entity);
EcaAPI.teleportEntity(entity, x, y, z);
EcaAPI.removeEntity(entity, Entity.RemovalReason.KILLED);

// Invulnerability
EcaAPI.setInvulnerable(entity, true);
boolean inv = EcaAPI.isInvulnerable(entity);

// Clear external entity data
EcaAPI.clearExternalEntityData(entity);
EcaAPI.addDataClearBlacklistKeyword("important");
```

---

# 中文

这是 CJiangqiu 为自己的其他 Mod 制作的Lib Mod。虽然名字叫 Core API，但**不是** Core Mod。

本Mod默认解锁了原版属性的上下限为正负Double.MAX_VALUE，如果不需要，可以在配置文件中通过 `"Unlock Attribute Limits"` 选项关闭该功能。

## 玩家使用

使用 `/eca` 命令（需要 OP 权限等级 2）：

- `/eca setHealth <目标> <血量>` - 设置实体血量
- `/eca setInvulnerable <目标> <true|false>` - 设置实体无敌状态
- `/eca kill <目标>` - 击杀实体
- `/eca remove <目标> [原因]` - 从世界中移除实体
- `/eca teleport <目标> <x> <y> <z>` - 传送实体

## 开发者使用

1. **生命值**：获取和设置实体生命值，支持自定义存储方式的实体（`getHealth`, `setHealth`, `modifyEntityHealth`, `triggerHealthAnalysis`, `getHealthCache`）。您可以通过以下API修改生命值相关的名单设置：`addHealthWhitelistKeyword`, `addHealthBlacklistKeyword`
2. **实体控制**：击杀、复活、传送、完全清除实体（`killEntity`, `reviveEntity`, `teleportEntity`, `removeEntity`）
3. **无敌状态**：通过 NBT 管理实体无敌状态（`isInvulnerable`, `setInvulnerable`）
4. **清除外部实体数据**：本Mod提供了清除外部Mod添加的实体数据的功能（`clearExternalEntityData`），您可以通过以下API修改清除数据的黑名单：`addDataClearBlacklistKeyword`

```java
import net.eca.api.EcaAPI;

// 生命值
float hp = EcaAPI.getHealth(entity);
EcaAPI.setHealth(entity, 100.0f);
EcaAPI.modifyEntityHealth(entity, 50.0f);
EcaAPI.addHealthWhitelistKeyword("mana");
EcaAPI.addHealthBlacklistKeyword("timer");

// 实体控制
EcaAPI.killEntity(entity, damageSource);
EcaAPI.reviveEntity(entity);
EcaAPI.teleportEntity(entity, x, y, z);
EcaAPI.removeEntity(entity, Entity.RemovalReason.KILLED);

// 无敌状态
EcaAPI.setInvulnerable(entity, true);
boolean inv = EcaAPI.isInvulnerable(entity);

// 清除外部实体数据
EcaAPI.clearExternalEntityData(entity);
EcaAPI.addDataClearBlacklistKeyword("important");
```

---

**Author / 作者**: CJiangqiu
