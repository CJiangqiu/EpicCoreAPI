# EpicCoreAPI

This mod provides entity manipulation APIs and commands based on CoreMod (ITransformationService), Java Agent, and VarHandle technologies. Note that while method names may resemble vanilla logic, the underlying implementation is completely different. For example, the set health API can modify entities using custom health values (including but not limited to entity data, numeric fields, and hash tables); the remove API performs low-level Minecraft container cleanup; the set invulnerable API provides a more powerful implementation than vanilla creative mode invulnerability. Additionally, this mod unlocks vanilla attribute limits to Double.MAX_VALUE by default. You can disable this in the config file with "Unlock Attribute Limits" option.

This mod also provides an [MCreator plugin](https://mcreator.net/plugin/121284/20244epic-core-api-plugin) for MCreator users to conveniently use the APIs in this mod.

## Usage for Players

Use `/eca` command (requires OP permission level 2):
- `/eca setHealth <targets> <health>` - Set entity health
- `/eca setMaxHealth <targets> <maxHealth>` - Set entity max health (reverse-calculates attribute base value)
- `/eca setInvulnerable <targets> <true|false>` - Set entity invulnerability
 - `/eca lockHealth <targets> true <value>` - Lock entity health at specific value
 - `/eca lockHealth <targets> false` - Unlock entity health
 - `/eca lockMaxHealth <targets> true <value>` - Lock entity max health at specific value
 - `/eca lockMaxHealth <targets> false` - Unlock entity max health
 - `/eca banHealing <targets> true [value]` - Ban healing for entities (value optional, defaults to current health)
 - `/eca banHealing <targets> false` - Unban healing for entities
 - `/eca kill <targets>` - Kill entities
- `/eca remove <targets> [reason]` - Remove entities from world
- `/eca memoryRemove <targets>` - DANGER! Requires Attack Radical Logic config. Remove entities via LWJGL internal channel
- `/eca teleport <targets> <x> <y> <z>` - Teleport entities
- `/eca lockLocation <targets> <true|false> [x y z]` - Lock/unlock entity location
- `/eca cleanBossBar <targets>` - Clean up boss bars
- `/eca allReturn <targets> <true|false>` - DANGER! Requires Attack Radical Logic config. Enable/disable return transformation on all boolean and void methods of the target entity's mod
- `/eca allReturn global <true|false>` - DANGER! Enable/disable global AllReturn for all non-whitelisted mods
- `/eca banSpawn <targets> <seconds>` - Ban spawning of selected entities' types for specified duration
- `/eca banSpawn clear` - Unban all spawns in current dimension
- `/eca setForceLoading <targets> <true|false>` - Enable/disable force chunk loading for entities
- `/eca setInvulnerable show_all` - Show all invulnerable entities
- `/eca entityExtension get_registry` - Show entity extension registry
- `/eca entityExtension get_active` - Show active entity extension types in current dimension
- `/eca entityExtension get_current` - Show the currently effective entity extension
- `/eca entityExtension clear` - Clear active entity extension table and all global effects in current dimension
- `/eca entityExtension set_skybox <preset>` - Set global skybox shader preset

Added new command selectors implemented by the ECA selector system:
- `@eca_e[...]` - all entities
- `@eca_p[...]` - nearest player
- `@eca_a[...]` - all players
- `@eca_r[...]` - random player
- `@eca_s[...]` - command source entity (self)

## Usage for Developers

### Adding ECA as Dependency

**Step 1: Add Modrinth Maven repository** (build.gradle)
```groovy
repositories {
    maven { url = "https://api.modrinth.com/maven"; content { includeGroup "maven.modrinth" } }
}
```

**Step 2: Add ECA dependency** (build.gradle)
```groovy
dependencies {
    implementation fg.deobf("maven.modrinth:epic-core-api:VERSION")
}
```
> Replace `VERSION` with the version you need (e.g. `1.1.2-fix-dev`). Go to [ECA Modrinth page](https://modrinth.com/mod/epic-core-api) to find available versions. Use the `-dev` version for development environments to avoid mixin remapping issues.

**Step 3: Declare dependency** (mods.toml)
```toml
[[dependencies.your_mod_id]]
modId="eca"
mandatory=true
versionRange="[1.1.2,)"
ordering="NONE"
side="BOTH"
```

### API Reference

- `lockHealth(entity, value)` - Lock entity health at specific value (for invincibility, heal negation, etc.)
- `unlockHealth(entity)` - Remove health lock
- `getLockedHealth(entity)` - Get current health lock value (null if not locked)
- `isHealthLocked(entity)` - Check if entity health is locked
- `banHealing(entity, value)` - Ban healing for entity at specified value (entity cannot heal but can take damage)
- `unbanHealing(entity)` - Unban healing for entity
- `getHealBanValue(entity)` - Get current heal ban value (null if not banned)
- `isHealingBanned(entity)` - Check if entity has healing banned
- `getHealth(entity)` - Get vanilla health via VarHandle
- `setHealth(entity, health)` - Multi-phase health modification (vanilla + custom EntityData + bytecode-analyzed real health)
- `setMaxHealth(entity, maxHealth)` - Set max health by reverse-calculating attribute base value from current modifiers
- `lockMaxHealth(entity, value)` - Lock entity max health at specific value (enforced every tick)
- `unlockMaxHealth(entity)` - Unlock entity max health
- `getLockedMaxHealth(entity)` - Get current max health lock value (null if not locked)
- `isMaxHealthLocked(entity)` - Check if entity max health is locked
- `addHealthWhitelistKeyword(keyword)` - Add keyword to health modification whitelist
- `removeHealthWhitelistKeyword(keyword)` - Remove keyword from health modification whitelist
- `getHealthWhitelistKeywords()` - Get all health whitelist keywords
- `addHealthBlacklistKeyword(keyword)` - Add keyword to health modification blacklist
- `removeHealthBlacklistKeyword(keyword)` - Remove keyword from health modification blacklist
- `getHealthBlacklistKeywords()` - Get all health blacklist keywords
- `kill(entity, damageSource)` - Kill entity (loot + advancements + removal)
- `revive(entity)` - Clear death state and restore health
- `revive(level, uuid)` - Clear death state and restore health by UUID in specified level
- `reviveAllContainers(entity)` - Revive all critical entity containers (tickList, lookup, sections, tracker)
- `reviveAllContainers(level, uuid)` - Revive all critical entity containers by UUID in specified level
- `teleport(entity, x, y, z)` - Teleport via VarHandle with client sync
- `lockLocation(entity)` - Lock entity location at current position
- `lockLocation(entity, position)` - Lock entity location at specified position
- `unlockLocation(entity)` - Unlock entity location
- `isLocationLocked(entity)` - Check if entity location is locked
- `getLockedLocation(entity)` - Get locked position (null if not locked)
- `remove(entity, reason)` - Complete removal (AI, boss bars, containers, passengers)
- `memoryRemove(entity, reason)` - DANGER! Requires Attack Radical Logic config. Remove entity via LWJGL internal channel
- `cleanupBossBar(entity)` - Remove boss bars without removing entity
- `isInvulnerable(entity)` - Check if entity is invulnerable (ECA internal invulnerability logic)
- `setInvulnerable(entity, invulnerable)` - Set invulnerability (enable: revive + lock health + block damage + remove harmful effects per tick + prevent mob targeting + protect player inventory; disable: clear all protections)
- `enableAllReturn(entity)` - DANGER! Requires Attack Radical Logic config. Performs return transformation on all boolean and void methods of the target entity's mod
- `setGlobalAllReturn(enable)` - DANGER! Requires Attack Radical Logic config. Enable/disable global AllReturn for all non-whitelisted mods
- `disableAllReturn()` - Disable AllReturn and clear targets
- `isAllReturnEnabled()` - Check if AllReturn is enabled
- `addProtectedPackage(prefix)` - Add custom package prefix to whitelist, protecting it from AllReturn (e.g., "com.yourmod.")
- `removeProtectedPackage(prefix)` - Remove custom package prefix from whitelist (built-in protections cannot be removed)
- `isPackageProtected(className)` - Check if a class is protected by the whitelist
- `getAllProtectedPackages()` - Get all protected package prefixes (built-in + custom)
- `getEntityExtensionRegistry()` - Get all registered entity extensions (Map<EntityType, EntityExtension>)
- `getActiveEntityExtensionTypes(level)` - Get active entity extension types in current dimension (Map<EntityType, Integer>)
- `getActiveEntityExtension(level)` - Get the currently effective entity extension (highest priority)
- `clearActiveEntityExtensionTable(level)` - Clear active entity extension table in current dimension
- `setGlobalFog(level, fogData)` - Set global fog effect override for a dimension (does not change effect priority)
- `clearGlobalFog(level)` - Clear global fog effect override
- `setGlobalSkybox(level, skyboxData)` - Set global skybox effect override for a dimension (does not change effect priority)
- `clearGlobalSkybox(level)` - Clear global skybox effect override
- `setGlobalMusic(level, musicData)` - Set global combat music effect override for a dimension (does not change effect priority)
- `clearGlobalMusic(level)` - Clear global combat music effect override
- `clearAllGlobalEffects(level)` - Clear all global effect overrides (fog, skybox, music) for a dimension
- `banSpawn(level, entityType, seconds)` - Ban entity type from spawning for specified duration
- `isSpawnBanned(level, entityType)` - Check if entity type is banned from spawning
- `getSpawnBanTime(level, entityType)` - Get remaining spawn ban time in seconds
- `unbanSpawn(level, entityType)` - Unban entity type, allowing it to spawn again
- `getAllSpawnBans(level)` - Get all spawn bans in level (Map<EntityType, Integer>)
- `unbanAllSpawns(level)` - Unban all entity types in level
- `setForceLoading(entity, level, forceLoad)` - Enable/disable force chunk loading for entity
- `isForceLoaded(entity)` - Check if entity is force loaded (via EntityExtension or API)
- `getEntity(level, entityId)` - Resolve entity by runtime id in specified level (ECA selector path)
- `getEntity(level, uuid)` - Resolve entity by UUID in specified level (ECA selector path)
- `getEntity(level, entityId, entityClass)` - Resolve typed entity by id
- `getEntity(level, uuid, entityClass)` - Resolve typed entity by UUID
- `getEntity(server, entityId)` - Resolve entity by id across all levels
- `getEntity(server, uuid)` - Resolve entity by UUID across all levels
- `getEntities(level)` - Get all entities in level
- `getEntities(level, area)` - Get entities in AABB area
- `getEntities(level, filter)` - Get entities using custom predicate
- `getEntities(level, area, filter)` - Get entities in area using custom predicate
- `getEntities(level, entityClass)` - Get all entities of specified type in level
- `getEntities(level, area, entityClass)` - Get entities of specified type in area
- `getEntities(server)` - Get all entities across all server levels
- `getEntities(server, filter)` - Get entities across all levels using custom predicate

```java
import net.eca.api.EcaAPI;
import net.eca.network.EntityExtensionOverridePacket.FogData;
import net.eca.network.EntityExtensionOverridePacket.MusicData;
import net.eca.network.EntityExtensionOverridePacket.SkyboxData;
import net.eca.util.entity_extension.EntityExtension;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;

// Health Lock
EcaAPI.lockHealth(entity, 20.0f);
Float locked = EcaAPI.getLockedHealth(entity);
EcaAPI.unlockHealth(entity);

// Heal Ban
EcaAPI.banHealing(entity, entity.getHealth());  // Ban healing at current health
Float banValue = EcaAPI.getHealBanValue(entity);
EcaAPI.unbanHealing(entity);

// Basic Health Access
float realHealth = EcaAPI.getHealth(entity);
EcaAPI.setHealth(entity, 50.0f);

// Max Health
EcaAPI.setMaxHealth(entity, 1024.0f);
EcaAPI.lockMaxHealth(entity, 1024.0f);
Float lockedMax = EcaAPI.getLockedMaxHealth(entity);
EcaAPI.unlockMaxHealth(entity);

// Keyword Management
EcaAPI.addHealthWhitelistKeyword("mana");
EcaAPI.addHealthBlacklistKeyword("timer");
Set<String> whitelist = EcaAPI.getHealthWhitelistKeywords();
Set<String> blacklist = EcaAPI.getHealthBlacklistKeywords();
EcaAPI.removeHealthWhitelistKeyword("mana");
EcaAPI.removeHealthBlacklistKeyword("timer");

// Entity Control
EcaAPI.kill(entity, damageSource);
EcaAPI.revive(entity);
EcaAPI.revive(serverLevel, uuid);  // Revive by UUID
Map<String, Boolean> containerResults = EcaAPI.reviveAllContainers(entity);  // Revive all containers
EcaAPI.reviveAllContainers(serverLevel, uuid);  // Revive all containers by UUID
EcaAPI.teleport(entity, x, y, z);
EcaAPI.lockLocation(entity);  // Lock at current position
EcaAPI.lockLocation(entity, new Vec3(100, 64, 200));  // Lock at specified position
boolean locationLocked = EcaAPI.isLocationLocked(entity);
Vec3 lockedPos = EcaAPI.getLockedLocation(entity);
EcaAPI.unlockLocation(entity);
EcaAPI.remove(entity, Entity.RemovalReason.KILLED);
EcaAPI.memoryRemove(entity, Entity.RemovalReason.CHANGED_DIMENSION);  // Remove using LWJGL internal Unsafe instance
EcaAPI.cleanupBossBar(entity);

// ECA Entity Selector API
Entity byId = EcaAPI.getEntity(level, 123);
Entity byUuid = EcaAPI.getEntity(level, uuid);
List<Entity> allInLevel = EcaAPI.getEntities(level);
List<Entity> inArea = EcaAPI.getEntities(level, new AABB(0, 0, 0, 16, 256, 16));
List<Entity> filtered = EcaAPI.getEntities(level, e -> e.getType() == EntityType.ZOMBIE);
List<LivingEntity> livingInArea = EcaAPI.getEntities(level, new AABB(0, 0, 0, 32, 256, 32), LivingEntity.class);
List<Entity> allServerEntities = EcaAPI.getEntities(server);

// Invulnerability
EcaAPI.setInvulnerable(entity, true);
boolean isInv = EcaAPI.isInvulnerable(entity);
EcaAPI.setInvulnerable(entity, false);

// AllReturn (DANGER! Requires Attack Radical Logic config)
EcaAPI.enableAllReturn(entity);  // Enable for entity's mod
EcaAPI.setGlobalAllReturn(true);  // Enable for ALL non-whitelisted mods
boolean enabled = EcaAPI.isAllReturnEnabled();
EcaAPI.disableAllReturn();  // Disable and clear all AllReturn

// Package Whitelist
EcaAPI.addProtectedPackage("com.yourmod.");
boolean removed = EcaAPI.removeProtectedPackage("com.yourmod.");  // Note: built-in hardcoded entries cannot be removed
boolean isProtected = EcaAPI.isPackageProtected("com.yourmod.YourClass");
Set<String> allProtected = EcaAPI.getAllProtectedPackages();  // Get all protected packages

// Spawn Ban
EcaAPI.banSpawn(serverLevel, EntityType.ZOMBIE, 300);  // Ban zombies for 5 minutes
boolean banned = EcaAPI.isSpawnBanned(serverLevel, EntityType.ZOMBIE);
int remaining = EcaAPI.getSpawnBanTime(serverLevel, EntityType.ZOMBIE);
EcaAPI.unbanSpawn(serverLevel, EntityType.ZOMBIE);
Map<EntityType<?>, Integer> allBans = EcaAPI.getAllSpawnBans(serverLevel);
EcaAPI.unbanAllSpawns(serverLevel);

// Force Loading
EcaAPI.setForceLoading(livingEntity, serverLevel, true);
boolean forceLoaded = EcaAPI.isForceLoaded(livingEntity);
EcaAPI.setForceLoading(livingEntity, serverLevel, false);

// Entity Extension
Map<EntityType<?>, EntityExtension> registry = EcaAPI.getEntityExtensionRegistry();
Map<EntityType<?>, Integer> activeTypes = EcaAPI.getActiveEntityExtensionTypes(serverLevel);
EntityExtension active = EcaAPI.getActiveEntityExtension(serverLevel);
EcaAPI.clearActiveEntityExtensionTable(serverLevel);

// Global Effect Override (directly override fog/skybox/music without entity extension, does not change effect priority)
EcaAPI.setGlobalFog(serverLevel, new FogData(true, 8.0f, 0.0f, 0.0f, 0.0f, 0.02f, 0.25f, 0.0f, 1.0f, 0));
EcaAPI.clearGlobalFog(serverLevel);
EcaAPI.setGlobalSkybox(serverLevel, new SkyboxData(false, null, true, new ResourceLocation("eca", "the_last_end"), 1.0f, 100.0f, 1.0f, 1.0f, 1.0f, 1.0f));
EcaAPI.clearGlobalSkybox(serverLevel);
EcaAPI.setGlobalMusic(serverLevel, new MusicData(new ResourceLocation("your_mod", "music.boss"), 0, 1.0f, 1.0f, true, true));
EcaAPI.clearGlobalMusic(serverLevel);
EcaAPI.clearAllGlobalEffects(serverLevel);
```

### Entity Extension System

This mod also provides a customizable entity type extension system for adding special effects to your entities. You need to create a subclass extending `EntityExtension` and annotate it with `@RegisterEntityExtension` to register the extension.

Quick start example:

```java
@RegisterEntityExtension
public class MyBossExtension extends EntityExtension {

    static {
        EntityExtensionManager.register(new MyBossExtension());
    }

    public MyBossExtension() {
        super(EntityType.WITHER, 8);  // entity type + priority (in a dimension, some global extension effects like fog, skybox, combat music only apply to the entity extension with the highest priority among existing entities)
    }

    @Override
    public boolean enableForceLoading() {
        return true;  // mark this entity type as force-loaded, avoid using on entities that spawn in large numbers to prevent lag
    }

    @Override
    protected String getModId() {
        return "your_mod_id";  // your mod id, used for all resource path resolution (textures, sounds, etc.)
    }

    @Override
    public boolean shouldShowBossBar(LivingEntity entity) {
        return entity != null && entity.isAlive();  // boss bar display condition
    }

    @Override
    public boolean enableCustomHealthOverride() {
        return true;  // if true, ECA custom boss bar current health will be read from getCustomHealthValue() instead of vanilla getHealth()
    }

    @Override
    public Number getCustomHealthValue(LivingEntity entity) {
        return entity.getEntityData().get(YOUR_CUSTOM_HEALTH_DATA);  // the actual value to use as current health (e.g. entity data, custom field), null = fallback to vanilla
    }

    @Override
    public boolean enableCustomMaxHealthOverride() {
        return true;  // if true, ECA custom boss bar max health will be read from getCustomMaxHealthValue() instead of vanilla getMaxHealth()
    }

    @Override
    public Number getCustomMaxHealthValue(LivingEntity entity) {
        return entity.getEntityData().get(YOUR_CUSTOM_MAX_HEALTH_DATA);  // the actual value to use as max health (e.g. entity data, custom field), null = fallback to vanilla
    }

    // Custom boss health bar
    @Override
    public BossBarExtension bossBarExtension() {
        return new BossBarExtension() {
            @Override public boolean enabled() { return true; }  // enable boss bar
            @Override public ResourceLocation getFrameTexture() { return texture("boss/frame.png"); }  // frame texture (null to skip). If both texture and RenderType are set, shader renders masked by texture alpha
            @Override public ResourceLocation getFillTexture() { return texture("boss/fill.png"); }  // fill texture (null to skip)
            @Override public RenderType getFrameRenderType() { return CustomRenderTypes.BOSS_BAR; }  // frame shader/render type (null to skip)
            @Override public RenderType getFillRenderType() { return CustomRenderTypes.BOSS_BAR; }  // fill shader/render type (null to skip), can use a different preset
            @Override public int getFrameWidth() { return 420; }  // frame pixel width (RenderType-only mode requires this, texture mode auto-detects)
            @Override public int getFrameHeight() { return 40; }  // frame pixel height
            @Override public int getFillWidth() { return 400; }  // fill pixel width (RenderType-only mode requires this, texture mode auto-detects)
            @Override public int getFillHeight() { return 30; }  // fill pixel height
            @Override public int getFrameOffsetX() { return 0; }  // frame X offset
            @Override public int getFrameOffsetY() { return -10; }  // frame Y offset
            @Override public int getFillOffsetX() { return 0; }  // fill X offset
            @Override public int getFillOffsetY() { return 0; }  // fill Y offset
        };
    }

    // Entity extra render layer
    @Override
    public EntityLayerExtension entityLayerExtension() {
        return new EntityLayerExtension() {
            @Override public boolean enabled() { return true; }  // enable render layer
            @Override public RenderType getRenderType() { return CustomRenderTypes.BOSS_LAYER; }  // render layer shader/render type
            @Override public boolean isGlow() { return true; }  // extra render layer glowing
            @Override public boolean isHurtOverlay() { return true; }  // show hurt overlay effect on this layer
            @Override public float getAlpha() { return 0.8f; }  // render layer transparency (0.0 ~ 1.0)
        };
    }

    // Global fog
    @Override
    public GlobalFogExtension globalFogExtension() {
        return new GlobalFogExtension() {
            @Override public boolean enabled() { return true; }  // enable fog
            @Override public boolean globalMode() { return true; }  // global mode (ignore radius, always active in dimension)
            @Override public float radius() { return 8.0f; }  // fog activation radius around entity
            @Override public int fogColor() { return 0x000000; }  // fog color as packed RGB int (e.g. 0xFF0000 = red, 0x800080 = purple, 0x000000 = black). Override fogRed/Green/Blue() instead to mix your own color.
            @Override public float terrainFogStart(float renderDistance) { return renderDistance * 0.02f; }  // terrain fog start distance
            @Override public float terrainFogEnd(float renderDistance) { return renderDistance * 0.25f; }  // terrain fog end distance
            @Override public float skyFogStart(float renderDistance) { return 0.0f; }  // sky fog start distance
            @Override public float skyFogEnd(float renderDistance) { return renderDistance; }  // sky fog end distance
            @Override public FogShape fogShape() { return FogShape.SPHERE; }  // fog shape (SPHERE or CYLINDER)
        };
    }

    // Global custom skybox
    @Override
    public GlobalSkyboxExtension globalSkyboxExtension() {
        return new GlobalSkyboxExtension() {
            @Override public boolean enabled() { return true; }  // enable skybox
            @Override public boolean enableTexture() { return true; }  // enable texture-based skybox rendering
            @Override public ResourceLocation texture() { return texture("sky/skybox.png"); }  // skybox texture resource location
            @Override public boolean enableShader() { return true; }  // enable shader-based skybox rendering
            @Override public RenderType shaderRenderType() { return CustomRenderTypes.SKYBOX; }  // skybox shader/render type
            @Override public float alpha() { return 0.9f; }  // skybox transparency (0.0 ~ 1.0)
            @Override public float size() { return 100.0f; }  // skybox quad size
            @Override public float textureUvScale() { return 16.0f; }  // texture UV scale
            @Override public float textureRed() { return 1.0f; }  // texture color red (0.0 ~ 1.0)
            @Override public float textureGreen() { return 1.0f; }  // texture color green (0.0 ~ 1.0)
            @Override public float textureBlue() { return 1.0f; }  // texture color blue (0.0 ~ 1.0)
        };
    }

    // Global combat music
    @Override
    public CombatMusicExtension combatMusicExtension() {
        return new CombatMusicExtension() {
            @Override public boolean enabled() { return true; }  // enable combat music
            @Override public ResourceLocation soundEventId() { return sound("music.boss_battle"); }  // sound event id (must be registered in sounds.json)
            @Override public SoundSource soundSource() { return SoundSource.MUSIC; }  // sound category
            @Override public float volume() { return 1.0f; }  // playback volume (0.0 ~ 1.0)
            @Override public float pitch() { return 1.0f; }  // playback pitch
            @Override public boolean loop() { return true; }  // loop playback
            @Override public boolean strictMusicLock() { return true; }  // block all other MUSIC sounds while active
        };
    }
}
```

### Shader Presets

This mod also provides some built-in shader presets for the entity extension system. You can directly use these RenderTypes in your extension — simply replace `CustomRenderTypes` in the example above with the preset name. Each preset provides 3 RenderTypes: BOSS_BAR, BOSS_LAYER, SKYBOX.

Available presets:
- `TheLastEndRenderTypes` — The Last End
- `DreamSakuraRenderTypes` — Dream Sakura
- `ForestRenderTypes` — Forest
- `OceanRenderTypes` — Ocean
- `StormRenderTypes` — Storm
- `VolcanoRenderTypes` — Volcano
- `ArcaneRenderTypes` — Arcane
- `AuroraRenderTypes` — Aurora
- `HackerRenderTypes` — Hacker
- `StarlightRenderTypes` — Starlight
- `CosmosRenderTypes` — Cosmos
- `BlackHoleRenderTypes` — Black Hole

---

# 中文

本 Mod 提供了一些基于 CoreMod (ITransformationService)、Java Agent 和 VarHandle 等技术所实现的实体操作 API 和相关命令。注意，虽然在方法命名上本 Mod 提供的各种逻辑和原版相似，但是本质上的实现完全不同。例如，设置生命值 API 可以修改部分使用自定义生命值（包括但不限于实体数据、数字类型字段、部分哈希表）的实体；清除 API 则是进行了 Minecraft 底层容器的相关清除；设置无敌 API 则是提供了比原版创造模式无敌更强大的实现。此外，本 Mod 还将原版属性上限解锁至 Double.MAX_VALUE。如不需要，可在配置文件 "Unlock Attribute Limits" 中关闭。

本 Mod 还提供了一个 [MCreator 插件](https://mcreator.net/plugin/121284/20244epic-core-api-plugin)来方便 MCreator 用户使用本 Mod 中的 API。

## 玩家使用

使用 `/eca` 命令（需要 OP 权限等级 2）：
- `/eca setHealth <目标> <血量值>` - 设置实体血量值
- `/eca setMaxHealth <目标> <最大血量值>` - 设置实体最大生命值（反算属性基础值）
- `/eca setInvulnerable <目标> <true|false>` - 设置实体无敌状态
 - `/eca lockHealth <目标> true <血量值>` - 锁定实体血量
 - `/eca lockHealth <目标> false` - 解锁实体血量
 - `/eca lockMaxHealth <目标> true <值>` - 锁定实体最大生命值
 - `/eca lockMaxHealth <目标> false` - 解锁实体最大生命值
 - `/eca banHealing <目标> true [血量值]` - 禁止实体治疗（血量值可选，默认使用当前血量）
 - `/eca banHealing <目标> false` - 解除禁疗
 - `/eca kill <目标>` - 击杀实体
- `/eca remove <目标> [原因]` - 从世界中移除实体
- `/eca memoryRemove <目标>` - 危险！需要开启激进攻击逻辑配置，通过LWJGL内部通道清除实体
- `/eca teleport <目标> <x> <y> <z>` - 传送实体
- `/eca lockLocation <目标> <true|false> [x y z]` - 锁定/解除实体位置
- `/eca cleanBossBar <目标>` - 清理 Boss 血条
- `/eca allReturn <目标> <true|false>` - 危险！需要开启激进攻击逻辑配置，启用/禁用对目标实体的所属mod的全部布尔和void方法的return transformation
- `/eca allReturn global <true|false>` - 危险！启用/禁用全局AllReturn，影响所有非白名单mod
- `/eca banSpawn <目标> <秒数>` - 禁止选中实体的类型生成指定时长
- `/eca banSpawn clear` - 解除当前维度所有禁生成
- `/eca setForceLoading <目标> <true|false>` - 启用/禁用实体强加载
- `/eca setInvulnerable show_all` - 显示所有无敌实体
- `/eca entityExtension get_registry` - 查看实体扩展注册表
- `/eca entityExtension get_active` - 查看当前维度活跃的扩展类型
- `/eca entityExtension get_current` - 查看当前生效的实体扩展
- `/eca entityExtension clear` - 清空当前维度活跃扩展表和所有全局效果覆盖
- `/eca entityExtension set_skybox <预设名>` - 设置全局天空盒着色器预设

新增了由 ECA 选择器实现的命令选择器：
- `@eca_e[...]` - 所有实体
- `@eca_p[...]` - 最近玩家
- `@eca_a[...]` - 所有玩家
- `@eca_r[...]` - 随机玩家
- `@eca_s[...]` - 命令执行源实体（自身）

## 开发者使用

### 添加 ECA 依赖

**第一步：添加 Modrinth Maven 仓库** (build.gradle)
```groovy
repositories {
    maven { url = "https://api.modrinth.com/maven"; content { includeGroup "maven.modrinth" } }
}
```

**第二步：添加 ECA 依赖** (build.gradle)
```groovy
dependencies {
    implementation fg.deobf("maven.modrinth:epic-core-api:VERSION")
}
```
> 将 `VERSION` 替换为所需版本（如 `1.1.2-fix-dev`）。前往 [ECA Modrinth 页面](https://modrinth.com/mod/epic-core-api) 查看可用版本。开发环境请使用 `-dev` 版本以避免 mixin 重映射问题。

**第三步：声明依赖** (mods.toml)
```toml
[[dependencies.你的modId]]
modId="eca"
mandatory=true
versionRange="[1.1.2,)"
ordering="NONE"
side="BOTH"
```

### API 参考

- `lockHealth(entity, value)` - 锁定实体血量值（用于无敌阶段、治疗等）
- `unlockHealth(entity)` - 解除血量锁定
- `getLockedHealth(entity)` - 获取当前锁定值（未锁定返回 null）
- `isHealthLocked(entity)` - 检查是否锁定
- `banHealing(entity, value)` - 禁止实体治疗（可受伤害，但不能治疗）
- `unbanHealing(entity)` - 解除禁疗
- `getHealBanValue(entity)` - 获取当前禁疗值（未禁疗返回 null）
- `isHealingBanned(entity)` - 检查是否被禁疗
- `getHealth(entity)` - 使用 VarHandle 获取原版血量值
- `setHealth(entity, health)` - 多阶段血量值修改（原版 + 自定义 EntityData + 真实值回溯）
- `setMaxHealth(entity, maxHealth)` - 通过反算属性基础值设置最大生命值
- `lockMaxHealth(entity, value)` - 锁定实体最大生命值（每 tick 强制维持）
- `unlockMaxHealth(entity)` - 解锁最大生命值
- `getLockedMaxHealth(entity)` - 获取最大生命值锁定值（未锁定返回 null）
- `isMaxHealthLocked(entity)` - 检查最大生命值是否被锁定
- `addHealthWhitelistKeyword(keyword)` - 添加血量值修改白名单关键词
- `removeHealthWhitelistKeyword(keyword)` - 移除血量值修改白名单关键词
- `getHealthWhitelistKeywords()` - 获取全部白名单关键词
- `addHealthBlacklistKeyword(keyword)` - 添加血量值修改黑名单关键词
- `removeHealthBlacklistKeyword(keyword)` - 移除血量值修改黑名单关键词
- `getHealthBlacklistKeywords()` - 获取全部黑名单关键词
- `kill(entity, damageSource)` - 击杀实体（掉落 + 成就 + 移除）
- `revive(entity)` - 复活实体（清除死亡状态）
- `revive(level, uuid)` - 在指定维度按 UUID 复活实体
- `reviveAllContainers(entity)` - 复活实体的所有关键容器（tickList、lookup、sections、tracker）
- `reviveAllContainers(level, uuid)` - 在指定维度按 UUID 复活实体的所有关键容器
- `teleport(entity, x, y, z)` - VarHandle 传送并同步到客户端
- `lockLocation(entity)` - 锁定实体当前位置
- `lockLocation(entity, position)` - 锁定实体到指定位置
- `unlockLocation(entity)` - 解除实体位置锁定
- `isLocationLocked(entity)` - 检查实体位置是否锁定
- `getLockedLocation(entity)` - 获取锁定位置（未锁定返回 null）
- `remove(entity, reason)` - 完整移除（AI、Boss 血条、容器、乘客等）
- `memoryRemove(entity, reason)` - 危险！需要开启激进攻击逻辑配置，通过LWJGL内部通道清除实体
- `cleanupBossBar(entity)` - 仅移除 Boss 血条
- `isInvulnerable(entity)` - 检查 ECA 无敌状态
- `setInvulnerable(entity, invulnerable)` - 设置无敌状态（开启：复活、锁血、阻断伤害、每tick清除有害效果、阻止怪物锁定、保护玩家物品栏；关闭：清除所有保护）
- `enableAllReturn(entity)` - 危险！需要开启激进攻击逻辑配置，会尝试对目标实体的所属mod的全部布尔和void方法进行return transformation
- `setGlobalAllReturn(enable)` - 危险！需要开启激进攻击逻辑配置，启用/禁用全局AllReturn，影响所有非白名单mod
- `disableAllReturn()` - 关闭AllReturn并清除目标
- `isAllReturnEnabled()` - 检查AllReturn是否启用
- `addProtectedPackage(prefix)` - 添加受保护的包名前缀，使其免受 AllReturn 影响（如 "com.yourmod."）
- `removeProtectedPackage(prefix)` - 移除自定义包名保护（内置保护名单不能移除）
- `isPackageProtected(className)` - 检查类是否受白名单保护
- `getAllProtectedPackages()` - 获取所有受保护的包名前缀（内置保护名单 + 自定义）
- `getEntityExtensionRegistry()` - 获取所有已注册的实体扩展（Map<EntityType, EntityExtension>）
- `getActiveEntityExtensionTypes(level)` - 获取当前维度活跃的扩展类型（Map<EntityType, Integer>）
- `getActiveEntityExtension(level)` - 获取当前生效的实体扩展（最高优先级）
- `clearActiveEntityExtensionTable(level)` - 清空当前维度活跃扩展表
- `setGlobalFog(level, fogData)` - 设置维度全局雾气效果覆盖（不改变效果优先级）
- `clearGlobalFog(level)` - 清除全局雾气效果覆盖
- `setGlobalSkybox(level, skyboxData)` - 设置维度全局天空盒效果覆盖（不改变效果优先级）
- `clearGlobalSkybox(level)` - 清除全局天空盒效果覆盖
- `setGlobalMusic(level, musicData)` - 设置维度全局战斗音乐效果覆盖（不改变效果优先级）
- `clearGlobalMusic(level)` - 清除全局战斗音乐效果覆盖
- `clearAllGlobalEffects(level)` - 清除维度所有全局效果覆盖（雾气、天空盒、音乐）
- `banSpawn(level, entityType, seconds)` - 禁止指定实体类型生成指定时长
- `isSpawnBanned(level, entityType)` - 检查实体类型是否被禁生成
- `getSpawnBanTime(level, entityType)` - 获取禁生成剩余秒数
- `unbanSpawn(level, entityType)` - 解除指定实体类型的禁生成
- `getAllSpawnBans(level)` - 获取所有禁生成（Map<EntityType, Integer>）
- `unbanAllSpawns(level)` - 解除所有禁生成
- `setForceLoading(entity, level, forceLoad)` - 启用/禁用实体强加载
- `isForceLoaded(entity)` - 检查实体是否被强加载（包含 EntityExtension 和 API 两种来源）
- `getEntity(level, entityId)` - 在指定维度按运行时 id 获取实体（ECA 选择器路径）
- `getEntity(level, uuid)` - 在指定维度按 UUID 获取实体（ECA 选择器路径）
- `getEntity(level, entityId, entityClass)` - 按 id 获取指定类型实体
- `getEntity(level, uuid, entityClass)` - 按 UUID 获取指定类型实体
- `getEntity(server, entityId)` - 跨全部维度按 id 获取实体
- `getEntity(server, uuid)` - 跨全部维度按 UUID 获取实体
- `getEntities(level)` - 获取维度内全部实体
- `getEntities(level, area)` - 获取维度内 AABB 范围实体
- `getEntities(level, filter)` - 使用自定义条件获取维度实体
- `getEntities(level, area, filter)` - 使用自定义条件获取范围内实体
- `getEntities(level, entityClass)` - 获取维度内指定类型的全部实体
- `getEntities(level, area, entityClass)` - 获取范围内指定类型实体
- `getEntities(server)` - 获取全服全部实体
- `getEntities(server, filter)` - 使用自定义条件获取全服实体

```java
import net.eca.api.EcaAPI;
import net.eca.network.EntityExtensionOverridePacket.FogData;
import net.eca.network.EntityExtensionOverridePacket.MusicData;
import net.eca.network.EntityExtensionOverridePacket.SkyboxData;
import net.eca.util.entity_extension.EntityExtension;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;

// 血量锁定
EcaAPI.lockHealth(entity, 20.0f);
Float locked = EcaAPI.getLockedHealth(entity);
EcaAPI.unlockHealth(entity);

// 禁疗系统
EcaAPI.banHealing(entity, entity.getHealth());  // 在当前血量禁止治疗
Float banValue = EcaAPI.getHealBanValue(entity);
EcaAPI.unbanHealing(entity);

// 基础血量访问
float realHealth = EcaAPI.getHealth(entity);
EcaAPI.setHealth(entity, 50.0f);

// 最大生命值
EcaAPI.setMaxHealth(entity, 1024.0f);
EcaAPI.lockMaxHealth(entity, 1024.0f);
Float lockedMax = EcaAPI.getLockedMaxHealth(entity);
EcaAPI.unlockMaxHealth(entity);

// 关键词管理
EcaAPI.addHealthWhitelistKeyword("mana");
EcaAPI.addHealthBlacklistKeyword("timer");
Set<String> whitelist = EcaAPI.getHealthWhitelistKeywords();
Set<String> blacklist = EcaAPI.getHealthBlacklistKeywords();
EcaAPI.removeHealthWhitelistKeyword("mana");
EcaAPI.removeHealthBlacklistKeyword("timer");

// 实体控制
EcaAPI.kill(entity, damageSource);
EcaAPI.revive(entity);
EcaAPI.revive(serverLevel, uuid);  // 按 UUID 复活
Map<String, Boolean> containerResults = EcaAPI.reviveAllContainers(entity);  // 复活所有容器
EcaAPI.reviveAllContainers(serverLevel, uuid);  // 按 UUID 复活所有容器
EcaAPI.teleport(entity, x, y, z);
EcaAPI.lockLocation(entity);  // 锁定到当前位置
EcaAPI.lockLocation(entity, new Vec3(100, 64, 200));  // 锁定到指定位置
boolean locationLocked = EcaAPI.isLocationLocked(entity);
Vec3 lockedPos = EcaAPI.getLockedLocation(entity);
EcaAPI.unlockLocation(entity);
EcaAPI.remove(entity, Entity.RemovalReason.KILLED);
EcaAPI.memoryRemove(entity, Entity.RemovalReason.CHANGED_DIMENSION);  // 提供使用LWJGL内部Unsafe实例进行清除
EcaAPI.cleanupBossBar(entity);

// ECA 实体选择 API
Entity byId = EcaAPI.getEntity(level, 123);
Entity byUuid = EcaAPI.getEntity(level, uuid);
List<Entity> allInLevel = EcaAPI.getEntities(level);
List<Entity> inArea = EcaAPI.getEntities(level, new AABB(0, 0, 0, 16, 256, 16));
List<Entity> filtered = EcaAPI.getEntities(level, e -> e.getType() == EntityType.ZOMBIE);
List<LivingEntity> livingInArea = EcaAPI.getEntities(level, new AABB(0, 0, 0, 32, 256, 32), LivingEntity.class);
List<Entity> allServerEntities = EcaAPI.getEntities(server);

// 无敌状态
EcaAPI.setInvulnerable(entity, true);
boolean isInv = EcaAPI.isInvulnerable(entity);
EcaAPI.setInvulnerable(entity, false);

// AllReturn（危险！需开启激进攻击配置）
EcaAPI.enableAllReturn(entity);  // 对实体所属mod启用
EcaAPI.setGlobalAllReturn(true);  // 对所有非白名单mod启用
boolean enabled = EcaAPI.isAllReturnEnabled();
EcaAPI.disableAllReturn();  // 关闭并清除全部AllReturn

// 包名白名单
EcaAPI.addProtectedPackage("com.yourmod.");
boolean removed = EcaAPI.removeProtectedPackage("com.yourmod.");  // 注意内置硬编码名单无法移除
boolean isProtected = EcaAPI.isPackageProtected("com.yourmod.YourClass");
Set<String> allProtected = EcaAPI.getAllProtectedPackages();  // 获取所有受保护的包名

// 禁生成
EcaAPI.banSpawn(serverLevel, EntityType.ZOMBIE, 300);  // 禁止僵尸生成5分钟
boolean banned = EcaAPI.isSpawnBanned(serverLevel, EntityType.ZOMBIE);
int remaining = EcaAPI.getSpawnBanTime(serverLevel, EntityType.ZOMBIE);
EcaAPI.unbanSpawn(serverLevel, EntityType.ZOMBIE);
Map<EntityType<?>, Integer> allBans = EcaAPI.getAllSpawnBans(serverLevel);
EcaAPI.unbanAllSpawns(serverLevel);

// 强加载
EcaAPI.setForceLoading(livingEntity, serverLevel, true);
boolean forceLoaded = EcaAPI.isForceLoaded(livingEntity);
EcaAPI.setForceLoading(livingEntity, serverLevel, false);

// 实体扩展
Map<EntityType<?>, EntityExtension> registry = EcaAPI.getEntityExtensionRegistry();
Map<EntityType<?>, Integer> activeTypes = EcaAPI.getActiveEntityExtensionTypes(serverLevel);
EntityExtension active = EcaAPI.getActiveEntityExtension(serverLevel);
EcaAPI.clearActiveEntityExtensionTable(serverLevel);

// 全局效果覆盖（直接覆盖雾气/天空盒/音乐，无需实体扩展，不改变效果优先级）
EcaAPI.setGlobalFog(serverLevel, new FogData(true, 8.0f, 0.0f, 0.0f, 0.0f, 0.02f, 0.25f, 0.0f, 1.0f, 0));
EcaAPI.clearGlobalFog(serverLevel);
EcaAPI.setGlobalSkybox(serverLevel, new SkyboxData(false, null, true, new ResourceLocation("eca", "the_last_end"), 1.0f, 100.0f, 1.0f, 1.0f, 1.0f, 1.0f));
EcaAPI.clearGlobalSkybox(serverLevel);
EcaAPI.setGlobalMusic(serverLevel, new MusicData(new ResourceLocation("your_mod", "music.boss"), 0, 1.0f, 1.0f, true, true));
EcaAPI.clearGlobalMusic(serverLevel);
EcaAPI.clearAllGlobalEffects(serverLevel);
```

### 实体扩展

本 Mod 还提供了一个可自定义的实体类型扩展功能，用于为你的实体增加一些特殊的效果。你需要创建继承 `EntityExtension` 的子类，并在类开头使用 `@RegisterEntityExtension` 进行注册扩展。

快速上手示例：

```java
@RegisterEntityExtension
public class MyBossExtension extends EntityExtension {

    static {
        EntityExtensionManager.register(new MyBossExtension());
    }

    public MyBossExtension() {
        super(EntityType.WITHER, 8);  // 实体类型 + 优先级（一个维度中，一些全局扩展效果例如雾气、天空盒、战斗音乐只会对存在的实体中扩展优先级最高的实体扩展生效）
    }

    @Override
    public boolean enableForceLoading() {
        return true;  // 设置该类型实体为强加载实体，请勿用于会大量生成的实体避免卡顿
    }

    @Override
    protected String getModId() {
        return "your_mod_id";  // 你的 Mod ID，用于所有资源路径解析（纹理、音效等）
    }

    @Override
    public boolean shouldShowBossBar(LivingEntity entity) {
        return entity != null && entity.isAlive();  // Boss 血条显示条件
    }

    @Override
    public boolean enableCustomHealthOverride() {
        return true;  // 若为 true，ECA自定义血条当前血量将从 getCustomHealthValue() 读取，而非原版 getHealth()
    }

    @Override
    public Number getCustomHealthValue(LivingEntity entity) {
        return entity.getEntityData().get(YOUR_CUSTOM_HEALTH_DATA);  // 实际用作当前血量的值（如实体数据、自定义字段等），为 null 则回退到原版
    }

    @Override
    public boolean enableCustomMaxHealthOverride() {
        return true;  // 若为 true，ECA自定义血条最大血量将从 getCustomMaxHealthValue() 读取，而非原版 getMaxHealth()
    }

    @Override
    public Number getCustomMaxHealthValue(LivingEntity entity) {
        return entity.getEntityData().get(YOUR_CUSTOM_MAX_HEALTH_DATA);  // 实际用作最大血量的值（如实体数据、自定义字段等），为 null 则回退到原版
    }

    // 自定义 Boss 血条
    @Override
    public BossBarExtension bossBarExtension() {
        return new BossBarExtension() {
            @Override public boolean enabled() { return true; }  // 启用 Boss 血条
            @Override public ResourceLocation getFrameTexture() { return texture("boss/frame.png"); }  // 外框纹理（null 则跳过）。同时设置纹理和渲染类型时，着色器将以纹理 alpha 为遮罩渲染
            @Override public ResourceLocation getFillTexture() { return texture("boss/fill.png"); }  // 填充纹理（null 则跳过）
            @Override public RenderType getFrameRenderType() { return CustomRenderTypes.BOSS_BAR; }  // 外框着色器/渲染类型（null 则跳过）
            @Override public RenderType getFillRenderType() { return CustomRenderTypes.BOSS_BAR; }  // 填充着色器/渲染类型（null 则跳过），可使用不同预设
            @Override public int getFrameWidth() { return 420; }  // 外框像素宽度（仅渲染类型模式必须设置，纹理模式自动检测）
            @Override public int getFrameHeight() { return 40; }  // 外框像素高度
            @Override public int getFillWidth() { return 400; }  // 填充像素宽度（仅渲染类型模式必须设置，纹理模式自动检测）
            @Override public int getFillHeight() { return 30; }  // 填充像素高度
            @Override public int getFrameOffsetX() { return 0; }  // 外框 X 偏移
            @Override public int getFrameOffsetY() { return -10; }  // 外框 Y 偏移
            @Override public int getFillOffsetX() { return 0; }  // 填充 X 偏移
            @Override public int getFillOffsetY() { return 0; }  // 填充 Y 偏移
        };
    }

    // 实体额外渲染层
    @Override
    public EntityLayerExtension entityLayerExtension() {
        return new EntityLayerExtension() {
            @Override public boolean enabled() { return true; }  // 启用渲染层
            @Override public RenderType getRenderType() { return CustomRenderTypes.BOSS_LAYER; }  // 渲染层着色器/渲染类型
            @Override public boolean isGlow() { return true; }  // 额外渲染层发光
            @Override public boolean isHurtOverlay() { return true; }  // 在该层上显示受伤覆盖效果
            @Override public float getAlpha() { return 0.8f; }  // 渲染层透明度（0.0 ~ 1.0）
        };
    }

    // 全局雾气
    @Override
    public GlobalFogExtension globalFogExtension() {
        return new GlobalFogExtension() {
            @Override public boolean enabled() { return true; }  // 启用迷雾
            @Override public boolean globalMode() { return true; }  // 全局模式（忽略半径，在维度内始终生效）
            @Override public float radius() { return 8.0f; }  // 迷雾激活半径（围绕实体）
            @Override public int fogColor() { return 0x000000; }  // 迷雾颜色，十六进制 RGB（如 0xFF0000 = 红，0x800080 = 紫，0x000000 = 黑）。如需自行进行颜色调配，可 override fogRed/Green/Blue() 方法代替。
            @Override public float terrainFogStart(float renderDistance) { return renderDistance * 0.02f; }  // 地形迷雾起始距离
            @Override public float terrainFogEnd(float renderDistance) { return renderDistance * 0.25f; }  // 地形迷雾结束距离
            @Override public float skyFogStart(float renderDistance) { return 0.0f; }  // 天空迷雾起始距离
            @Override public float skyFogEnd(float renderDistance) { return renderDistance; }  // 天空迷雾结束距离
            @Override public FogShape fogShape() { return FogShape.SPHERE; }  // 迷雾形状（SPHERE 或 CYLINDER）
        };
    }

    // 全局自定义天空盒
    @Override
    public GlobalSkyboxExtension globalSkyboxExtension() {
        return new GlobalSkyboxExtension() {
            @Override public boolean enabled() { return true; }  // 启用天空盒
            @Override public boolean enableTexture() { return true; }  // 启用纹理天空盒渲染
            @Override public ResourceLocation texture() { return texture("sky/skybox.png"); }  // 天空盒纹理资源路径
            @Override public boolean enableShader() { return true; }  // 启用着色器天空盒渲染
            @Override public RenderType shaderRenderType() { return CustomRenderTypes.SKYBOX; }  // 天空盒着色器/渲染类型
            @Override public float alpha() { return 0.9f; }  // 天空盒透明度（0.0 ~ 1.0）
            @Override public float size() { return 100.0f; }  // 天空盒四边形大小
            @Override public float textureUvScale() { return 16.0f; }  // 纹理 UV 缩放
            @Override public float textureRed() { return 1.0f; }  // 纹理颜色 红（0.0 ~ 1.0）
            @Override public float textureGreen() { return 1.0f; }  // 纹理颜色 绿（0.0 ~ 1.0）
            @Override public float textureBlue() { return 1.0f; }  // 纹理颜色 蓝（0.0 ~ 1.0）
        };
    }

    // 全局战斗音乐
    @Override
    public CombatMusicExtension combatMusicExtension() {
        return new CombatMusicExtension() {
            @Override public boolean enabled() { return true; }  // 启用战斗音乐
            @Override public ResourceLocation soundEventId() { return sound("music.boss_battle"); }  // 音效事件 ID（需在 sounds.json 中注册）
            @Override public SoundSource soundSource() { return SoundSource.MUSIC; }  // 音效类别
            @Override public float volume() { return 1.0f; }  // 播放音量（0.0 ~ 1.0）
            @Override public float pitch() { return 1.0f; }  // 播放音调
            @Override public boolean loop() { return true; }  // 循环播放
            @Override public boolean strictMusicLock() { return true; }  // 激活时阻止其他所有 MUSIC 类音效播放
        };
    }
}
```

### 着色器预设

本 Mod 还提供了一些用于实体扩展系统的着色器预设，可以直接在扩展中使用相关的 RenderType。使用时将示例代码中的 `CustomRenderTypes` 替换为对应预设名字即可。每个预设均提供 3 个 RenderType：BOSS_BAR、BOSS_LAYER、SKYBOX。

可用预设：
- `TheLastEndRenderTypes` — 终焉
- `DreamSakuraRenderTypes` — 梦之樱
- `ForestRenderTypes` — 森林
- `OceanRenderTypes` — 海洋
- `StormRenderTypes` — 风暴
- `VolcanoRenderTypes` — 火山
- `ArcaneRenderTypes` — 奥术
- `AuroraRenderTypes` — 极光
- `HackerRenderTypes` — 黑客
- `StarlightRenderTypes` — 星辉
- `CosmosRenderTypes` — 宇宙
- `BlackHoleRenderTypes` — 黑洞

---

**Author / 作者**: CJiangqiu
