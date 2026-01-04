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
- `/eca cleanbossbar <targets>` - Clean up boss bars

## Usage for Developers

- `triggerHealthAnalysis(entity)` - Trigger bytecode analysis for entity's getHealth() to find real health storage
- `getHealthCache(entityClass)` - Get cached real health analysis result
- `modifyEntityHealth(entity, targetHealth)` - Modify health intelligently
- `lockHealth(entity, value)` - Lock entity health at specific value (for invincibility, heal negation, etc.)
- `unlockHealth(entity)` - Remove health lock
- `getLockedHealth(entity)` - Get current health lock value (null if not locked)
- `isHealthLocked(entity)` - Check if entity health is locked
- `getHealth(entity)` - Get vanilla health via VarHandle
- `setHealth(entity, health)` - Multi-phase health modification (vanilla + custom EntityData + bytecode-analyzed real health)
- `addHealthWhitelistKeyword(keyword)` - Add keyword to health modification whitelist
- `removeHealthWhitelistKeyword(keyword)` - Remove keyword from health modification whitelist
- `getHealthWhitelistKeywords()` - Get all health whitelist keywords
- `addHealthBlacklistKeyword(keyword)` - Add keyword to health modification blacklist
- `removeHealthBlacklistKeyword(keyword)` - Remove keyword from health modification blacklist
- `getHealthBlacklistKeywords()` - Get all health blacklist keywords
- `killEntity(entity, damageSource)` - Kill entity (loot + advancements + removal)
- `reviveEntity(entity)` - Clear death state and restore health
- `teleportEntity(entity, x, y, z)` - Teleport via VarHandle with client sync
- `removeEntity(entity, reason)` - Complete removal (AI, boss bars, containers, passengers)
- `cleanupBossBar(entity)` - Remove boss bars without removing entity
- `isInvulnerable(entity)` - Check if entity is invulnerable (ECA internal invulnerability logic)
- `setInvulnerable(entity, invulnerable)` - Set invulnerability (auto-manages health lock)

```java
import net.eca.api.EcaAPI;

// Health Analysis & Modification
EcaAPI.triggerHealthAnalysis(entity);
HealthFieldCache cache = EcaAPI.getHealthCache(entity.getClass());
boolean success = EcaAPI.modifyEntityHealth(entity, 100.0f);

// Health Lock
EcaAPI.lockHealth(entity, 20.0f);
Float locked = EcaAPI.getLockedHealth(entity);
EcaAPI.unlockHealth(entity);

// Basic Health Access
float realHealth = EcaAPI.getHealth(entity);
EcaAPI.setHealth(entity, 50.0f);

// Keyword Management
EcaAPI.addHealthWhitelistKeyword("mana");
EcaAPI.addHealthBlacklistKeyword("timer");

// Entity Control
EcaAPI.killEntity(entity, damageSource);
EcaAPI.reviveEntity(entity);
EcaAPI.teleportEntity(entity, x, y, z);
EcaAPI.removeEntity(entity, Entity.RemovalReason.KILLED);
EcaAPI.cleanupBossBar(entity);

// Invulnerability
EcaAPI.setInvulnerable(entity, true);
boolean isInv = EcaAPI.isInvulnerable(entity);
EcaAPI.setInvulnerable(entity, false);
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
- `/eca cleanbossbar <目标>` - 清理 Boss 血条

## 开发者使用

- `triggerHealthAnalysis(entity)` - 手动触发实体 getHealth() 进行真实生命值字节码分析
- `getHealthCache(entityClass)` - 获取缓存的真实生命值分析结果
- `modifyEntityHealth(entity, targetHealth)` - 智能修改生命值
- `lockHealth(entity, value)` - 锁定实体血量至指定值（用于锁血、禁疗等）
- `unlockHealth(entity)` - 移除血量锁定
- `getLockedHealth(entity)` - 获取当前血量锁定值（未锁定返回 null）
- `isHealthLocked(entity)` - 检查实体血量是否被锁定
- `getHealth(entity)` - 通过 VarHandle 获取原版血量
- `setHealth(entity, health)` - 多阶段生命值修改（原版 + 自定义实体数据血量 + 字节码分析的真实血量）
- `addHealthWhitelistKeyword(keyword)` - 添加生命值修改白名单关键词
- `removeHealthWhitelistKeyword(keyword)` - 移除生命值修改白名单关键词
- `getHealthWhitelistKeywords()` - 获取所有生命值白名单关键词
- `addHealthBlacklistKeyword(keyword)` - 添加生命值修改黑名单关键词
- `removeHealthBlacklistKeyword(keyword)` - 移除生命值修改黑名单关键词
- `getHealthBlacklistKeywords()` - 获取所有生命值黑名单关键词
- `killEntity(entity, damageSource)` - 击杀实体（掉落物 + 成就 + 移除）
- `reviveEntity(entity)` - 清除死亡状态并恢复血量
- `teleportEntity(entity, x, y, z)` - VarHandle 直接传送并同步客户端
- `removeEntity(entity, reason)` - 完整移除（AI、Boss血条、容器、乘客）
- `cleanupBossBar(entity)` - 仅移除 Boss 血条而不移除实体
- `isInvulnerable(entity)` - 检查实体是否无敌（ECA内部实现的无敌逻辑）
- `setInvulnerable(entity, invulnerable)` - 设置无敌状态（自动管理血量锁定）

```java
import net.eca.api.EcaAPI;

// 生命值分析与修改
EcaAPI.triggerHealthAnalysis(entity);
HealthFieldCache cache = EcaAPI.getHealthCache(entity.getClass());
boolean success = EcaAPI.modifyEntityHealth(entity, 100.0f);

// 血量锁定
EcaAPI.lockHealth(entity, 20.0f);
Float locked = EcaAPI.getLockedHealth(entity);
EcaAPI.unlockHealth(entity);

// 基础生命值访问
float realHealth = EcaAPI.getHealth(entity);
EcaAPI.setHealth(entity, 50.0f);

// 关键词管理
EcaAPI.addHealthWhitelistKeyword("mana");
EcaAPI.addHealthBlacklistKeyword("timer");

// 实体控制
EcaAPI.killEntity(entity, damageSource);
EcaAPI.reviveEntity(entity);
EcaAPI.teleportEntity(entity, x, y, z);
EcaAPI.removeEntity(entity, Entity.RemovalReason.KILLED);
EcaAPI.cleanupBossBar(entity);

// 无敌状态
EcaAPI.setInvulnerable(entity, true);
boolean isInv = EcaAPI.isInvulnerable(entity);
EcaAPI.setInvulnerable(entity, false);
```

---

**Author / 作者**: CJiangqiu
