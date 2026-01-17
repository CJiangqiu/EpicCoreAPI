# EpicCoreAPI

A library mod made by CJiangqiu for his other mods. Despite the name "Core API", this is **NOT** a Core Mod.

This mod unlocks vanilla attribute limits to Double.MAX_VALUE by default. You can disable this in the config file with "Unlock Attribute Limits" option.

## Usage for Players

Use `/eca` command (requires OP permission level 2):
- `/eca setHealth <targets> <health>` - Set entity health
- `/eca setInvulnerable <targets> <true|false>` - Set entity invulnerability
- `/eca lockHealth <targets> <value>` - Lock entity health at specific value
- `/eca unlockHealth <targets>` - Unlock entity health
- `/eca kill <targets>` - Kill entities
- `/eca remove <targets> [reason]` - Remove entities from world
- `/eca teleport <targets> <x> <y> <z>` - Teleport entities
- `/eca cleanbossbar <targets>` - Clean up boss bars
- `/eca allreturn <targets>` - DANGER! Requires Attack Radical Logic config. Performs return transformation on all boolean and void methods of the target entity's mod
- `/eca allreturn off` - Disable AllReturn and clear targets

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
- `cleanupBossBar(entity)` - Remove boss bars without removing entity
- `isInvulnerable(entity)` - Check if entity is invulnerable (ECA internal invulnerability logic)
- `setInvulnerable(entity, invulnerable)` - Set invulnerability (auto-manages health lock)
- `enableAllReturn(entity)` - DANGER! Requires Attack Radical Logic config. Performs return transformation on all boolean and void methods of the target entity's mod
- `disableAllReturn()` - Disable AllReturn and clear targets
- `isAllReturnEnabled()` - Check if AllReturn is enabled

```java
import net.eca.api.EcaAPI;

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

// AllReturn (DANGER! Requires Attack Radical Logic config)
EcaAPI.enableAllReturn(entity);  // Enable for entity's mod
boolean enabled = EcaAPI.isAllReturnEnabled();
EcaAPI.disableAllReturn();  // Disable and clear
```

---

# 中文

由 CJiangqiu 为自己的其他 Mod 制作的库 Mod。虽然名字叫 Core API，但**不是** Core Mod。

本 Mod 默认将原版属性上限解锁至 Double.MAX_VALUE。如不需要，可在配置文件 "Unlock Attribute Limits" 中关闭。

## 玩家使用

使用 `/eca` 命令（需要 OP 权限等级 2）：
- `/eca setHealth <目标> <血量值>` - 设置实体血量值
- `/eca setInvulnerable <目标> <true|false>` - 设置实体无敌状态
- `/eca lockHealth <目标> <血量值>` - 锁定实体血量
- `/eca unlockHealth <目标>` - 解锁实体血量
- `/eca kill <目标>` - 击杀实体
- `/eca remove <目标> [原因]` - 从世界中移除实体
- `/eca teleport <目标> <x> <y> <z>` - 传送实体
- `/eca cleanbossbar <目标>` - 清理 Boss 血条
- `/eca allreturn <目标>` - 危险！需要开启激进攻击逻辑配置，会尝试对目标实体的所属mod的全部布尔和void方法进行return transformation
- `/eca allreturn off` - 关闭AllReturn并清除目标

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
- `cleanupBossBar(entity)` - 仅移除 Boss 血条
- `isInvulnerable(entity)` - 检查 ECA 无敌状态
- `setInvulnerable(entity, invulnerable)` - 设置无敌状态（自动管理血量锁定）
- `enableAllReturn(entity)` - 危险！需要开启激进攻击逻辑配置，会尝试对目标实体的所属mod的全部布尔和void方法进行return transformation
- `disableAllReturn()` - 关闭AllReturn并清除目标
- `isAllReturnEnabled()` - 检查AllReturn是否启用

```java
import net.eca.api.EcaAPI;

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
EcaAPI.cleanupBossBar(entity);

// 无敌状态
EcaAPI.setInvulnerable(entity, true);
boolean isInv = EcaAPI.isInvulnerable(entity);
EcaAPI.setInvulnerable(entity, false);

// AllReturn（危险！需开启激进攻击配置）
EcaAPI.enableAllReturn(entity);  // 对实体所属mod启用
boolean enabled = EcaAPI.isAllReturnEnabled();
EcaAPI.disableAllReturn();  // 关闭并清除
```

---

**Author / 作者**: CJiangqiu
