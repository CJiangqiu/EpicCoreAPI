# EpicCoreAPI

A library mod made by CJiangqiu for his other mods. Despite the name "Core API", this is **NOT** a Core Mod.


## Usage for Players

Use `/eca` command (requires OP permission level 2):

- `/eca setHealth <targets> <health>` - Set entity health
- `/eca setInvulnerable <targets> <true|false>` - Set entity invulnerability
- `/eca kill <targets>` - Kill entities
- `/eca remove <targets> [reason]` - Remove entities from world

## Usage for Developers
1. **Reflection**: Get/set field values with automatic obfuscation mapping (`getFieldValue`, `setFieldValue`, `invokeMethod`)
2. **VarHandle**: High-performance field access with atomic operations (`getAndSet`, `compareAndSet`)
3. **Health**: Get and set entity health, supports custom entities with non-standard health storage (`getHealth`, `setHealth`, `modifyEntityHealth`)
4. **Entity Control**: Kill, revive, and completely remove entities (`killEntity`, `reviveEntity`, `removeEntity`)
5. **Invulnerability**: Manage entity invulnerable state via NBT (`isInvulnerable`, `setInvulnerable`)
6. **Logging**: Register prefixed loggers for your mod (`registerLogger`)
7. **Mapping**: Register custom obfuscation mappings (`registerFieldMapping`, `registerMethodMapping`)
```java
import net.eca.api.EcaAPI;

// Reflection
Object data = EcaAPI.getFieldValue(entity, Entity.class, "Entity.entityData");

// Health
float hp = EcaAPI.getHealth(entity);
EcaAPI.setHealth(entity, 100.0f);

// Entity control
EcaAPI.killEntity(entity, damageSource);
EcaAPI.reviveEntity(entity);

// Logging
var logger = EcaAPI.registerLogger("yourmod");
logger.info("Hello!");
```

---

# 中文

这是 CJiangqiu 为自己的其他 Mod 制作的库模组。虽然名字叫 Core API，但**不是** Core Mod。

## 玩家使用

使用 `/eca` 命令（需要 OP 权限等级 2）：

- `/eca setHealth <目标> <血量>` - 设置实体血量
- `/eca setInvulnerable <目标> <true|false>` - 设置实体无敌状态
- `/eca kill <目标>` - 击杀实体
- `/eca remove <目标> [原因]` - 从世界中移除实体

## 开发者使用
1. **反射**: 获取/设置字段值，自动处理混淆映射（`getFieldValue`, `setFieldValue`, `invokeMethod`）
2. **VarHandle**: 高性能字段访问，支持原子操作（`getAndSet`, `compareAndSet`）
3. **生命值**: 获取和设置实体生命值，支持自定义存储方式的实体（`getHealth`, `setHealth`, `modifyEntityHealth`）
4. **实体控制**: 击杀、复活、完全清除实体（`killEntity`, `reviveEntity`, `removeEntity`）
5. **无敌状态**: 通过 NBT 管理实体无敌状态（`isInvulnerable`, `setInvulnerable`）
6. **日志**: 为你的 Mod 注册带前缀的日志器（`registerLogger`）
7. **映射**: 注册自定义混淆映射（`registerFieldMapping`, `registerMethodMapping`）
```java
import net.eca.api.EcaAPI;

// 反射
Object data = EcaAPI.getFieldValue(entity, Entity.class, "Entity.entityData");

// 生命值
float hp = EcaAPI.getHealth(entity);
EcaAPI.setHealth(entity, 100.0f);

// 实体控制
EcaAPI.killEntity(entity, damageSource);
EcaAPI.reviveEntity(entity);

// 日志
var logger = EcaAPI.registerLogger("yourmod");
logger.info("Hello!");
```

---

**Author / 作者**: CJiangqiu
