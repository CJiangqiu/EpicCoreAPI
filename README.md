# EpicCoreAPI

A library mod made by CJiangqiu for his other mods. Provides powerful entity manipulation APIs through CoreMod (ITransformationService), Java Agent, and VarHandle technologies.

This mod unlocks vanilla attribute limits to Double.MAX_VALUE by default. You can disable this in the config file with "Unlock Attribute Limits" option.

## Usage for Players

Use `/eca` command (requires OP permission level 2):
- `/eca setHealth <targets> <health>` - Set entity health
- `/eca setInvulnerable <targets> <true|false>` - Set entity invulnerability
- `/eca lockHealth <targets> true <value>` - Lock entity health at specific value
- `/eca lockHealth <targets> false` - Unlock entity health
- `/eca kill <targets>` - Kill entities
- `/eca remove <targets> [reason]` - Remove entities from world
- `/eca memoryRemove <targets>` - DANGER! Requires Attack Radical Logic config. Remove entities via LWJGL internal channel
- `/eca teleport <targets> <x> <y> <z>` - Teleport entities
- `/eca cleanbossbar <targets>` - Clean up boss bars
- `/eca allreturn <targets>` - DANGER! Requires Attack Radical Logic config. Performs return transformation on all boolean and void methods of the target entity's mod
- `/eca allreturn global <true|false>` - DANGER! Enable/disable global AllReturn for all non-whitelisted mods
- `/eca allreturn off` - Disable AllReturn and clear targets
- `/eca spawnban <targets> <seconds>` - Ban spawning of selected entities' types for specified duration
- `/eca spawnban clear` - Clear all spawn bans in current dimension

## Usage for Developers

### Adding ECA as Dependency

**Step 1: Add CurseMaven repository** (build.gradle)
```groovy
repositories {
    maven { url = "https://cursemaven.com"; content { includeGroup "curse.maven" } }
}
```

**Step 2: Add ECA dependency** (build.gradle)
```groovy
dependencies {
    implementation fg.deobf("curse.maven:epic-core-api-977556:FILE_ID")
}
```
> Go to [ECA CurseForge Files](https://www.curseforge.com/minecraft/mc-mods/epic-core-api/files), click on the version you need, and copy the **Curse Maven Snippet** from the file page.

**Step 3: Declare dependency** (mods.toml)
```toml
[[dependencies.your_mod_id]]
modId="eca"
mandatory=true
versionRange="[1.0.5,)"
ordering="NONE"
side="BOTH"
```

### API Reference

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
- `memoryRemoveEntity(entity)` - DANGER! Requires Attack Radical Logic config. Remove entity via LWJGL internal channel
- `cleanupBossBar(entity)` - Remove boss bars without removing entity
- `isInvulnerable(entity)` - Check if entity is invulnerable (ECA internal invulnerability logic)
- `setInvulnerable(entity, invulnerable)` - Set invulnerability (auto-manages health lock)
- `enableAllReturn(entity)` - DANGER! Requires Attack Radical Logic config. Performs return transformation on all boolean and void methods of the target entity's mod
- `setGlobalAllReturn(enable)` - DANGER! Requires Attack Radical Logic config. Enable/disable global AllReturn for all non-whitelisted mods
- `disableAllReturn()` - Disable AllReturn and clear targets
- `isAllReturnEnabled()` - Check if AllReturn is enabled
- `PackageWhitelist.addProtection(prefix)` - Add custom package prefix to whitelist (e.g., "com.example.")
- `addSpawnBan(level, entityType, seconds)` - Ban entity type from spawning for specified duration
- `isSpawnBanned(level, entityType)` - Check if entity type is banned from spawning
- `getSpawnBanTime(level, entityType)` - Get remaining spawn ban time in seconds
- `clearSpawnBan(level, entityType)` - Clear spawn ban for entity type
- `getAllSpawnBans(level)` - Get all spawn bans in level (Map<EntityType, Integer>)
- `clearAllSpawnBans(level)` - Clear all spawn bans in level

```java
import net.eca.api.EcaAPI;
import net.eca.agent.PackageWhitelist;

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
EcaAPI.memoryRemoveEntity(entity);  // Remove via LWJGL internal channel
EcaAPI.cleanupBossBar(entity);

// Invulnerability
EcaAPI.setInvulnerable(entity, true);
boolean isInv = EcaAPI.isInvulnerable(entity);
EcaAPI.setInvulnerable(entity, false);

// AllReturn (DANGER! Requires Attack Radical Logic config)
EcaAPI.enableAllReturn(entity);  // Enable for entity's mod
EcaAPI.setGlobalAllReturn(true);  // Enable for ALL non-whitelisted mods
boolean enabled = EcaAPI.isAllReturnEnabled();
EcaAPI.setGlobalAllReturn(false);  // Disable global
EcaAPI.disableAllReturn();  // Disable and clear all

// Custom Package Whitelist (protect your mod from AllReturn)
PackageWhitelist.addProtection("com.yourmod.");

// Spawn Ban
EcaAPI.addSpawnBan(serverLevel, EntityType.ZOMBIE, 300);  // Ban zombies for 5 minutes
boolean banned = EcaAPI.isSpawnBanned(serverLevel, EntityType.ZOMBIE);
int remaining = EcaAPI.getSpawnBanTime(serverLevel, EntityType.ZOMBIE);
EcaAPI.clearSpawnBan(serverLevel, EntityType.ZOMBIE);
Map<EntityType<?>, Integer> allBans = EcaAPI.getAllSpawnBans(serverLevel);
EcaAPI.clearAllSpawnBans(serverLevel);
```

---

# 中文

由 CJiangqiu 为自己的其他 Mod 所制作的前置 Mod。提供一些强大的实体操作 API，通过 CoreMod (ITransformationService)、Java Agent、VarHandle 等技术实现。

本 Mod 默认将原版属性上限解锁至 Double.MAX_VALUE。如不需要，可在配置文件 "Unlock Attribute Limits" 中关闭。

## 玩家使用

使用 `/eca` 命令（需要 OP 权限等级 2）：
- `/eca setHealth <目标> <血量值>` - 设置实体血量值
- `/eca setInvulnerable <目标> <true|false>` - 设置实体无敌状态
- `/eca lockHealth <目标> true <血量值>` - 锁定实体血量
- `/eca lockHealth <目标> false` - 解锁实体血量
- `/eca kill <目标>` - 击杀实体
- `/eca remove <目标> [原因]` - 从世界中移除实体
- `/eca memoryRemove <目标>` - 危险！需要开启激进攻击逻辑配置，通过LWJGL内部通道清除实体
- `/eca teleport <目标> <x> <y> <z>` - 传送实体
- `/eca cleanbossbar <目标>` - 清理 Boss 血条
- `/eca allreturn <目标>` - 危险！需要开启激进攻击逻辑配置，会尝试对目标实体的所属mod的全部布尔和void方法进行return transformation
- `/eca allreturn global <true|false>` - 危险！启用/禁用全局AllReturn，影响所有非白名单mod
- `/eca allreturn off` - 关闭AllReturn并清除目标
- `/eca spawnban <目标> <秒数>` - 禁止选中实体的类型生成指定时长
- `/eca spawnban clear` - 清除当前维度所有禁生成

## 开发者使用

### 添加 ECA 依赖

**第一步：添加 CurseMaven 仓库** (build.gradle)
```groovy
repositories {
    maven { url = "https://cursemaven.com"; content { includeGroup "curse.maven" } }
}
```

**第二步：添加 ECA 依赖** (build.gradle)
```groovy
dependencies {
    implementation fg.deobf("curse.maven:epic-core-api-977556:FILE_ID")
}
```
> 前往 [ECA CurseForge 文件页](https://www.curseforge.com/minecraft/mc-mods/epic-core-api/files)，点击所需版本，从文件页面复制 **Curse Maven Snippet**。

**第三步：声明依赖** (mods.toml)
```toml
[[dependencies.你的modId]]
modId="eca"
mandatory=true
versionRange="[1.0.5,)"
ordering="NONE"
side="BOTH"
```

### API 参考

- `lockHealth(entity, value)` - 锁定实体血量值（用于无敌阶段、治疗等）
- `unlockHealth(entity)` - 解除血量锁定
- `getLockedHealth(entity)` - 获取当前锁定值（未锁定返回 null）
- `isHealthLocked(entity)` - 检查是否锁定
- `getHealth(entity)` - 使用 VarHandle 获取原版血量值
- `setHealth(entity, health)` - 多阶段血量值修改（原版 + 自定义 EntityData + 真实值回溯）
- `addHealthWhitelistKeyword(keyword)` - 添加血量值修改白名单关键词
- `removeHealthWhitelistKeyword(keyword)` - 移除血量值修改白名单关键词
- `getHealthWhitelistKeywords()` - 获取全部白名单关键词
- `addHealthBlacklistKeyword(keyword)` - 添加血量值修改黑名单关键词
- `removeHealthBlacklistKeyword(keyword)` - 移除血量值修改黑名单关键词
- `getHealthBlacklistKeywords()` - 获取全部黑名单关键词
- `killEntity(entity, damageSource)` - 击杀实体（掉落 + 成就 + 移除）
- `reviveEntity(entity)` - 复活实体（清除死亡状态）
- `teleportEntity(entity, x, y, z)` - VarHandle 传送并同步到客户端
- `removeEntity(entity, reason)` - 完整移除（AI、Boss 血条、容器、乘客等）
- `memoryRemoveEntity(entity)` - 危险！需要开启激进攻击逻辑配置，通过LWJGL内部通道清除实体
- `cleanupBossBar(entity)` - 仅移除 Boss 血条
- `isInvulnerable(entity)` - 检查 ECA 无敌状态
- `setInvulnerable(entity, invulnerable)` - 设置无敌状态（自动管理血量锁定）
- `enableAllReturn(entity)` - 危险！需要开启激进攻击逻辑配置，会尝试对目标实体的所属mod的全部布尔和void方法进行return transformation
- `setGlobalAllReturn(enable)` - 危险！需要开启激进攻击逻辑配置，启用/禁用全局AllReturn，影响所有非白名单mod
- `disableAllReturn()` - 关闭AllReturn并清除目标
- `isAllReturnEnabled()` - 检查AllReturn是否启用
- `PackageWhitelist.addProtection(prefix)` - 添加自定义包名前缀到白名单（如 "com.example."）
- `addSpawnBan(level, entityType, seconds)` - 禁止指定实体类型生成指定时长
- `isSpawnBanned(level, entityType)` - 检查实体类型是否被禁生成
- `getSpawnBanTime(level, entityType)` - 获取禁生成剩余秒数
- `clearSpawnBan(level, entityType)` - 清除指定实体类型的禁生成
- `getAllSpawnBans(level)` - 获取所有禁生成（Map<EntityType, Integer>）
- `clearAllSpawnBans(level)` - 清除所有禁生成

```java
import net.eca.api.EcaAPI;
import net.eca.agent.PackageWhitelist;

// 血量锁定
EcaAPI.lockHealth(entity, 20.0f);
Float locked = EcaAPI.getLockedHealth(entity);
EcaAPI.unlockHealth(entity);

// 基础血量访问
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
EcaAPI.memoryRemoveEntity(entity);  // 通过LWJGL内部通道清除
EcaAPI.cleanupBossBar(entity);

// 无敌状态
EcaAPI.setInvulnerable(entity, true);
boolean isInv = EcaAPI.isInvulnerable(entity);
EcaAPI.setInvulnerable(entity, false);

// AllReturn（危险！需开启激进攻击配置）
EcaAPI.enableAllReturn(entity);  // 对实体所属mod启用
EcaAPI.setGlobalAllReturn(true);  // 对所有非白名单mod启用
boolean enabled = EcaAPI.isAllReturnEnabled();
EcaAPI.setGlobalAllReturn(false);  // 禁用全局
EcaAPI.disableAllReturn();  // 关闭并清除全部

// 自定义包名白名单（保护你的mod免受AllReturn影响）
PackageWhitelist.addProtection("com.yourmod.");

// 禁生成
EcaAPI.addSpawnBan(serverLevel, EntityType.ZOMBIE, 300);  // 禁止僵尸生成5分钟
boolean banned = EcaAPI.isSpawnBanned(serverLevel, EntityType.ZOMBIE);
int remaining = EcaAPI.getSpawnBanTime(serverLevel, EntityType.ZOMBIE);
EcaAPI.clearSpawnBan(serverLevel, EntityType.ZOMBIE);
Map<EntityType<?>, Integer> allBans = EcaAPI.getAllSpawnBans(serverLevel);
EcaAPI.clearAllSpawnBans(serverLevel);
```

---

**Author / 作者**: CJiangqiu
