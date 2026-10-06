This mod provides entity manipulation APIs and commands based on CoreMod (ITransformationService), Java Agent, and Mixin technologies, plus a set of feature modules: BossShow, entity extensions, ECA Blender Model, block extensions, item extensions, screen filters, the ECA shader generator, custom factions, and custom raids. Note that while the entity-manipulation methods may share names with vanilla logic, the underlying implementation is completely different. For example, the set health API can modify entities using custom health values (including but not limited to entity data, numeric fields, and hash tables); the remove API performs low-level Minecraft container cleanup; the set invulnerable API provides a more powerful implementation than vanilla creative mode invulnerability. Additionally, this mod unlocks vanilla attribute limits to Double.MAX_VALUE by default. You can disable this in the config file with "Unlock Attribute Limits" option.

The original intent of this mod is to provide developers with simplified entity manipulation APIs while achieving a certain level of strength under the premise of ensuring performance and compatibility. Therefore, please do not use this mod for mod power comparisons or endless code arms races. Additionally, in modpack survival environments, it is best to ensure that the Attack and Defence Radical Logic config options are disabled.

This mod also provides an [MCreator plugin](https://mcreator.net/plugin/121284/20244epic-core-api-plugin) for MCreator users to conveniently use the APIs in this mod.

## Usage for Players

Players can use the following `/eca` commands (requires permission level ≥ 2):
- `/eca setHealth <targets> <health> [report]` - Set entity health. Add `report` to write one structured `<entity>_health_report_<timestamp>.txt` file per living target directly under `logs/`; the report identifies the observed storage pattern, channel attempts, successful mutation module, confidence, evidence, configuration gates, delayed next-tick verification, and the complete per-source dataflow inversion trace, including background analysis that belongs to the request.
- `/eca setMaxHealth <targets> <maxHealth>` - Set entity max health (reverse-calculates attribute base value)
- `/eca setInvulnerable <targets> <true|false>` - Set entity invulnerability
 - `/eca lockHealth <targets> true <value>` - Lock entity health at specific value
 - `/eca lockHealth <targets> false` - Unlock entity health
 - `/eca lockMaxHealth <targets> true <value>` - Lock entity max health at specific value
 - `/eca lockMaxHealth <targets> false` - Unlock entity max health
 - `/eca banHealing <targets> true [value]` - Prevent affected entities' health from exceeding the configured healing ban value (optional; defaults to current health).
 - `/eca banHealing <targets> false` - Unban healing for entities
 - `/eca hurt <targets> <amount>` - Force entity damage (vanilla hurt first, forced write when the health loss does not land; kill credit goes to the executor when run by a living entity)
 - `/eca kill <targets>` - Kill entities
- `/eca remove <targets> [reason]` - Remove entities from world
- `/eca memoryRemove <targets>` - DANGER! Requires Attack Radical Logic config. Remove entities using LWJGL's internal Unsafe instance
- `/eca teleport <targets> <x> <y> <z>` - Teleport entities to the specified coordinates in their current dimension.
- `/eca lockLocation <targets> <true|false> [x y z]` - Lock/unlock entity location
- `/eca cleanBossBar <targets>` - Clean up boss bars
- `/eca allReturn <targets> <true|false>` - DANGER! Requires Attack Radical Logic. Enable or disable AllReturn as requested; players target mods owning items in their four armor slots and two hand slots, while other entities target their own mod. Transform whitelists apply.
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
- `/eca setFilter <targets> true <type>` - Apply a screen filter to players (type: sketch, spotlight, matrix, rain, desert, snow, toxic, cosmos)
- `/eca setFilter <targets> false` - Remove all active filters from players
- `/eca bossShow edit` - Open the in-game cinematic editor (switches to spectator mode)
- `/eca bossShow exit` - Exit the editor and restore the previous game mode
- `/eca bossShow list` - List all loaded cutscenes
- `/eca bossShow play <viewer> <target> <id>` - Force-play a cutscene
- `/eca bossShow stop <viewer>` - Stop the viewer's current cutscene
- `/eca bossShow reload` - Reload all cutscene JSON definitions from disk
- `/eca bossShow clearHistory <player>` - Clear a player's "already seen" records
- `/eca shaderGenerator` - Open the in-game shader preset generator
- `/eca resurrection start` - Start the resurrection daemon thread
- `/eca resurrection stop` - Stop the resurrection daemon thread
- `/eca resurrection status` - Show daemon thread state and revival/check counts
- `/eca resurrection add <targets>` - Add entities to resurrection tracking (auto-revived on death every poll cycle)
- `/eca resurrection remove <targets>` - Remove entities from resurrection tracking
- `/eca resurrection list` - List all tracked entities with container integrity status
- `/eca resurrection check <target>` - One-shot container integrity check for an entity
- `/eca resurrection revive <target>` - Manually force-revive a tracked entity immediately
- `/eca resurrection interval <ms>` - Set poll interval in milliseconds (100–10000; default 25)
- `/eca faction create <id> <displayName> [color]` - Create a faction (color accepts a preset name such as red/gold/teal)
- `/eca faction remove <id>` - Remove a faction definition and drop every entity binding pointing at it
- `/eca faction join <factionId> [targets]` - Bind entities to a faction (defaults to the command source entity)
- `/eca faction leave [targets]` - Unbind entities from their current faction
- `/eca faction list` - List all registered factions
- `/eca faction info [factionId]` - Show a faction's color, members and relation overrides
- `/eca faction relation <factionA> <factionB> <relation>` - Set A's relation toward B. `hostile` / `neutral` / `friendly` store a relation override; `same_faction` instead merges B into A (B's members are rebound to A, relations are folded in, and B is deleted)
- `/eca faction leader <factionId>` - Show a faction's leader and whether it is currently loaded
- `/eca faction leader <factionId> set [target]` - Set the leader (defaults to the command source entity; joins the faction automatically)
- `/eca faction leader <factionId> clear` - Clear the leader; the former leader remains a member
- `/eca raid defs` - List all registered raid definitions
- `/eca raid list` - List active raids in the current dimension
- `/eca raid start <definitionId> [pos]` - Start a raid at a position inside its target structure (defaults to the command source position)
- `/eca raid startat <definitionId> <pos>` - Start a raid with an explicit center, skipping the structure lookup
- `/eca raid info <instanceId>` - Show details of one running raid
- `/eca raid end <instanceId> <victory|defeat>` - End a raid and clear every surviving raider

Added new command selectors, resolved through ECA's own entity lookup:
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
> Replace `VERSION` with the version you need (e.g. `1.1.7-fix-fix`). Go to [ECA Modrinth page](https://modrinth.com/mod/epic-core-api) to find available versions.

**Step 3: Declare dependency** (mods.toml)
```toml
[[dependencies.your_mod_id]]
modId="eca"
mandatory=true
versionRange="[1.1.5,)"
ordering="NONE"
side="BOTH"
```

### API Reference

- `lockHealth(entity, value)` - Lock entity health at specific value (for invincibility, heal negation, etc.)
- `unlockHealth(entity)` - Remove health lock
- `getLockedHealth(entity)` - Get current health lock value (null if not locked)
- `isHealthLocked(entity)` - Check if entity health is locked
- `banHealing(entity, value)` - Prevent the affected entity's health from exceeding the configured healing ban value.
- `unbanHealing(entity)` - Unban healing for entity
- `getHealBanValue(entity)` - Get current heal ban value (null if not banned)
- `isHealingBanned(entity)` - Check if entity has healing banned
- `getHealth(entity)` - If ECA's health modification analysis has identified the entity's real health, return that health; otherwise, return the entity's vanilla health data. This read does not initiate analysis or fall back to the entity's `getHealth()`; a null entity returns 0.0f, and unreadable storage returns NaN.
- `getRealHealth(entity)` - Uses the same read rules as `getHealth(entity)`, but returns NaN for a null entity.
- `setHealth(entity, health)` - Attempt a verified health change through vanilla direct write → dataflow reversal → external scan → method probe → numeric inversion; players use only the vanilla write. Dataflow analysis locates storage and inverts read expressions, including traceable reflection, Unsafe and VarHandle access. New probe evidence can revisit earlier channels. Channels are constrained by evidence, configuration and budgets, with verification after attempts and snapshots/rollback for probing writes. Immediate verification does not guarantee that entity logic will preserve the value on later ticks; delayed verification checks this separately. Basic dataflow reversal is available by default unless Force Compatibility Mode is enabled. Const Override, External Scan, Method Probe and Numeric Inversion require Attack Radical Logic and their respective switches under `Attack → setHealth`, which default to off.
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
- `hurt(entity, damageSource, amount)` - Damage an entity and guarantee the health loss lands. Vanilla `hurt` writes health only once, inside `actuallyHurt`, as `setHealth(getHealth() - damage)` — through the entity's own getter and setter, so an overridden or storage-decoupled entity runs the whole pipeline and fires all events while losing no health. This method clears the invulnerability cooldown and calls vanilla `hurt` first (mitigation, knockback, aggro and hurt animation all happen normally), then compares the health anchor against `before - amount` within `min(1.0, amount * 50%)`. On mismatch it restores the damage-source bookkeeping vanilla would have left (lastHurtByMob, lastHurtByPlayer/Time, lastDamageSource/Stamp, combat tracker, hurt animation) and forces the health through `setHealth`, clamped at zero. A lethal result is never forced into the death path here: the entity is left at zero health so vanilla `tickDeath` plays the death animation and removes it — use `kill` when an immediate kill is wanted. Entities under ECA's own health lock or invulnerability are left to those systems — vanilla `hurt` still runs, but no forced write is attempted.
- `hurt(entity, attacker, amount)` - Same pipeline with the damage source derived from the attacker: `playerAttack` for players, `mobAttack` for every other living entity, so kill credit and loot attribution behave as expected
- `kill(entity, damageSource)` - Kill entity (loot + advancements + removal)
- `revive(entity)` - Clear death state and restore health
- `revive(level, uuid)` - Clear death state and restore health by UUID in specified level
- `reviveAllContainers(entity)` - Revive all critical entity containers (tickList, lookup, sections, tracker)
- `reviveAllContainers(level, uuid)` - Revive all critical entity containers by UUID in specified level
- `teleport(entity, x, y, z)` - Force teleport an entity to the specified coordinates in its current dimension and synchronize clients. Must run on the server's main thread.
- `lockLocation(entity)` - Lock entity location at current position
- `lockLocation(entity, position)` - Lock entity location at specified position
- `unlockLocation(entity)` - Unlock entity location
- `isLocationLocked(entity)` - Check if entity location is locked
- `getLockedLocation(entity)` - Get locked position (null if not locked)
- `remove(entity, reason)` - Complete removal (AI, boss bars, containers, passengers)
- `memoryRemove(entity, reason)` - DANGER! Requires Attack Radical Logic config. Remove entity using LWJGL's internal Unsafe instance
- `cleanupBossBar(entity)` - Remove boss bars without removing entity
- `isInvulnerable(entity)` - Check if entity is invulnerable (ECA internal invulnerability logic)
- `setInvulnerable(entity, invulnerable)` - Set invulnerability (enable: revive + lock health + block damage + remove harmful effects per tick + prevent mob targeting + protect player inventory; disable: clear all protections)
- `enableAllReturn(entity)` - DANGER! Requires Attack Radical Logic. Enables AllReturn for the entity's mod; for players, targets the mods owning their armor and main-hand/off-hand equipment. Only eligible boolean and void methods are transformed, subject to transform whitelists.
- `disableAllReturn(entity)` - Disable AllReturn using the same target rules as enabling: armor and main-hand/off-hand equipment mods for players, or the entity's own mod for other entities.
- `setGlobalAllReturn(enable)` - DANGER! Requires Attack Radical Logic config. Enable/disable global AllReturn for all non-whitelisted mods
- `disableAllReturn()` - Disable AllReturn and clear targets
- `isAllReturnEnabled()` - Check if AllReturn is enabled
- `addAllReturnWhitelist(prefix)` - Add package prefix to AllReturn whitelist (skip AllReturn, defensive hooks still apply)
- `removeAllReturnWhitelist(prefix)` - Remove package prefix from AllReturn whitelist (built-in entries cannot be removed)
- `addTransformWhitelist(prefix)` - Add package prefix to transform whitelist (skip ALL ECA transformations including defensive hooks)
- `removeTransformWhitelist(prefix)` - Remove package prefix from transform whitelist (built-in entries cannot be removed)
- `isAllReturnWhitelisted(className)` - Check if a class is protected from AllReturn
- `isTransformWhitelisted(className)` - Check if a class is protected from all ECA transformations
- `getAllWhitelistedPackages()` - Get all whitelist prefixes (both levels, built-in + custom)
- `getEntityExtensionRegistry()` - Get all registered entity extensions (Map<EntityType, EntityExtension>)
- `getActiveEntityExtensionTypes(level)` - Get active entity extension types in current dimension (Map<EntityType, Integer>)
- `getActiveEntityExtension(level)` - Get the currently effective entity extension (highest priority)
- `clearActiveEntityExtensionTable(level)` - Clear active entity extension table in current dimension
- `playAnimation(entity, animation)` - Manually take control and start a named model action at speed 1 without looping (logical server thread)
- `playAnimation(entity, animation, speed, loop)` - Manually start/restart a named model action with speed and looping (logical server thread)
- `stopAnimation(entity)` - Stop managed playback, cancel an active skill if present, and release control to the controller/fallback
- `pauseAnimation(entity)` - Pause managed action/skill timing, without freezing lifecycle decisions
- `resumeAnimation(entity)` - Resume managed action/skill timing from its preserved position
- `isAnimationPlaying(entity[, animation])` - Query managed playback including paused/held poses, optionally matching the action name
- `triggerBlenderSkill(entity, skillId)` - Start a configured skill through its interruption policy
- `cancelBlenderSkill(entity)` - Cancel the active skill without a completion callback
- `isBlenderSkillActive(entity)` - Query active skill execution, including pauses
- `getBlenderSkillExecution(entity)` - Get the current execution snapshot, or null
- `setGlobalFog(level, fogData)` - Set global fog effect override for a dimension (does not change effect priority)
- `clearGlobalFog(level)` - Clear global fog effect override
- `setGlobalSkybox(level, skyboxData)` - Set global skybox effect override for a dimension (does not change effect priority)
- `clearGlobalSkybox(level)` - Clear global skybox effect override
- `setGlobalMusic(level, musicData)` - Set global combat music effect override for a dimension (does not change effect priority)
- `clearGlobalMusic(level)` - Clear global combat music effect override
- `clearAllGlobalEffects(level)` - Clear all global effect overrides (fog, skybox, music) for a dimension
- `enableFilter(player, filterType)` - Apply a screen filter to a player (FilterType: SKETCH, SPOTLIGHT, MATRIX, RAIN, DESERT, SNOW, TOXIC, COSMOS)
- `disableFilter(player, filterType)` - Remove a screen filter from a player
- `isFilterEnabled(player, filterType)` - Check whether a filter is active on a player
- `getActiveFilters(player)` - Get a player's active filters (unmodifiable Set<FilterType>)
- `playBossShow(viewer, target, cutsceneId)` - Force-play a BossShow cutscene for a viewer (ignores watch history)
- `playBossShowIfNew(viewer, target, cutsceneId)` - Play a BossShow cutscene only if the viewer hasn't seen it before
- `stopBossShow(viewer)` - Stop the viewer's current BossShow cutscene
- `isBossShowPlaying(viewer)` - Check whether the viewer is currently in a BossShow cutscene
- `launchBossShowEvent(eventName, viewer, target)` - Trigger all Custom-trigger BossShows matching the event name (returns count launched)
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
- `getNearestEntity(level, pos, filter)` - Get the nearest entity matching a predicate (ECA resolver, so invulnerable entities are included)
- `getNearestEntity(level, pos, area, filter)` - Same, narrowed to an AABB
- `getNearestEntity(level, pos, entityClass)` - Get the nearest entity of a given type
- `getNearestEntity(level, pos, area, entityClass)` - Get the nearest entity of a given type inside an AABB
- `shaderPreset(id)` - Get a shader preset by id, exposing its ready-made render targets
- `startResurrection()` - Start the resurrection daemon thread (idempotent)
- `stopResurrection()` - Stop the resurrection daemon thread
- `isResurrectionRunning()` - Check whether the daemon is running
- `addResurrectionTarget(entity)` - Add an entity to the resurrection tracking set
- `removeResurrectionTarget(entity)` - Remove an entity from the resurrection tracking set
- `isResurrectionTracked(entity)` - Check whether an entity is tracked for resurrection
- `getResurrectionTrackedCount()` - Get the number of currently tracked entities
- `clearAllResurrectionTargets()` - Remove all entities from the tracking set
- `setResurrectionPollInterval(ms)` - Set the daemon poll interval (ms, clamped 1–10000, default 25)
- `getResurrectionPollInterval()` - Get the current poll interval in ms
- `getResurrectionTotalRevived()` - Get the total number of entities revived since start
- `getResurrectionTotalChecks()` - Get the total number of entity checks performed since start
- `checkResurrectionTarget(level, entity)` - Perform a one-shot container integrity check
- `reviveResurrectionTarget(level, entity)` - Manually force-revive a tracked entity immediately
- `createFaction(id, displayName, color)` - Create and register a faction (memory only)
- `createFaction(id, displayName, color, level)` - Create and register a faction, persisted to world SavedData
- `removeFaction(id)` - Remove a faction definition (memory only)
- `removeFaction(id, level)` - Remove a faction definition and drop every entity binding pointing at it
- `mergeFactions(intoId, fromId, level)` - Merge one faction into another: members are rebound, relation overrides are folded in, and the dissolved faction is removed. Returns the number of members moved, or -1 if the merge could not run
- `getFaction(id)` - Get a faction definition by id
- `getAllFactions()` - Get all registered factions
- `joinFaction(entity, factionId)` - Bind an entity to a faction
- `leaveFaction(entity)` - Unbind an entity from its faction
- `getEntityFaction(entity)` - Get the faction id an entity belongs to (null if none; tamed animals fall back to their owner's faction)
- `areSameFaction(a, b)` - Check whether two entities share a faction
- `isFriendly(a, b)` - Check the complete friendly relationship: same/friendly ECA faction, vanilla scoreboard alliance, or owner-pet alliance (excludes creative, spectator and ECA invulnerability)
- `getFactionMembers(level, factionId)` - Resolve the faction's member table to live entities in one level
- `kickAllFromFaction(factionId, level)` - Remove every explicit member globally, including unloaded and cross-dimension members
- `setFactionRelation(a, b, relation)` - Set faction A's relation toward faction B (memory only)
- `setFactionRelation(a, b, relation, level)` - Set faction A's relation toward B, persisted
- `getFactionRelation(a, b)` - Get the explicit relation from A to B (null if no override)
- `getEffectiveFactionRelation(source, target)` - Resolve the effective relation between two entities
- `canHarm(source, target)` - Check whether ECA faction relations allow source to harm the target
- `canTarget(source, target)` - Check whether complete faction and protection rules allow source to deliberately target the target
- `alertFactionMembers(factionId, attacker, victim, level)` - Make nearby untargeted allies retaliate against an attacker
- `joinFaction(uuid, typeId, isPlayer, factionId, level)` - Bind an entity to a faction by UUID, without requiring it to be loaded
- `leaveFaction(uuid, level)` - Remove a member from its faction by UUID, without requiring it to be loaded
- `getEntityFaction(uuid)` - Get the faction bound to a UUID (pure index lookup; no pet inheritance, which needs a live entity)
- `isFactionMember(uuid, factionId)` - Check whether a UUID belongs to a specific faction
- `getFactionMemberRecords(factionId)` - Get every member record (UUID + entity type) without loading entities
- `getFactionMemberUuids(factionId)` - Get every member UUID without loading entities
- `getFactionMembersByType(factionId, typeId)` - Filter members by entity type without loading entities
- `getFactionMemberCount(factionId)` - Get a faction's member count without loading entities
- `resolveFactionMembers(factionId, level)` - Resolve a faction's members to live entities in one level
- `setFactionLeader(factionId, leader, level)` - Set a faction's leader (joins the faction automatically if needed)
- `clearFactionLeader(factionId, level)` - Clear the leader; the former leader remains a member
- `getFactionLeader(factionId)` - Get the leader record without loading the entity
- `getFactionLeaderUuid(factionId)` - Get the leader's UUID
- `resolveFactionLeader(factionId, server)` - Resolve the leader to a live entity, searching every dimension
- `isFactionLeader(entity)` - Check whether an entity leads any faction
- `getFactionByLeader(uuid)` - Find which faction an entity leads
- `startRaid(level, pos, raidId)` - Start a raid at a position inside its target structure (center taken from the structure)
- `startRaidAt(level, center, raidId)` - Start a raid with an explicit center, skipping the structure lookup
- `endRaid(level, raid, victory)` - End a raid, discarding every surviving raider
- `endRaid(level, raidId, victory)` - End a raid by its instance id, discarding every surviving raider
- `getRaid(level, raidId)` - Get an active raid by its instance id
- `getActiveRaids(level)` - Get every active raid in a level
- `getNearestRaid(level, pos, maxDistance)` - Find the nearest active raid within a distance
- `getAllRaidDefinitions()` - Get all registered raid definitions

Here is a simple example:

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
EcaAPI.hurt(entity, damageSource, 50.0f);
EcaAPI.hurt(entity, player, 50.0f);  // Damage source derived from the attacker
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
EcaAPI.memoryRemove(entity, Entity.RemovalReason.CHANGED_DIMENSION);  // Remove using LWJGL's internal Unsafe instance
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
EcaAPI.enableAllReturn(entity);  // Players target armor and hand equipment mods; other entities target their own mod
EcaAPI.setGlobalAllReturn(true);  // Enable for ALL non-whitelisted mods
boolean enabled = EcaAPI.isAllReturnEnabled();
EcaAPI.disableAllReturn();  // Disable and clear all AllReturn

// Whitelist — AllReturn level (skip AllReturn only, defensive hooks still apply)
EcaAPI.addAllReturnWhitelist("com.yourmod.");
boolean removed = EcaAPI.removeAllReturnWhitelist("com.yourmod.");
boolean isProtected = EcaAPI.isAllReturnWhitelisted("com.yourmod.YourClass");

// Whitelist — Transform level (skip ALL ECA transformations including defensive hooks)
EcaAPI.addTransformWhitelist("com.yourmod.");
boolean removedTransform = EcaAPI.removeTransformWhitelist("com.yourmod.");
boolean isFullyProtected = EcaAPI.isTransformWhitelisted("com.yourmod.YourClass");

Set<String> allWhitelisted = EcaAPI.getAllWhitelistedPackages();

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

// Resurrection
EcaAPI.startResurrection();
EcaAPI.addResurrectionTarget(entity);
boolean tracked = EcaAPI.isResurrectionTracked(entity);
EcaAPI.removeResurrectionTarget(entity);
EcaAPI.setResurrectionPollInterval(50);
EcaAPI.stopResurrection();

// Faction
EcaAPI.createFaction("undead_legion", "Undead Legion", 0xFF884400, serverLevel);
EcaAPI.setFactionRelation("undead_legion", "village_guard", FactionRelation.HOSTILE, serverLevel);
EcaAPI.joinFaction(entity, "undead_legion");
String factionId = EcaAPI.getEntityFaction(entity);
boolean sameFaction = EcaAPI.areSameFaction(entityA, entityB);
boolean friendly = EcaAPI.isFriendly(entityA, entityB);
boolean mayAttack = EcaAPI.canHarm(attacker, target);
boolean mayTarget = EcaAPI.canTarget(attacker, target);
FactionRelation relation = EcaAPI.getEffectiveFactionRelation(attacker, target);
EcaAPI.leaveFaction(entity);

// Raid
RaidInstance raid = EcaAPI.startRaid(serverLevel, pos, "undead_siege");        // resolves the center from the target structure
RaidInstance forced = EcaAPI.startRaidAt(serverLevel, center, "undead_siege"); // explicit center, no structure lookup
List<RaidInstance> active = EcaAPI.getActiveRaids(serverLevel);
RaidInstance nearest = EcaAPI.getNearestRaid(serverLevel, pos, 128.0);
EcaAPI.endRaid(serverLevel, raid, true);                                       // ends in victory, clears surviving raiders
```

### Entity Extensions

This mod also provides a customizable entity type extension feature for adding special visual effects to your entities. You need to create a subclass extending `EntityExtension` and annotate it with `@RegisterEntityExtension` to register the extension. Here is a quick start example:

For the custom boss bar, use `enableBossBar()`, `shouldShowBossBar(LivingEntity)` and `bossBarExtension()`. Inside `BossBarExtension`, `showValueText()` enables centered `current/max` text. Override `getDisplayCurrentValue(LivingEntity)` and `getDisplayMaxValue(LivingEntity)` to return custom display values; by default they use the entity's health and maximum health.

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
    public String getFactionId() {
        return "undead_legion";  // entities of this type automatically join this faction. Default null = opt out. The faction must already be registered via @RegisterFaction or EcaAPI.createFaction(), otherwise the binding is refused and logged
    }

    @Override
    public boolean enableBossBar() {
        return true;  // master switch for boss bar takeover: whether ECA clears the entity's native boss bar and replaces it with ECA's. Default false — when not enabled, ECA never touches the entity's own boss bar. Must be true for bossBarExtension() / shouldShowBossBar() / custom health override to have any effect
    }

    @Override
    public boolean shouldShowBossBar(LivingEntity entity) {
        return entity != null && entity.isAlive();  // boss bar display condition (only effective when enableBossBar() is true)
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

    // Custom boss health bar (requires enableBossBar() = true)
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
            @Override public float getFrameAlpha() { return 1.0f; }  // frame opacity, 0.0~1.0 (default 1.0)
            @Override public float getFillAlpha() { return 1.0f; }  // fill opacity, 0.0~1.0 (default 1.0)
            @Override public boolean showValueText() { return true; }  // draw current/max values centered on the bar
            @Override public Number getDisplayCurrentValue(LivingEntity entity) { return entity.getHealth(); }  // optional custom current-value source
            @Override public Number getDisplayMaxValue(LivingEntity entity) { return entity.getMaxHealth(); }  // optional custom maximum-value source
        };
    }

    // Entity extra render layer
    @Override
    public EntityLayerExtension entityLayerExtension() {
        return new EntityLayerExtension() {
            @Override public boolean enabled() { return true; }  // enable render layer
            @Override public List<ShaderMaskPass> getShaderPasses() {
                ResourceLocation mask = texture("entity/boss_mask.png");
                return List.of(
                    ShaderMaskPass.masked(ArcaneRenderTypes.BOSS_LAYER, mask, 0x000000, 0.05f, 0.8f),
                    ShaderMaskPass.masked(VolcanoRenderTypes.BOSS_LAYER, mask, 0xFF0000, 0.05f, 0.8f),
                    ShaderMaskPass.masked(OceanRenderTypes.BOSS_LAYER, mask, 0x0000FF, 0.05f, 0.8f)
                );
            }
            @Override public boolean isGlow() { return true; }  // extra render layer glowing
            @Override public boolean isHurtOverlay() { return true; }  // show hurt overlay effect on this layer
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

    // Conditional gate: fog/skybox/music activate only when the matching shouldEnableXxx returns true; re-checked ~once/sec against the dimension's primary entity
    @Override
    public boolean shouldEnableFog(LivingEntity entity) {
        return entity.getHealth() < entity.getMaxHealth() * 0.5f;  // example: fog activates when entity is below 50% health
    }

    @Override
    public boolean shouldEnableSkybox(LivingEntity entity) {
        return true;  // skybox activation condition per entity instance
    }

    @Override
    public boolean shouldEnableMusic(LivingEntity entity) {
        return true;  // combat music activation condition per entity instance
    }

    /*
     * Conditional switching — override the entity-aware overload (on all five extension methods: bossBar / entityLayer / globalFog /
     * globalSkybox / combatMusic) to return a different extension object based on entity state. ECA invokes the entity-aware overload only
     * with a non-null entity (other cases use the no-arg variant) and catches any exception it throws, falling back to the no-arg variant.
     * Global effects re-evaluate ~once/sec against the dimension's primary entity; instance effects (boss bar/render layer) re-evaluate per frame.
     */
    @Override
    public GlobalSkyboxExtension globalSkyboxExtension(LivingEntity entity) {
        if (entity.getHealth() < entity.getMaxHealth() * 0.5f) {
            return phase2Skybox;  // below 50% health: switch to another GlobalSkyboxExtension you defined
        }
        return globalSkyboxExtension();  // default: the no-arg skybox above
    }
}
```

### ECA Blender Model

ECA Blender Model lets you use native `.blend` files or exported GLB files for entity models and animations. The following example shows how to set up a model and its animations.

#### Resources and model configuration

```text
assets/example/eca/blender/guardian/
  definition.json
  model.blend
  textures/body.png
```

```json
{
  "model": "model.blend",
  "scale": 1.0,
  "translation": [0.0, 0.0, 0.0],
  "rotation": [0.0, 0.0, 0.0],
  "default_animation": "Idle",
  "loop": true,
  "hidden_nodes": ["PresentationGround"],
  "node_time_source": "entity",
  "parameters": {}
}
```

The model ID consists of the resource namespace and the model directory path relative to `blender/`. For example, `assets/example/eca/blender/guardian/` has the ID `example:guardian`. You can organize models into subfolders: a model in `assets/example/eca/blender/bosses/guardian/` has the ID `example:bosses/guardian`.

| Field | Meaning |
| --- | --- |
| `model` | Relative model filename; defaults to `model.glb`, so explicitly select `model.blend` |
| `scale` | Overall scale, default 1 |
| `translation` / `rotation` | Three-component XYZ offset / rotation in degrees, default zero |
| `default_animation` / `loop` | Fallback action / looping, default no action / true |
| `hidden_nodes` | Named objects/nodes hidden with their children |
| `collection` / `object` | Native .blend collection plus descendants / single object selection; mutually exclusive, default current scene master collection |
| `node_time_source` | Native node clock: `entity` (default) or `animation` |
| `parameters` | Native numeric geometry-group inputs keyed by socket identifier |

Use the Action names from Blender for animation names. `__scene__` plays each object's currently assigned Action over the scene frame range. Replace the example names, such as `Idle` and `Walk`, with names from your model.

Textures may be packed into the model or referenced relative to its directory, such as `//textures/body.png`. To use GLB, export your model as glTF Binary and set `model` to `"model.glb"`.

Adjust `rotation`, `scale` and `translation` to fit the entity. Reload game resources after changing model files.

#### Binding through an entity extension

You can use an entity extension to enable ECA Blender Model for an existing entity:

```java
package example;

import net.eca.api.RegisterEntityExtension;
import net.eca.util.entity_extension.BlenderModelExtension;
import net.eca.util.entity_extension.BlenderRenderMode;
import net.eca.util.entity_extension.BossBarExtension;
import net.eca.util.entity_extension.EntityExtension;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.EntityLayerExtension;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@RegisterEntityExtension
public final class GuardianExtension extends EntityExtension {
    static {
        EntityExtensionManager.register(new GuardianExtension());
    }

    private GuardianExtension() {
        super(EntityType.GUARDIAN, 0);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public BossBarExtension bossBarExtension() { return null; }

    @Override
    @OnlyIn(Dist.CLIENT)
    public EntityLayerExtension entityLayerExtension() { return null; }

    @Override
    @OnlyIn(Dist.CLIENT)
    protected String getModId() { return "example"; }

    @Override
    @OnlyIn(Dist.CLIENT)
    public BlenderModelExtension blenderModelExtension() { return Client.MODEL; }

    @OnlyIn(Dist.CLIENT)
    private static final class Client {
        private static final BlenderModelExtension MODEL = new BlenderModelExtension() {
            @Override
            public ResourceLocation modelId() {
                return new ResourceLocation("example", "guardian");
            }

            @Override
            public BlenderRenderMode renderMode() {
                return BlenderRenderMode.REPLACE;
            }
        };
    }
}
```

This example binds the model to guardians. Replace `EntityType.GUARDIAN` with your entity type and `example:guardian` with your model ID. If the entity already has an extension, add the model configuration to it.

`BlenderRenderMode.REPLACE` replaces the entity model; `ADDITIVE` displays both models together. Replacement also hides the original model's additional layers, including armor and held items. If the model fails to load, the original model remains visible.

Override `shouldRender(entity)`, `scale(entity)` or `offsetX/Y/Z(entity)` to adjust the model for individual entities. Override `blenderModelExtension(LivingEntity)` to select different models for instances of the same entity type.

#### Animation controllers

The lifecycle controller selects idle, movement, hurt and death animations. The skill controller handles actions triggered by gameplay, such as attacks or spells. This `GuardianAnimations` class defines four lifecycle actions and a heavy attack with a hit marker:

```java
package example;

import net.eca.blender.animation.controller.BlenderAnimationClip;
import net.eca.blender.animation.controller.BlenderControllerContext;
import net.eca.blender.animation.controller.BlenderControllerSet;
import net.eca.blender.animation.controller.BlenderSkillDefinition;
import net.eca.blender.animation.controller.BlenderSkillMarker;
import net.eca.blender.animation.controller.DefaultBlenderLifecycleController;
import net.eca.blender.animation.controller.DefaultBlenderSkillController;

import java.util.List;

public final class GuardianAnimations {
    public static final BlenderControllerSet CONTROLLERS = new BlenderControllerSet(
        new DefaultBlenderLifecycleController(
            BlenderAnimationClip.looping("Idle"),
            BlenderAnimationClip.looping("Walk"),
            BlenderAnimationClip.once("Hurt", 8),
            BlenderAnimationClip.once("Death", 20)
        ),
        new DefaultBlenderSkillController(List.of(
            new BlenderSkillDefinition(
                "heavy_attack",
                BlenderAnimationClip.once("Attack_Heavy", 30),
                10, true, true, false,
                List.of(new BlenderSkillMarker("hit", 12))
            )
        )) {
            @Override
            public void onSkillMarker(BlenderControllerContext context, BlenderSkillMarker marker) {
                if ("hit".equals(marker.name())) {
                    // Apply your hit logic here; context.entity() is the entity using the skill.
                }
            }
        }
    );

    private GuardianAnimations() { }
}
```

Return this configuration from `GuardianExtension`:

```java
import net.eca.blender.animation.controller.BlenderControllerSet;
import net.minecraft.world.entity.LivingEntity;

// Method in GuardianExtension
@Override
public BlenderControllerSet blenderAnimationControllers(LivingEntity entity) {
    return GuardianAnimations.CONTROLLERS;
}
```

Model configuration runs on the client; animation controllers run on the server. Keep the client annotations shown in the example and reuse the same `CONTROLLERS` configuration. Returning new controllers on every call interrupts the current skill.

`looping("Walk")` loops an action. `once("Hurt", 8)` plays an eight-tick hurt action. There are 20 ticks per second; match durations to your model's animations. Use `null` for lifecycle actions you do not need. Movement is detected from horizontal entity velocity by default and can be customized.

The skill ID `heavy_attack` is used in API calls, while `Attack_Heavy` names the model action. This skill lasts 30 ticks and invokes the `hit` marker at tick 12.

| Skill parameter | Example | Meaning |
| --- | --- | --- |
| `priority` | `10` | Skill priority |
| `interruptible` | `true` | Other skills may interrupt this skill |
| `interruptOnHurt` | `true` | A hurt animation may interrupt this skill |
| `restartable` | `false` | Triggering the same skill cannot restart it |
| `markers` | `hit: 12` | Invoke a callback at animation tick 12 |

By default, an incoming skill needs at least the current skill's priority, and the current skill must allow interruption. Hurt interruption also requires a hurt action. Marker names must be unique and their times within the skill duration. Playback speed scales both the action and its markers.

#### Skill callbacks and custom controllers

Extend `DefaultBlenderLifecycleController` or `DefaultBlenderSkillController` to override individual methods. You can also implement `BlenderLifecycleController` and `BlenderSkillController` directly.

For example, change movement detection or require a strictly higher priority to interrupt a skill:

```java
import net.eca.blender.animation.controller.BlenderControllerContext;
import net.eca.blender.animation.controller.BlenderSkillDefinition;

// Override in a DefaultBlenderLifecycleController subclass.
@Override
public boolean isMoving(BlenderControllerContext context) {
    return context.entity().getDeltaMovement().horizontalDistanceSqr() > 0.0004;
}

// Override in a DefaultBlenderSkillController subclass to require higher priority.
@Override
public boolean canInterrupt(BlenderControllerContext context,
                            BlenderSkillDefinition current,
                            BlenderSkillDefinition incoming) {
    return current.interruptible() && incoming.priority() > current.priority();
}
```

The skill controller provides these server-side callbacks:

| Callback | Use |
| --- | --- |
| `onSkillStarted(context)` | Apply costs or prepare effects when a skill starts |
| `onSkillMarker(context, marker)` | Apply hit logic, spawn projectiles or perform other timed actions |
| `onSkillCompleted(context)` | Handle normal completion |
| `onSkillCancelled(context, reason)` | Clean up after interruption or cancellation |

`context.entity()` is the entity using the skill, `context.skill()` is its definition, and `context.elapsedTicks()` is its animation progress. Cancellation does not invoke completion. Each marker runs once per execution; tick-zero markers follow the start callback, and endpoint markers precede completion.

Callbacks cannot immediately change the same entity's playback. For combos, record the next action and trigger it from a later server tick. Controllers may be shared by multiple entities, so keep entity-specific skill data on the entity or in your own data manager.

#### Playback and skill API

Use `EcaAPI` from server-side gameplay code to trigger a skill or play a model action directly:

```java
import net.eca.api.EcaAPI;
import net.eca.blender.animation.controller.BlenderControllerContext;

// Call these operations as needed inside your server-side gameplay methods.
EcaAPI.triggerBlenderSkill(entity, "heavy_attack");

boolean attacking = EcaAPI.isBlenderSkillActive(entity);
BlenderControllerContext execution = EcaAPI.getBlenderSkillExecution(entity);

EcaAPI.cancelBlenderSkill(entity);

// Play an action directly, without a skill definition.
EcaAPI.playAnimation(entity, "Attack_Heavy");
EcaAPI.playAnimation(entity, "Walk", 1.25f, true);
EcaAPI.pauseAnimation(entity);
EcaAPI.resumeAnimation(entity);
EcaAPI.stopAnimation(entity);
```

`triggerBlenderSkill` follows the skill's definition and interruption policy. `cancelBlenderSkill` cancels the active skill. `getBlenderSkillExecution` returns null when no skill is active. Trigger skills from gameplay events such as attacks or casting; repeated calls attempt to trigger them again.

`playAnimation` interrupts the current skill and takes manual control. Replaying the same action restarts it. Non-looping actions hold their final pose until stopped or replaced. `stopAnimation` returns control to the controller; without a controller, playback returns to the binding's selected action or `default_animation`.

`pauseAnimation` and `resumeAnimation` pause/resume the action and skill timer. Hurt or death can still switch actions while paused. `isAnimationPlaying(entity)` checks for a playback state, including pauses and held final poses; use `isBlenderSkillActive` to check whether a skill is still running.

Call these APIs on the logical server thread. Death takes priority over other actions and blocks manual playback operations. Death animations do not delay entity removal, so match their duration to the entity's death sequence.

#### Node animation and parameters

Use `node_time_source` to select the clock for geometry nodes and material time drivers:

| Value | Behavior |
| --- | --- |
| `entity` (default) | Nodes continue across action loops, switches and pauses |
| `animation` | Nodes follow the current action's time, speed and pause state |

Use `entity` for effects such as a continuously rotating ring. Its time follows the client entity's age and may reset when the entity unloads and returns. Override `BlenderModelExtension.nodeTimeSource(entity, resourceDefault)` to select a clock per entity.

Set numeric geometry-group inputs with `parameters` in `definition.json`, for example `"parameters": {"Socket_2": 1.0}`, or return them dynamically from `BlenderModelExtension`:

```java
import net.minecraft.world.entity.LivingEntity;
import java.util.Map;

// Method in BlenderModelExtension; replace Socket_2 with the input identifier.
@Override
public Map<String, Float> nodeParameters(LivingEntity entity) {
    return Map.of("Socket_2", 1.0f);
}
```

Use the input's socket identifier rather than its display label. Scalar inputs are supported, and method values override resource values. Parameters are read on the client; your mod must synchronize values that originate in server gameplay.

#### Registering without entity extensions

You can register model bindings and controllers directly instead of using an entity extension. These two classes reuse `GuardianAnimations`; replace the example mod ID with your own.

Register the model during client initialization:

```java
package example;

import net.eca.blender.client.entity.BlenderEntityBindings;
import net.eca.blender.entity.BlenderEntityBinding;
import net.eca.blender.entity.BlenderRenderPolicy;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = "example", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GuardianClientBindings {
    private static final BlenderEntityBinding BINDING = new BlenderEntityBinding() {
        @Override
        public ResourceLocation modelId() {
            return new ResourceLocation("example", "guardian");
        }

        @Override
        public BlenderRenderPolicy renderPolicy() {
            return BlenderRenderPolicy.REPLACE;
        }
    };

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> BlenderEntityBindings.register(EntityType.GUARDIAN, BINDING));
    }
}
```

Register the controllers during common initialization:

```java
package example;

import net.eca.blender.animation.BlenderControllers;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod.EventBusSubscriber(modid = "example", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GuardianControllerBindings {
    @SubscribeEvent
    public static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() ->
            BlenderControllers.register(EntityType.GUARDIAN, GuardianAnimations.CONTROLLERS));
    }
}
```

`BlenderEntityBindings` registers the model binding; `BlenderControllers` registers animation controllers. Duplicate registrations for a type return false. Explicit registrations take precedence over entity-extension settings. Remove a registration with `unregister(type, binding)` or `unregister(type, controllers)` on the corresponding registry.

#### Supported features and limitations

The following applies to native `.blend` files in the format used by Blender 5.2.1. Native support is experimental. GLB uses exported models and animations rather than Blender node graphs.

##### Models, rigging and animation

| Feature | Supported modes and conditions |
| --- | --- |
| Meshes | Vertices, UVs, material slots, polygon triangulation, object hierarchy and transforms; selecting between multiple UV sets is not supported |
| Bones | Basic hierarchy and default inheritance, including bone-parented objects; segmented bone deformation is unsupported |
| Armature | One modifier using vertex-group weights, up to four effective bone influences per vertex; no bone envelopes or Preserve Volume |
| Actions | Object and pose-bone location, rotation and scale; Euler, quaternion and axis-angle rotation |
| Keyframes | Constant, Linear and Bezier interpolation; values hold at endpoints outside the curve range; no linear extrapolation or curve modifiers |
| Action layout | One layer with one keyframe strip, no strip time offset and layer influence of 1; no layer blending, NLA or Delta Transform |
| Bevel | Convex edges and planar faces, Edges/Offset/Angle, circular profile 0.5; requires Loop Slide; no custom profiles, material overrides, edge marks or Harden Normals |

Place geometry nodes and Bevel before Armature. Bevel does not support skinned meshes; realize instances before applying it. If bevels overlap, enable Clamp Overlap or reduce the width.

##### Geometry nodes

| Node or feature | Supported modes and conditions |
| --- | --- |
| Node groups | Group Input, Group Output, nested groups and Reroute; numeric group parameters |
| Inputs and conversions | Position, Normal, Index, Scene Time, numeric/vector/boolean inputs, Combine/Separate XYZ, Euler to Rotation and Rotation to Euler |
| Transform | Translation/Rotation/Scale components, not matrix mode |
| Set Position | Mesh vertices and points; no curves; realize instances first |
| Join Geometry | Combines supported geometry inputs |
| Instance on Points | Placement with selection, rotation and scale; no Pick Instance; convert curves first |
| Realize Instances | Full realization only, not partial realization by Selection or Depth |
| Set Material | Fixed material reference and uniform Selection; no per-point or per-face material selection |
| Curve Circle | Radius mode |
| Curve to Points | Count mode, Points output only; no Length mode or Tangent/Rotation outputs |
| Curve to Mesh | Closed planar polyline profiles with uniform Scale; no open-curve caps; Fill Caps has no effect on closed curves |
| Icosphere | Mesh output, not UV Map output |

Curves are limited to closed polylines generated by Curve Circle and their transforms; Bezier/NURBS curves are unsupported. Radius and Curve to Mesh Scale must be positive.

The following math operations are available in both geometry and material nodes:

| Node | Supported operations |
| --- | --- |
| Math | Add, Subtract, Multiply, Divide, Multiply Add, Power, Logarithm, Exponent, Square Root, Inverse Square Root, Absolute, Sign, Minimum, Maximum, Less Than, Greater Than, Compare, Round, Floor, Ceil, Truncate, Fraction, Modulo, Floored Modulo, Snap, Sine, Cosine, Tangent, their inverse functions, Atan2, Radians and Degrees |
| Vector Math | Add, Subtract, Multiply, Divide, Cross Product, Dot Product, Distance, Length, Scale, Normalize, Floor, Ceil, Absolute, Minimum and Maximum |

##### Material and texture nodes

| Node or feature | Supported modes and conditions |
| --- | --- |
| Basic inputs | Value, RGB, Combine/Separate XYZ, node groups and Reroute |
| Texture Coordinate | UV, Object and Generated outputs; referencing another object for coordinates is unsupported |
| Mapping | Point and Vector modes |
| Mix / Mix Color | Ordinary Mix blending; Float/Vector/Color with a uniform factor; no other blend modes or per-component factors |
| Color Ramp | RGB color mode with Linear, Ease or Constant interpolation |
| Image Texture | Flat projection, Linear/Closest interpolation, Repeat/Extend/Clip extension |
| Gradient Texture | Linear, Quadratic, Easing, Diagonal, Radial, Quadratic Sphere and Spherical |
| Noise Texture | 3D/4D fBM, Fac/Color outputs; Scale, Detail, Roughness, Lacunarity, Distortion and Normalize, plus W in 4D |
| Emission | Color and Strength |
| Diffuse | Basic Color; connected Roughness or Normal inputs are unsupported |
| Principled BSDF | Base Color, Alpha, Emission Color/Strength, Metallic and Roughness; no normal inputs, subsurface, transmission, coat or sheen |
| Mix Shader / Add Shader | Mixes or adds the supported surface shaders above |

Textures can be packed or referenced relative to the model directory. External absolute paths and parent-directory paths are unavailable. Images must use formats readable by Minecraft.

##### Material time drivers

The following driver expressions are supported on **unlinked floating-point inputs of material nodes**:

| Expression | Usage |
| --- | --- |
| `frame` | The frame number corresponding to the current node time |
| `frame / constant` | Divide the frame number by a fixed value, such as `frame / 12.0`; the divisor must be nonzero |

Use an expression driver in Blender and keep its default driver curve, without custom driver variables or curve modifiers. Only the two forms above are supported; expressions such as `frame * 2` and `sin(frame)` are not. An empty driver curve is also accepted. Edited curves must be a two-key identity mapping, where output equals input.

Drivers follow the node clock selected by `node_time_source`. This support applies to material node inputs, not object transforms or geometry node inputs.

##### Other limitations

Constraints, multi-track animation blending, shape keys, linked libraries, collection instances, node-tree keyframes, geometry simulation/repeat zones, subdivision, cloth and fluid modifiers are unsupported. Armor and held-item bone attachments require additional integration.

Materials use Minecraft lighting, so transparency, reflections and metallic surfaces can differ from Blender. Blender lights, volumes, displacement and color management are unsupported.

Complex models and dynamic nodes increase memory use and rendering cost. Choose resources appropriate for your target hardware. ECA does not impose resource budgets, but file-format, renderer and hardware requirements still apply.

For load failures, check the model ID and node error in the log, fix the resource and reload. Animation names and durations must match the model.

### Block Extensions

Block extensions add shader overlays without replacing the normal model. Ordinary baked and falling blocks use BLOCK-profile passes from `getBlockShaderPasses()`; GeckoLib block entities use NEW_ENTITY-profile passes from `getGeoShaderPasses(texture)`. A logical preset id still supplies the default RenderTypes for both profiles.

```java
@RegisterBlockExtension
public final class AmethystBlockExtension extends BlockExtension {
    static {
        BlockExtensionManager.register(new AmethystBlockExtension());
    }

    private AmethystBlockExtension() {
        super(Blocks.AMETHYST_BLOCK);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public ResourceLocation getShaderPresetId() {
        return new ResourceLocation("example", "amethyst_glow");
    }

    @Override
    public List<ShaderMaskPass> getBlockShaderPasses() {
        ResourceLocation mask = new ResourceLocation("example", "textures/block/amethyst_mask.png");
        return List.of(
            ShaderMaskPass.masked(getBlockRenderType(), mask, 0x000000, 0.05f, 1.0f),
            ShaderMaskPass.masked(CustomRenderTypes.BLOCK_FIRE, mask, 0xFF0000, 0.05f, 1.0f)
        );
    }
}
```

External masks on baked blocks are sampled with sprite-local UVs even though the model uses an atlas. Geo masks use the model texture's normal UV layout and combine with `overlayGeoBones()` as an intersection. Normal world blocks are indexed by section and batched into a separate overlay pass; falling blocks and GeckoLib block entities are handled automatically. Block items remain part of `ItemExtension`. The old Color-Key and single-mask getters are deprecated compatibility adapters.

### Item Extensions

You can create item extensions to add shader rendering effects to specific items: create a subclass extending `ItemExtension` and annotate it with `@RegisterItemExtension` to register.

```java
import net.eca.api.RegisterItemExtension;
import net.eca.client.render.ArcaneRenderTypes;
import net.eca.client.render.ShaderMaskPass;
import net.eca.client.render.StarlightRenderTypes;
import net.eca.client.render.VolcanoRenderTypes;
import net.eca.util.ItemUtil;
import net.eca.util.item_extension.EcaTooltipLine;
import net.eca.util.item_extension.ItemExtension;
import net.eca.util.item_extension.ItemExtensionManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

@RegisterItemExtension
public class DiamondSwordExtension extends ItemExtension {

    static {
        ItemExtensionManager.register(new DiamondSwordExtension());
    }

    public DiamondSwordExtension() {
        super(Items.DIAMOND_SWORD);  // target item
    }

    @Override
    protected String getModId() {
        return "your_mod_id";
    }

    @Override
    public boolean enabled() {
        return true;  // global master switch — return false to disable this extension entirely
    }

    @Override
    public boolean shouldRender(ItemStack stack) {
        return true;  // per-stack activation condition (e.g. check NBT, enchantment, custom name)
    }

    @Override
    public List<ShaderMaskPass> getShaderPasses() {
        ResourceLocation mask = texture("item/diamond_sword_mask.png");
        return List.of(
            ShaderMaskPass.masked(ArcaneRenderTypes.ITEM, mask, 0x000000, 0.05f, 0.8f),
            ShaderMaskPass.masked(VolcanoRenderTypes.ITEM, mask, 0xFF0000, 0.05f, 0.8f),
            ShaderMaskPass.masked(StarlightRenderTypes.ITEM, mask, 0x0000FF, 0.05f, 0.8f)
        );
    }

    @Override
    public List<EcaTooltipLine> getTooltipLines(ItemStack stack, TooltipFlag flag) {
        return List.of(
            EcaTooltipLine.head(ItemUtil.of("Starlight Forged")
                .addEffect.GRADIENT(0x7C4DFF, 0x00E5FF)
                .addEffect.BOLD()),
            EcaTooltipLine.body(ItemUtil.of("Arcane resonance: unstable")
                .addEffect.SOLID(0xFFAA00)
                .addEffect.ITALIC()),
            EcaTooltipLine.tail(ItemUtil.of("Hold Shift for hidden lore")
                .addEffect.RAINBOW())
        );
    }
}
```

Structured tooltip lines can choose their own insertion position:

- `EcaTooltipLine.head(...)`: below the item name.
- `EcaTooltipLine.body(...)`: in the main tooltip body, before advanced item id/NBT/disabled lines when present.
- `EcaTooltipLine.tail(...)`: at the end of the tooltip.

Each line accepts either a normal `Component` or an `EcaText` built through `ItemUtil.of(...)`, so tooltip text supports the same rich effects as item names: gradient, rainbow, solid color, shimmer, glitch, bold, italic, underline, and strikethrough. The older `appendTooltip(ItemStack, TooltipFlag, List<Component>)` hook is still available when you need to directly edit the final tooltip list.

Item mask passes use sprite-local UVs automatically. `ShaderMaskPass.masked(...)` samples an external mask texture, while `ShaderMaskPass.baseTexture(...)` selects colors directly from the item texture. The old `getRenderType()`, Color-Key, and single-mask getters are deprecated compatibility adapters.

Note: Like entity extensions, each item can only have one extension. Duplicate registrations are rejected with an error log. Both entity layer extensions (`EntityLayerExtension.getAlpha()`, default 0.5) and item extensions (`ItemExtension.getAlpha()`, default 1.0) support adjustable transparency for their shader overlay layers.

### Shader Mask Passes

Entity, item, and block shader overlays share the same `ShaderMaskPass` pipeline. Every pass supplies a RenderType, an optional UV-aligned mask texture, a target RGB color (black by default), a near-color tolerance, and opacity. An extension may return multiple passes so different colors in one mask use different shaders. Passes render in list order, and later passes draw over earlier passes where selected regions overlap. Transparent and non-matching mask pixels are discarded.

### Shader Presets

Packaged ECA-specific files use the common `assets/<namespace>/eca/<system>/` layout. Server-side definitions use the matching `data/<namespace>/eca/<system>/` layout, while standard Minecraft resources such as textures and language files remain in their vanilla directories. Legacy locations documented by earlier ECA versions continue to load as fallbacks.

This mod also provides several shader presets for the entity, item, and block extension systems, which can be used directly in your extensions. Simply replace `CustomRenderTypes` in the example code with the corresponding preset name. Each built-in preset class exposes 5 ready RenderTypes — `BOSS_BAR`, `BOSS_LAYER`, `SKYBOX` for entity extensions, `ITEM` for item extensions, and `BLOCK` for block extensions — plus `createEntityEffect(texture)` for entity texture overlays. Entity texture overlays are supported through `EntityLayerExtension.getTexture()` — return a texture to overlay it on the entity model, optionally combined with the shader RenderType for a texture‑plus‑shader effect (matching the boss‑bar overlay technique).

Every built-in preset is also packaged in the five-file layout, so it is registered as the preset ID `eca:<name>` and can be returned from `BlockExtension.getShaderPresetId()`. The NEW_ENTITY profile a GeckoLib block entity needs is obtained through `EcaPresets.geoBlock("eca:<name>", texture)`, since the preset classes carry no geo field.

Available presets:
- `TheLastEndRenderTypes` / `eca:the_last_end` — The Last End
- `DreamSakuraRenderTypes` / `eca:dream_sakura` — Dream Sakura
- `ForestRenderTypes` / `eca:forest` — Forest
- `OceanRenderTypes` / `eca:ocean` — Ocean
- `StormRenderTypes` / `eca:storm` — Storm
- `VolcanoRenderTypes` / `eca:volcano` — Volcano
- `ArcaneRenderTypes` / `eca:arcane` — Arcane
- `AuroraRenderTypes` / `eca:aurora` — Aurora
- `HackerRenderTypes` / `eca:hacker` — Hacker
- `StarlightRenderTypes` / `eca:starlight` — Starlight
- `CosmosRenderTypes` / `eca:cosmos` — Cosmos
- `BlackHoleRenderTypes` / `eca:black_hole` — Black Hole

### Screen Filters

This mod provides a set of full-screen post-processing filter presets that the server can apply per player, either by command or through the API. A filter is synced to the client and rendered as a shader pass over the level. Each player can have only one filter active at a time — applying a new one replaces the current one.

Filter presets:
- `SKETCH` — sketch
- `SPOTLIGHT` — spotlight
- `MATRIX` — matrix
- `RAIN` — rain
- `DESERT` — desert
- `SNOW` — snow
- `TOXIC` — toxic
- `COSMOS` — cosmos

### Shader Generator

ECA provides an in-game shader preset generator for building portable Minecraft core shader presets without writing GLSL by hand. Open it with:

```mcfunction
/eca shaderGenerator
```

The generator edits a layered composition project. Each layer can contain multiple visual modules, including basic shapes, starry sky effects, magic symbols, and image elements. The editor supports live preview, undo/redo, layer visibility, layer ordering, blend modes, canvas editing, project save/load, five-file shader export, and project deletion (**File -> Delete Current Project**, which asks for confirmation and then permanently removes the project directory with its source, textures and imported dependencies).

Each project also owns a five-file source workspace. Use **File -> Source Editor** to switch the same project to manual GLSL/JSON editing with a single-row menu, comment-based quick navigation, undo/redo, save, compile shortcuts, and debounced live preview. The right side places the preview above a scrollable compiler-output panel. Generated fragment shaders emit `// @eca-nav layer: ...` and `// @eca-nav element: ...` markers; manually written `// @eca-nav ...` comments create custom navigation points. Returning to the visual editor does not discard either representation. **File -> Import Shader Folder** opens the native folder picker at Forge's canonical game directory and copies a selected JSON/VSH/FSH shader into a new local ECA project. A folder may contain multiple shader programs, in which case the editor asks which one to import. When a source path is below `assets/<modid>/`, the project dialog pre-fills that Mod ID; otherwise the field remains empty. Standard three-file shaders are duplicated into the BLOCK and NEW_ENTITY source slots for independent compile validation; folders containing ECA's shared-fragment `_block`/`_entity` five-file layout preserve both profiles directly. The source folder is never modified.

Import supports standard Minecraft core shader JSON/VSH/FSH resources. Common time, camera, scale, opacity, and cosmic-UV uniforms receive preview bindings. A shader that depends on a mod-specific render pipeline, Java callbacks, textures, or uniforms may still need a dedicated adapter; unsupported fragment structure is reported as a compile error instead of being silently rewritten.

Texture dependencies are resolved as well. When an imported shader references a numbered sequence of PNG files, ECA attempts to copy those files, combine them into one preview texture, and provide the matching sampler and uniform with the UV range of each image. A PNG with a `.mcmeta` animation section updates frame by frame inside that combined texture while preserving frame order, per-frame duration, and interpolation. If ECA cannot determine how the files correspond to a sampler or uniform, the compiler-output panel names the unresolved dependency and scanned directory instead of failing silently; the shader compilation itself can still succeed.

Preview targets currently include plane, item, entity, skybox, and Boss bar. The exported preset uses the standard core shader five-file layout:

```text
assets/<namespace>/eca/shader_presets/<name>.fsh
assets/<namespace>/eca/shader_presets/<name>_block.vsh
assets/<namespace>/eca/shader_presets/<name>_block.json
assets/<namespace>/eca/shader_presets/<name>_entity.vsh
assets/<namespace>/eca/shader_presets/<name>_entity.json
```

The fragment shader is shared by both profiles. The two vertex profiles are generated separately because Minecraft uses different vertex formats for different render targets:

- `<name>_block.*` uses `DefaultVertexFormat.BLOCK`, for skybox, plane preview, and Boss bar rendering.
- `<name>_entity.*` uses `DefaultVertexFormat.NEW_ENTITY`, for entity layers, item layers, and textured entity effects.

Export modes:

- `PORTABLE`: standard Minecraft core shader output with no ECA-specific uniforms.
- `PORTABLE_WITH_ECA_HINTS`: includes ECA uniform hooks with harmless defaults, while remaining usable without ECA.
- `ECA_ENHANCED`: includes ECA-specific uniforms and expects ECA's enhanced shader runtime.

Project files are saved under `config/eca/shadergenerator/<namespace>/<name>/project.json`. Use **File -> Export As <shader>** to export a runtime-loadable five-file preset into `config/eca/shadergenerator/<namespace>/<name>/`. ECA automatically discovers presets from both mod assets and exported config presets. A preset ID is always `<namespace>:<name>`.

For mod-packaged presets, place the five files under `src/main/resources/assets/<namespace>/eca/shader_presets/`. The legacy `assets/<namespace>/shaders/core/` location remains supported, but the canonical ECA path takes precedence when both define the same ID. You may also declare the preset with `@RegisterShaderPreset`. The annotation registers the preset ID during startup scanning and is useful for mods that want to expose custom presets through an explicit Java marker class:

```java
import net.eca.api.RegisterShaderPreset;

@RegisterShaderPreset("mymod:my_nebula")
public final class MyNebulaPreset {
}
```

At runtime, use `EcaPresets` to obtain the generated RenderTypes:

```java
import net.eca.client.render.preset.EcaPresets;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public final class MyPresetRenderTypes {
    public static RenderType bossBar() {
        return EcaPresets.bossBar("mymod:my_nebula");
    }

    public static RenderType bossLayer() {
        return EcaPresets.bossLayer("mymod:my_nebula");
    }

    public static RenderType skybox() {
        return EcaPresets.skybox("mymod:my_nebula");
    }

    public static RenderType item() {
        return EcaPresets.item("mymod:my_nebula");
    }
}
```

You can also query the preset object through `EcaAPI`:

```java
import net.eca.api.EcaAPI;
import net.eca.client.render.preset.ShaderPreset;
import net.minecraft.resources.ResourceLocation;

ShaderPreset preset = EcaAPI.shaderPreset(new ResourceLocation("mymod", "my_nebula"));
```

The returned `ShaderPreset` exposes `bossBar()`, `bossLayer()`, `skybox()`, `item()`, `block()`, `geoBlock(texture)` and `entityForPreview(texture)`; `EcaPresets` mirrors the first six as static lookups by preset id. `block()` and `geoBlock(texture)` are the BLOCK and NEW_ENTITY profiles used by block extensions. For entity texture overlays, use `EntityLayerExtension.getTexture()` with `bossLayer()`.

#### AI Assistant

The source editor also has an AI assistant that drives the same project through a model of your choice. Three API formats are supported — OpenAI Responses, OpenAI Chat compatible, and Anthropic Messages — each stored as a profile with its own base URL, model, API key or key environment variable, custom headers, and a 10–600 second timeout in `config/eca/shadergenerator/settings.json`.

The model acts through tools, not free text: read the project summary and module schemas, edit layers and elements, import PNG images, modify any of the five source files, save the project, export the five shader files, compile and read diagnostics, capture the preview, and undo or redo its own mutations. Three switches bound how far it acts alone — allow automatic edits, compile after every edit, send preview images to vision models. With automatic editing off it can still inspect and explain but every write is refused; with automatic compiling on it recompiles after each edit and repairs from the diagnostics, capped by an automatic-repair limit and an overall tool-round limit.

#### MCP

The **MCP** button on the AI assistant page starts a local ECA Shader MCP that lets an external agent drive the current shader project. The service speaks Streamable HTTP, binds only to `127.0.0.1`, and needs no access token; the MCP page shows the port and connected agents, and the port is stored in `config/eca/shadergenerator/mcp_settings.json`. The URL port must match the MCP page, and both Minecraft and the MCP service must stay running.

Order of operations: start the service in-game first via **Shader Generator → AI Assistant → MCP**, then launch and connect the agent. An agent cannot connect while the service is not running.

##### Codex

Register from the command line:

```bash
codex mcp add eca_shader --url http://localhost:8767/mcp
```

##### Claude Code

Register from the command line:

```bash
claude mcp add --transport http eca_shader http://127.0.0.1:8767/mcp
```

### BossShow Cinematics

BossShow plays a cutscene that locks the player's camera onto a pre-recorded path around a target entity, with subtitles and server-side event callbacks. Camera paths are recorded with the built-in in-game editor — you don't need to write keyframes by hand.

**Default Editor Keybindings**:

| Key | Action |
|-----|--------|
| `J` | Start / resume recording |
| `I` | Pause recording |
| `ENTER` | Save recording |
| `ESC` | Discard recording |

**Editor Workflow**:
1. Run `/eca bossShow edit` near at least one LivingEntity (within 64 blocks).
2. In the Home GUI, click **+ New cutscene from entity** → aim at an entity → right-click to select it as the camera anchor. Or click **Edit** on an existing cutscene.
3. Configure trigger type (Range / Custom), target entity type, cinematic bars, allow repeat, etc.
4. Click **● Record**, press `J` to start. Move the camera freely in spectator mode — each tick is captured as an editable frame.
5. After recording, click any tick on the timeline to edit its position and camera orientation. Use **Add content** on any tick to add an event or subtitle.
6. Press `ENTER` to save, `ESC` to discard. Saved files go to `config/eca/bossshow/<namespace>/<path>.json`.

**Timeline editing** (after recording, in the editor GUI): every frame is selectable. Click or drag the bar to scrub, use the arrow keys for single-tick navigation, and edit `dx/dy/dz/yaw/pitch` in the inspector. Use **Add content** to attach an event or subtitle to the current tick. **Set In** / **Set Out**, **Copy** / **Cut** / **Delete**, **Paste**, range offsets, and `Ctrl+Z` / `Ctrl+Y` are available for timeline editing.

**For Mod Developers**

Two ways to define a cutscene:

1. **JSON only** — place a file at `data/<modid>/eca/bossshow/<path>.json`. Loaded automatically on startup. The legacy `data/<modid>/bossshow/<path>.json` location remains supported. No Java code is needed if you don't need server-side event handling.

2. **Java + JSON** — extend `BossShow` and annotate with `@RegisterBossShow` to get server-side event callbacks during playback.

Current v4 JSON example. This minimal example contains only 3 frames (ticks 0–2, 0.15 seconds at 20 TPS), so its events, subtitles and effects also stay within that range. For a usable cutscene, record a longer camera track in the editor and place content at the desired ticks:

```json
{
  "format_version": 4,
  "target_type": "minecraft:warden",
  "trigger": { "type": "range", "effect_radius": 32.0 },
  "cinematic": true,
  "allow_repeat": false,
  "anchor_yaw": 0.0,
  "frames": [
    { "dx": 0.0, "dy": 1.8, "dz": -6.0, "yaw": 0.0, "pitch": 10.0 },
    { "dx": 0.0, "dy": 1.8, "dz": -5.8, "yaw": 2.0, "pitch": 10.0 },
    { "dx": 0.0, "dy": 1.8, "dz": -5.6, "yaw": 4.0, "pitch": 10.0 }
  ],
  "events": [
    { "tick": 0, "event_id": "intro" },
    { "tick": 2, "event_id": "finisher" }
  ],
  "subtitles": [
    { "tick": 0, "text": "mymod.bossshow.warden.intro" }
  ],
  "effects": [
    { "tick": 0, "type": "camera_shake", "duration": 2, "fade_out": 1,
      "parameters": { "yaw": 1.5, "pitch": 1.0, "roll": 0.5, "frequency": 1.2 } },
    { "tick": 2, "type": "shader_effect", "effect": "chromatic_aberration",
      "duration": 1,
      "parameters": { "strength": 0.012, "angle": 15.0, "pulse_amount": 0.4, "pulse_speed": 2.0 } }
  ]
}
```

- `format_version`: current output is `4`; position offsets and camera yaw use the same anchor-local rotation convention. Older frame-based files are converted on load using `anchor_yaw`; do not migrate them by changing only the version number.
- `frames`: one object per tick, in playback order. A frame's index in the array is its tick — there is no separate time field. Generated by the editor.
- `frames[].dx/dy/dz`: camera offset in anchor-local coordinates.
- `frames[].yaw/pitch`: camera orientation (yaw is anchor-local).
- `events`: independent event track. Each entry contains a `tick` and `event_id`; entries are delivered to `BossShow.onKeyframeEvent()` at that tick.
- `subtitles`: independent subtitle track. Each entry contains a `tick` and `text`; text may be literal or a translation key.
- `effects`: client-side screen-effect track. Common fields are `tick`, `type`, `duration`, `fade_in`, `fade_out`, `easing`, and `parameters`. Types are `camera_shake`, `shader_effect`, and `filter`. Shader effects support `chromatic_aberration`, `wave_distortion`, `heat_haze`, `brightness_pulse`, `hue_cycle`, `scanlines`, and `vignette`; filters use the existing ECA filter names.
- `frames[].keyframe`: legacy compatibility field. New files should use `events` and `subtitles` instead.
- `trigger`: `{"type":"range","effect_radius":N}` auto-triggers when a player enters range of a matching entity. `{"type":"custom","event_name":"..."}` only fires via `EcaAPI.launchBossShowEvent(...)`.

> The old `samples` + `markers` format is no longer recognized — files using it load as zero-frame cutscenes. Re-record or migrate to `frames`.

Event handler example — the `event_id` strings in the JSON above are dispatched to `onKeyframeEvent` on the server at the corresponding tick:

```java
@RegisterBossShow
public class WardenIntroShow extends BossShow {
    public static final ResourceLocation ID = new ResourceLocation("mymod", "warden_intro");
    static { BossShowManager.register(new WardenIntroShow()); }

    public WardenIntroShow() { super(ID, EntityType.WARDEN); }

    @Override
    public void onKeyframeEvent(String eventId, BossShowContext ctx) {
        LivingEntity target = ctx.target();
        ServerPlayer viewer = ctx.viewer();
        if (target == null || !target.isAlive()) return;

        switch (eventId) {
            case "intro" -> target.level().playSound(null, target.blockPosition(),
                                SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 2f, 1f);
            case "phase2" -> EcaAPI.setInvulnerable(target, true);
            case "finisher" -> EcaAPI.kill(target, target.level().damageSources().generic());
        }
    }

    @Override
    public void onStart(BossShowContext ctx) {
        // called when playback begins
    }

    @Override
    public void onEnd(BossShowContext ctx, boolean skipped) {
        // called when playback ends; skipped = true if the viewer pressed ESC
    }
}
```

Triggering from code:

```java
EcaAPI.playBossShow(viewer, target, cutsceneId); // force play (ignores history)
EcaAPI.playBossShowIfNew(viewer, target, cutsceneId); // only if viewer hasn't seen it
EcaAPI.launchBossShowEvent("phase2", viewer, target); // match all Custom triggers with this event name
EcaAPI.stopBossShow(viewer); // stop current cutscene
EcaAPI.isBossShowPlaying(viewer); // check if viewer is in a cutscene
```

> If a `@RegisterBossShow` class has no matching JSON on first launch, an empty template JSON is auto-generated at `config/eca/bossshow/<namespace>/<path>.json`.

**For Modpack Developers**

- **Override cutscenes** — place your modified JSON at `config/eca/bossshow/<namespace>/<path>.json`. Config files override mod-bundled definitions (`data/<modid>/eca/bossshow/`, with legacy support for `data/<modid>/bossshow/`) with the same id.
- **Edit in-game** — `/eca bossShow edit` lets you re-record camera paths, edit any tick's pose, adjust triggers, add event/subtitle cues, and operate on frame ranges (copy / cut / delete / paste). Saves go to `config/eca/bossshow/`, leaving the mod jar untouched.
- **Translate or rewrite subtitles** — create `config/eca/bossshow/lang/<locale>.json` (e.g. `en_us.json`, `zh_cn.json`). These take priority over the mod's own lang files for subtitle keys:
    ```json
    {
      "mymod.bossshow.warden.intro": "A sound echoes from the deep..."
    }
    ```
- **Hot reload** — `/eca bossShow reload` picks up all JSON changes without restarting.

### Custom Factions

ECA provides a faction system that controls targeting and damage relationships. Binding an entity makes vanilla alliance checks and target assignment respect same-faction, friendly and neutral rules without requiring an interface. Faction-bound mobs periodically acquire nearby faction-bound entities with a `HOSTILE` relation through `Mob.setTarget`; their existing combat goals still perform movement and attacks. Entities without a faction are never selected by this faction acquisition pass. Standard `LivingEntity` damage paths enforce friendly protection; direct state-changing APIs remain the caller's responsibility. `FactionUtil.isFriendly` resolves alliances, while `FactionUtil.canAttack` additionally enforces creative/spectator and ECA invulnerability protection.

`EcaAPI.isFriendly(a, b)` is the public complete friendly check. It returns true for the same ECA faction, friendly ECA factions, vanilla scoreboard allies, owner-pet pairs, pets with the same owner, and pets whose owners are scoreboard allies. Creative mode, spectator mode and ECA invulnerability are deliberately excluded because they are attack protections rather than alliance relationships. Use `areSameFaction` only when exact ECA faction identity matters; `canHarm` checks ECA faction relations only, while `canTarget` also rejects neutral relations and complete target immunity.

Factions are registered by extending `FactionDefinition` and annotating the class with `@RegisterFaction`. Definitions are scanned during `FMLLoadCompleteEvent`; duplicate ids are logged and skipped (first one scanned wins). Factions can also be created at runtime through `EcaAPI.createFaction`, with or without persistence. An `EntityExtension` may declare `getFactionId()` so that every entity of that type joins a faction automatically — the faction has to be registered before those entities spawn, otherwise the binding is refused and logged.

Four relations are available:
- `SAME_FACTION` — same faction id, fully immune to each other and never targeted
- `FRIENDLY` — different factions but allied, no damage and no targeting
- `NEUTRAL` — not deliberately targeted, but incidental damage still applies
- `HOSTILE` — normal combat

`SAME_FACTION` is derived, not stored: it is produced whenever both sides resolve to the same faction id, so storing it as a cross-faction override would never be read back. Making two factions genuinely one therefore means merging them — `EcaAPI.mergeFactions(intoId, fromId, level)`, which `/eca faction relation A B same_faction` calls with A as the survivor. Members of B are rebound to A, B's relation overrides are inherited only where A has none of its own, third-party entries pointing at B are retargeted to A or dropped, and B is then deleted. A keeps its own display name, color, default relation and leader, inheriting B's leader only when it has none.

Relation resolution runs in this order, and the first match wins:
1. Same faction id → `SAME_FACTION`
2. A's `getRelation(self, target)` conditional override
3. A's static `hostileTo` / `friendlyTo` / `neutralTo` arrays
4. Symmetric fallback — the same two checks evaluated from B's side
5. A's `getDefaultRelation(self, target)` conditional override (only when the other side has no faction)
6. A's static default relation

Each faction owns its member table. A member is recorded as a UUID plus its entity type, so a roster can be listed, filtered by type and counted without loading a single entity — members sitting in unloaded chunks or other dimensions are still fully visible and manageable. Factions live in the overworld's SavedData, so membership is global across dimensions and survives restarts.

A binding is dropped when the entity is permanently removed; chunk unloads and dimension changes keep it, and players keep theirs across death and respawn. Membership cannot outlive its faction — unregistering a faction drops its whole member table, and joining a faction that does not exist is refused rather than silently recorded.

Tamed animals inherit their owner's faction automatically, so a pet is protected by its owner's allies and can answer nearby faction alerts. Inheritance is resolved at lookup time rather than stored: an inherited pet is not included in the persistent member table, offline queries, counts or table-wide leader propagation. It follows its owner across faction changes and never creates a binding of its own — calling `leaveFaction` on such a pet therefore does nothing. Bind a pet explicitly if it must belong elsewhere or participate in member-table operations; an explicit binding always takes precedence over inheritance.

A faction only owns membership, relations and leadership. Entity composition and spawn weights belong to the system that performs the spawning, so the same faction can be used by different systems without sharing spawn rules.

```java
@RegisterFaction
public class UndeadLegionFaction extends FactionDefinition {

    @Override public String getId() { return "undead_legion"; }
    @Override public String getDisplayName() { return "faction.your_mod.undead_legion"; }
    @Override public int getColor() { return 0xFF884400; }

    // relation toward entities that belong to no faction
    @Override public FactionRelation getStaticDefaultRelation() { return FactionRelation.HOSTILE; }

    @Override public String[] getFriendlyTo() { return new String[]{"lich_coven"}; }
    @Override public String[] getHostileTo() { return new String[]{"village_guard"}; }

    // optional: conditional relation, return null to fall back to the static arrays above
    @Override
    public FactionRelation getRelation(LivingEntity self, Entity target) {
        if (target instanceof Player player && player.isCreative()) {
            return FactionRelation.NEUTRAL;
        }
        return null;
    }

}
```

**Leaders:** A faction may designate one member as its leader. Setting a leader adds it to the faction automatically if it was not a member — a leader outside its own faction would be a contradictory state. Leaving the faction also vacates the post, and a leader that is permanently removed is cleared automatically.

**Threat propagation:** When a leader attacks a hostile faction member, or is attacked by one, that entity is offered as the target of every resolvable mob in the faction member table. Existing targets and faction target permissions may still prevent a switch. Ordinary member alerts are answered by nearby mobs from both the victim's own faction and friendly factions. Two mechanisms coexist:

| | Trigger | Range |
|---|---|---|
| Leader protection | the leader attacks or is attacked | the entire member table |
| Member alert | any member is hurt | configurable radius around the victim |

Leader protection is deliberately not range-limited: the member table is walked directly, so summons far from their master still answer. Members that cannot be resolved in the leader's dimension are skipped, and propagation never hands a member a target it is forbidden to attack. Repeat propagation of the same target within one tick is dropped, so a rapidly attacking leader does not walk the table on every hit.

Both mechanisms are governed entirely by config — there are no per-faction overrides, so every faction behaves the same way on a given server:

- `Leader Protection Enabled` (default `true`)
- `Immediate Leader Protection` (default `false`)
- `Alert Enabled` (default `true`) / `Alert Range` (default `32`) / `Immediate Member Alert` (default `false`)

"Immediate" off means only members that currently have no target will engage; on means they abandon whatever they were fighting.

**Querying:** Membership can be inspected from either direction, and the methods that do not resolve entities work entirely offline:

| Direction | Methods |
|---|---|
| entity relationship | `areSameFaction(a, b)` (same ECA faction only), `isFriendly(a, b)` (complete ECA + vanilla friendly check), `getEffectiveFactionRelation(a, b)`, `canHarm(a, b)` (ECA faction harm rules only), `canTarget(a, b)` (neutral and immunity-aware target check) |
| member → faction | `getEntityFaction(entity)` (includes pet inheritance), `getEntityFaction(uuid)`, `isFactionMember(uuid, id)` |
| faction → members | `getFactionMemberRecords(id)`, `getFactionMemberUuids(id)`, `getFactionMembersByType(id, typeId)`, `getFactionMemberCount(id)` |
| faction → entities | `resolveFactionMembers(id, level)` |
| faction → leader | `getFactionLeader(id)`, `getFactionLeaderUuid(id)`, `resolveFactionLeader(id, server)` (searches every dimension) |
| leader → faction | `getFactionByLeader(uuid)`, `isFactionLeader(entity)` |

`joinFaction` and `leaveFaction` both have UUID overloads for managing members whose entity is not loaded.

Faction members can also glow in their relation color for nearby players, which is configurable and off by default.

### Custom Raids

ECA provides a customizable raid system. The vanilla raid only works on villages, only accepts entities implementing `Raider`, and hardcodes its victory condition and rewards; an ECA raid can target any structure, use any entity type, and replace every rule that governs how it progresses and ends.

Raids are registered by extending `RaidDefinition` and annotating with `@RegisterRaid`. Scanning runs after faction scanning, so a raid definition may freely reference faction ids. Only `getId()`, `getDisplayName()` and `getWaves()` are required — everything else has a working default modelled on the vanilla raid.

**Targeting:** Override `getTargetStructure()` for a single structure, or `getTargetStructureTag()` to match any structure carrying a tag so one raid applies to several structure types. Anchoring drives the default defeat condition: the raid is lost when the target structure no longer covers the raid center. Declaring neither runs the raid unanchored, in which case it can only end by victory, timeout, or an explicit end call.

**Waves:** Each `RaidWave` mixes two spawn sources freely — explicit entity entries, and faction draws whose entity types and weights are configured directly on that wave. Different waves can use different compositions for the same faction.

**Raiders:** Explicit entries added with `addEntry(...)` are bound to `getRaiderFactionId()`, while each `addFaction(...)` group is bound to that entry's own faction ID. Wave leaders also use `getRaiderFactionId()`. Spawned `Mob` instances receive an injected goal that paths them to the raid center. The goal sits at priority 3 by default, matching vanilla's `PathfindToRaidGoal` — below the usual melee attack goal, so raiders fight an already acquired hostile-faction target and otherwise advance. Any entity type can be spawned and no interface is required, but non-`Mob` entities receive neither faction target acquisition, the navigation goal, nor mob callbacks. Override `getRaiderGoalPriority()` or return a negative value to change or disable goal injection.

**Boss:** A wave may declare a leader with `RaidWave.setLeader(type)`. The spawned entity becomes the leader of the raid's raider faction, so the faction's threat propagation applies to it for free. Eligible, loaded mobs without an existing target respond by default; `Immediate Leader Protection` allows them to replace an existing target. Declaring a leader requires `getRaiderFactionId()`; without a faction there is nothing to lead and the entry spawns as an ordinary raider.

Note that propagation walks the entire faction member table, not just this raid's participants. If the raider faction has other members elsewhere in the world, they answer too. Use a raid-specific faction if you want the response confined to the raid.

**Validation:** Starting a raid verifies the factions it references. A non-empty but unregistered raider faction refuses the start outright because the requested friendly-fire and alert rules could not be applied. Returning `null` intentionally is allowed and leaves each spawned entity governed by its own AI. A wave drawing from an unregistered faction or with no positive entity weights logs an error and skips that group, but the raid still starts.

**Progression:** `shouldAdvanceWave`, `checkVictory` and `checkDefeat` are all overridable. The defaults reproduce vanilla semantics: the next wave spawns once the previous one is dead, and the defenders win when every wave has spawned and every raider is gone.

**Timing and callbacks:** `getMaxDurationTicks()` defaults to 48000, `getWaveCooldownTicks()` to 300, `getParticipantRadius()` to 96 blocks and `getCelebrationTicks()` to 600. A wave can add its own `spawnDelay()` and `spawnRadius()`. Lifecycle hooks are `onStart`, `onWaveStart`, `onWaveEnd`, `onVictory`, `onDefeat` and `onStop`. Client-side `bossBarExtension()` can replace the raid bar appearance while retaining server-synced raid state.

**Endless raids:** `isEndless()` cycles the wave list forever and never satisfies the default victory condition. Finish one with `EcaAPI.endRaid`, which discards every surviving raider.

Raids run per dimension and automatically restore their latest periodic checkpoint after a restart. Permanent casualties and terminal operations are saved immediately; ordinary progression is checkpointed once per second. The center chunk is force-loaded for the duration, but raiders that travel into other unloaded chunks are not force-loaded with it.

```java
@RegisterRaid
public class UndeadSiege extends RaidDefinition {

    @Override public String getId() { return "undead_siege"; }
    @Override public String getDisplayName() { return "raid.your_mod.undead_siege"; }
    @Override public String getRaiderFactionId() { return "undead_legion"; }

    @Override
    public ResourceKey<Structure> getTargetStructure() {
        return ResourceKey.create(Registries.STRUCTURE, new ResourceLocation("minecraft", "village_plains"));
    }

    @Override
    public List<RaidWave> getWaves() {
        return List.of(
            // explicit entity types
            new RaidWave().addEntry(EntityType.ZOMBIE, 6),
            // drawn using this wave's own weights
            new RaidWave().addFaction("undead_legion", 10, Map.of(EntityType.ZOMBIE, 8, EntityType.SKELETON, 2)),
            // both sources mixed, with a per-mob post-spawn callback
            new RaidWave()
                .addEntry(EntityType.WITHER_SKELETON, 4, mob -> mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD)))
                .addFaction("undead_legion", 8, Map.of(EntityType.ZOMBIE, 1, EntityType.WITHER_SKELETON, 4))
                .setLeader(EntityType.WITHER, mob -> mob.setCustomName(Component.literal("Undead Warlord")))
                .spawnRadius(32.0)
        );
    }

    // optional: replace the default victory condition
    @Override
    public boolean checkVictory(RaidContext ctx) {
        return ctx.isAllWavesSpawned() && ctx.getAliveRaiderCount() == 0;
    }

    @Override
    public void onVictory(RaidContext ctx) {
        for (ServerPlayer player : ctx.getNearbyPlayers()) {
            player.giveExperiencePoints(500);
        }
    }
}
```

Registering a definition does not start anything. Raids are started explicitly so that any trigger condition can drive them — entering a region, using an item, a command, a scheduled event:

```java
// starts inside the target structure; the center comes from the structure's bounding box
RaidInstance raid = EcaAPI.startRaid(serverLevel, pos, "undead_siege");

// explicit center, structure lookup skipped
RaidInstance forced = EcaAPI.startRaidAt(serverLevel, center, "undead_siege");

// end it early, clearing every surviving raider
EcaAPI.endRaid(serverLevel, raid, true);
```

### ECA Transformer Whitelist

Although I've added as many common libraries and mods to the ECA Transformer whitelist as possible, there may still be mods that crash due to ECA transformation. So I've prepared a JSON configuration for modpack developers to add package prefixes to the whitelist. You can add JSON files under the `config/eca/` folder. If the folder is empty on first launch, example files will be generated automatically.

Only the `type` and `packages` fields are required, other fields are ignored:

Single mod example (`allreturn` — skip AllReturn only, defensive hooks still apply):
```json
{
  "type": "allreturn",
  "packages": [
    "com.example.yourmod."
  ]
}
```

Multiple mods example (`transform` — skip ALL ECA transformations):
```json
{
  "type": "transform",
  "packages": [
    "com.example.modA.",
    "com.example.modB.",
    "net.example.modC."
  ]
}
```

Any `.json` filename works, and you can have multiple files.

---

# 中文

本 Mod 提供了一些基于 CoreMod (ITransformationService)、Java Agent 和 Mixin 等技术所实现的实体操作 API 和相关命令，此外还提供一系列功能模块：BossShow、实体扩展、ECA Blender Model、方块扩展、物品扩展、屏幕滤镜、ECA 着色器生成器、自定义阵营与自定义袭击。注意，本 Mod 的实体操作方法虽然在命名上可能与原版一致，但本质上的实现完全不同。例如，设置生命值 API 可以修改部分使用自定义生命值（包括但不限于实体数据、数字类型字段、部分哈希表）的实体；清除 API 则是进行了 Minecraft 底层容器的相关清除；设置无敌 API 则是提供了比原版创造模式无敌更强大的实现。此外，本 Mod 还将原版属性上限解锁至 Double.MAX_VALUE。如不需要，可在配置文件 "Unlock Attribute Limits" 中关闭。

本 Mod 的初衷是为开发者提供简化的实体操作 API，并在确保性能和兼容性的前提下获得一定的强度。因此，请不要将本 Mod 用于 Mod 战力对比和无休止的代码军火竞赛中。此外，在整合包生存环境下，最好确保攻击和防御逻辑的激进配置项处于关闭状态。

本 Mod 还提供了一个 [MCreator 插件](https://mcreator.net/plugin/121284/20244epic-core-api-plugin)来方便 MCreator 用户使用本 Mod 中的 API。

## 玩家使用

玩家可以使用以下 `/eca` 命令（需要确保权限等级 ≥ 2）：
- `/eca setHealth <目标> <血量值> [report]` - 设置实体血量值。追加 `report` 后，会直接在 `logs/` 中为每个生物目标生成独立的 `<实体名>_health_report_<时间点>.txt` 结构化报告，给出观测到的存储模型、各通道执行结果、实际成功模块、置信度与判断依据、配置门控及下一 tick 延迟复查结果。
- `/eca setMaxHealth <目标> <最大血量值>` - 设置实体最大生命值（反算属性基础值）
- `/eca setInvulnerable <目标> <true|false>` - 设置实体无敌状态
 - `/eca lockHealth <目标> true <血量值>` - 锁定实体血量
 - `/eca lockHealth <目标> false` - 解锁实体血量
 - `/eca lockMaxHealth <目标> true <值>` - 锁定实体最大生命值
 - `/eca lockMaxHealth <目标> false` - 解锁实体最大生命值
 - `/eca banHealing <目标> true [血量值]` - 限制被禁疗的实体的生命值无法超过设置的禁疗值（数值可选，默认使用当前血量）。
 - `/eca banHealing <目标> false` - 解除禁疗
 - `/eca hurt <目标> <伤害值>` - 强制实体受伤（先走原版 hurt，血没扣对时强制写入；由生物执行时掉落与经验归属给执行者）
 - `/eca kill <目标>` - 击杀实体
- `/eca remove <目标> [原因]` - 从世界中移除实体
- `/eca memoryRemove <目标>` - 危险！需要开启激进攻击逻辑配置，通过 LWJGL 的内部 Unsafe 实例清除实体
- `/eca teleport <目标> <x> <y> <z>` - 将实体传送到其当前维度的指定坐标。
- `/eca lockLocation <目标> <true|false> [x y z]` - 锁定/解除实体位置
- `/eca cleanBossBar <目标>` - 清理 Boss 血条
- `/eca allReturn <目标> <true|false>` - 危险！需开启“激进攻击逻辑”。按参数启用或关闭 AllReturn；玩家针对四个盔甲槽及主副手装备所属模组，其他实体针对自身所属模组，均遵守转换白名单。
- `/eca allReturn global <true|false>` - 危险！启用/禁用全局 AllReturn，影响所有非白名单 mod
- `/eca banSpawn <目标> <秒数>` - 禁止选中实体的类型生成指定时长
- `/eca banSpawn clear` - 解除当前维度所有禁生成
- `/eca setForceLoading <目标> <true|false>` - 启用/禁用实体强加载
- `/eca setInvulnerable show_all` - 显示所有无敌实体
- `/eca entityExtension get_registry` - 查看实体扩展注册表
- `/eca entityExtension get_active` - 查看当前维度活跃的扩展类型
- `/eca entityExtension get_current` - 查看当前生效的实体扩展
- `/eca entityExtension clear` - 清空当前维度活跃扩展表和所有全局效果覆盖
- `/eca entityExtension set_skybox <预设名>` - 设置全局天空盒着色器预设
- `/eca setFilter <目标> true <类型>` - 为玩家施加屏幕滤镜（类型：sketch、spotlight、matrix、rain、desert、snow、toxic、cosmos）
- `/eca setFilter <目标> false` - 移除玩家的所有激活滤镜
- `/eca bossShow edit` - 打开游戏内演出编辑器（自动切换旁观模式）
- `/eca bossShow exit` - 退出编辑器并恢复之前的游戏模式
- `/eca bossShow list` - 列出所有已加载的演出定义
- `/eca bossShow play <观看者> <目标> <id>` - 强制播放指定演出
- `/eca bossShow stop <观看者>` - 停止该玩家当前的演出
- `/eca bossShow reload` - 从磁盘重新加载所有演出 JSON 定义
- `/eca bossShow clearHistory <玩家>` - 清除该玩家的"已观看"记录
- `/eca shaderGenerator` - 打开游戏内着色器预设生成器
- `/eca resurrection start` - 启动线程复活守护线程
- `/eca resurrection stop` - 停止守护线程
- `/eca resurrection status` - 查看线程状态及复活/检查计数
- `/eca resurrection add <目标>` - 将实体加入复活追踪（每次轮询自动复活死亡实体）
- `/eca resurrection remove <目标>` - 将实体从追踪中移除
- `/eca resurrection list` - 列出所有被追踪实体及其容器完整性状态
- `/eca resurrection check <目标>` - 对实体进行一次性容器完整性检查（不执行复活）
- `/eca resurrection revive <目标>` - 立即手动强制复活一个被追踪的实体
- `/eca resurrection interval <毫秒>` - 设置轮询间隔（100~10000 ms，默认 25 ms）
- `/eca faction create <id> <显示名> [颜色]` - 创建阵营（颜色可用预设名，如 red/gold/teal）
- `/eca faction remove <id>` - 删除阵营定义，并清除指向它的全部实体绑定
- `/eca faction join <阵营ID> [目标]` - 将实体加入阵营（省略目标时为命令执行者）
- `/eca faction leave [目标]` - 将实体移出当前阵营
- `/eca faction list` - 列出全部已注册阵营
- `/eca faction info [阵营ID]` - 查看阵营的颜色、成员与关系覆盖
- `/eca faction relation <阵营A> <阵营B> <关系>` - 设置 A 对 B 的关系。`hostile` / `neutral` / `friendly` 写入关系覆盖；`same_faction` 则是把 B 并入 A（B 的成员改绑到 A，关系归并，随后删除 B）
- `/eca faction leader <阵营ID>` - 查看阵营首领及其当前是否已加载
- `/eca faction leader <阵营ID> set [目标]` - 设置首领（省略目标时为命令执行者，会自动加入该阵营）
- `/eca faction leader <阵营ID> clear` - 清除首领，原首领仍保留成员身份
- `/eca raid defs` - 列出全部已注册的袭击定义
- `/eca raid list` - 列出当前维度的活跃袭击
- `/eca raid start <定义ID> [坐标]` - 在目标结构内发起袭击（省略坐标时为命令执行者位置）
- `/eca raid startat <定义ID> <坐标>` - 以指定坐标为中心强制发起袭击，跳过结构查询
- `/eca raid info <实例ID>` - 查看单场袭击的详情
- `/eca raid end <实例ID> <victory|defeat>` - 结束袭击并清除全部仍存活的袭击者

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
> 将 `VERSION` 替换为所需版本（如 `1.1.7-fix-fix`）。前往 [ECA Modrinth 页面](https://modrinth.com/mod/epic-core-api) 查看可用版本。

**第三步：声明依赖** (mods.toml)
```toml
[[dependencies.你的modId]]
modId="eca"
mandatory=true
versionRange="[1.1.5,)"
ordering="NONE"
side="BOTH"
```

### API 参考

- `lockHealth(entity, value)` - 锁定实体血量值（用于无敌阶段、治疗等）
- `unlockHealth(entity)` - 解除血量锁定
- `getLockedHealth(entity)` - 获取当前锁定值（未锁定返回 null）
- `isHealthLocked(entity)` - 检查是否锁定
- `banHealing(entity, value)` - 限制被禁疗的实体的生命值无法超过设置的禁疗值。
- `unbanHealing(entity)` - 解除禁疗
- `getHealBanValue(entity)` - 获取当前禁疗值（未禁疗返回 null）
- `isHealingBanned(entity)` - 检查是否被禁疗
- `getHealth(entity)` - 如果实体的真实血量已经被ECA改血进行了分析并得到真实血量，返回真实血量；否则将会返回实体的原版生命值数据。读取本身不触发分析，也不回退调用实体的 `getHealth()`；实体为 null 返回 0.0f，存储读取失败返回 NaN。
- `getRealHealth(entity)` - 读取规则与 `getHealth(entity)` 相同，但实体为 null 时返回 NaN。
- `setHealth(entity, health)` - 通过带校验的改血流程尝试设置生命值：原版直写 → 数据流逆向 → 外部扫描 → 方法探针 → 数值反演；玩家只执行原版直写。数据流分析定位存储并反演读取表达式，包括可追踪的反射、Unsafe 和 VarHandle 访问；探针发现新证据时可回访前置通道。各通道受证据、配置及预算约束，尝试后校验，涉及探测写入时使用快照与失败回滚；当场校验通过不保证后续 tick 不被实体自身逻辑改回，另有延迟复查。基础数据流逆向默认可用，仅受强制兼容模式限制；Const Override、External Scan、Method Probe 和 Numeric Inversion 需要激进攻击逻辑及 `Attack → setHealth` 下的对应开关，默认关闭。
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
- `hurt(entity, damageSource, amount)` - 强制实体受伤并保证血量确实扣掉。原版 `hurt` 的实际扣血只有 `actuallyHurt` 末尾的 `setHealth(getHealth() - damage)` 一处，走的是实体自己的 getter 与 setter：两者被重写或与真实存储解耦时，整套流程照常跑完、事件照常发出，血却没掉。本方法先清无敌帧并调用原版 `hurt`（减免、击退、仇恨与受击表现均正常发生），再以 `min(1.0, 伤害值 * 50%)` 容差比对血量锚点与 `受伤前 - 伤害值`。不符时补齐原版本该留下的伤害源记账（lastHurtByMob、lastHurtByPlayer/Time、lastDamageSource/Stamp、战斗记录、受击动画）并通过 `setHealth` 强制落血量，下限钳到 0。致死结果不会在此强行推进死亡流程：实体停在 0 血，由原版 `tickDeath` 播放死亡动画并移除——需要立即击杀请改用 `kill`。处于 ECA 锁血或无敌状态的实体交由那两套系统处理——原版 `hurt` 照常调用，但不做强制写入。
- `hurt(entity, attacker, amount)` - 同一流程，伤害源由攻击者推导：玩家用 `playerAttack`，其他生物用 `mobAttack`，使击杀归属与掉落归属符合预期
- `kill(entity, damageSource)` - 击杀实体（掉落 + 成就 + 移除）
- `revive(entity)` - 复活实体（清除死亡状态）
- `revive(level, uuid)` - 在指定维度按 UUID 复活实体
- `reviveAllContainers(entity)` - 复活实体的所有关键容器（tickList、lookup、sections、tracker）
- `reviveAllContainers(level, uuid)` - 在指定维度按 UUID 复活实体的所有关键容器
- `teleport(entity, x, y, z)` - 将实体强制传送到当前维度的指定坐标，并同步到客户端，需要在服务端主线程执行。
- `lockLocation(entity)` - 锁定实体当前位置
- `lockLocation(entity, position)` - 锁定实体到指定位置
- `unlockLocation(entity)` - 解除实体位置锁定
- `isLocationLocked(entity)` - 检查实体位置是否锁定
- `getLockedLocation(entity)` - 获取锁定位置（未锁定返回 null）
- `remove(entity, reason)` - 完整移除（AI、Boss 血条、容器、乘客等）
- `memoryRemove(entity, reason)` - 危险！需要开启激进攻击逻辑配置，通过 LWJGL 的内部 Unsafe 实例清除实体
- `cleanupBossBar(entity)` - 仅移除 Boss 血条
- `isInvulnerable(entity)` - 检查 ECA 无敌状态
- `setInvulnerable(entity, invulnerable)` - 设置无敌状态（开启：复活、锁血、阻断伤害、每 tick 清除有害效果、阻止怪物锁定、保护玩家物品栏；关闭：清除所有保护）
- `enableAllReturn(entity)` - 危险！需开启“激进攻击逻辑”。对实体所属模组启用AllReturn；玩家则针对盔甲及主副手装备所属模组。仅转换符合条件的 boolean 和 void 方法，并遵守转换白名单。
- `disableAllReturn(entity)` - 按与启用相同的目标规则关闭 AllReturn：玩家针对盔甲及主副手装备所属模组，其他实体针对自身所属模组。
- `setGlobalAllReturn(enable)` - 危险！需要开启激进攻击逻辑配置，启用/禁用全局 AllReturn，影响所有非白名单 mod
- `disableAllReturn()` - 关闭 AllReturn 并清除目标
- `isAllReturnEnabled()` - 检查 AllReturn 是否启用
- `addAllReturnWhitelist(prefix)` - 添加 AllReturn 白名单前缀（跳过 AllReturn 转换，防御性 Hook 仍然生效）
- `removeAllReturnWhitelist(prefix)` - 移除 AllReturn 白名单前缀（内置条目不能移除）
- `addTransformWhitelist(prefix)` - 添加转换白名单前缀（跳过全部 ECA 转换，包括防御性 Hook）
- `removeTransformWhitelist(prefix)` - 移除转换白名单前缀（内置条目不能移除）
- `isAllReturnWhitelisted(className)` - 检查类是否在 AllReturn 白名单中
- `isTransformWhitelisted(className)` - 检查类是否在转换白名单中（跳过全部转换）
- `getAllWhitelistedPackages()` - 获取所有白名单前缀（两级合并，内置 + 自定义）
- `getEntityExtensionRegistry()` - 获取所有已注册的实体扩展（Map<EntityType, EntityExtension>）
- `getActiveEntityExtensionTypes(level)` - 获取当前维度活跃的扩展类型（Map<EntityType, Integer>）
- `getActiveEntityExtension(level)` - 获取当前生效的实体扩展（最高优先级）
- `clearActiveEntityExtensionTable(level)` - 清空当前维度活跃扩展表
- `playAnimation(entity, animation)` - 手动接管并以速度 1 从头播放指定模型动作，不循环（逻辑服务端线程）
- `playAnimation(entity, animation, speed, loop)` - 按指定速度和循环设置手动开始／重启动作（逻辑服务端线程）
- `stopAnimation(entity)` - 停止受管理动作，取消活跃技能并交回控制器／回退动作
- `pauseAnimation(entity)` - 暂停受管理动作和技能计时，但不冻结生命周期决策
- `resumeAnimation(entity)` - 从保存的位置继续受管理动作与技能计时
- `isAnimationPlaying(entity[, animation])` - 查询受管理动作状态，包含暂停和末尾保持，可匹配动作名
- `triggerBlenderSkill(entity, skillId)` - 按技能 ID 和打断策略触发技能
- `cancelBlenderSkill(entity)` - 取消当前技能，不执行完成回调
- `isBlenderSkillActive(entity)` - 查询活跃技能，包含暂停状态
- `getBlenderSkillExecution(entity)` - 获取当前技能执行快照，无执行时返回 null
- `setGlobalFog(level, fogData)` - 设置维度全局雾气效果覆盖（不改变效果优先级）
- `clearGlobalFog(level)` - 清除全局雾气效果覆盖
- `setGlobalSkybox(level, skyboxData)` - 设置维度全局天空盒效果覆盖（不改变效果优先级）
- `clearGlobalSkybox(level)` - 清除全局天空盒效果覆盖
- `setGlobalMusic(level, musicData)` - 设置维度全局战斗音乐效果覆盖（不改变效果优先级）
- `clearGlobalMusic(level)` - 清除全局战斗音乐效果覆盖
- `clearAllGlobalEffects(level)` - 清除维度所有全局效果覆盖（雾气、天空盒、音乐）
- `enableFilter(player, filterType)` - 为玩家施加屏幕滤镜（FilterType：SKETCH、SPOTLIGHT、MATRIX、RAIN、DESERT、SNOW、TOXIC、COSMOS）
- `disableFilter(player, filterType)` - 移除玩家的某个屏幕滤镜
- `isFilterEnabled(player, filterType)` - 检查玩家是否激活了某个滤镜
- `getActiveFilters(player)` - 获取玩家激活的滤镜（不可变 Set<FilterType>）
- `playBossShow(viewer, target, cutsceneId)` - 强制为观看者播放 BossShow 演出（无视观看历史）
- `playBossShowIfNew(viewer, target, cutsceneId)` - 仅在观看者未看过时播放 BossShow 演出
- `stopBossShow(viewer)` - 停止观看者当前的 BossShow 演出
- `isBossShowPlaying(viewer)` - 检查观看者是否正在 BossShow 演出中
- `launchBossShowEvent(eventName, viewer, target)` - 触发所有匹配该事件名的自定义触发 BossShow（返回启动数量）
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
- `getNearestEntity(level, pos, filter)` - 按自定义条件获取最近实体（走 ECA 解析器，无敌实体同样在搜索范围内）
- `getNearestEntity(level, pos, area, filter)` - 同上，限定在 AABB 范围内
- `getNearestEntity(level, pos, entityClass)` - 获取最近的指定类型实体
- `getNearestEntity(level, pos, area, entityClass)` - 获取 AABB 范围内最近的指定类型实体
- `shaderPreset(id)` - 按 ID 获取着色器预设对象，取用其现成的渲染目标
- `startResurrection()` - 启动复活守护线程（幂等）
- `stopResurrection()` - 停止复活守护线程
- `isResurrectionRunning()` - 检查守护线程是否运行
- `addResurrectionTarget(entity)` - 将实体加入复活追踪
- `removeResurrectionTarget(entity)` - 将实体从复活追踪中移除
- `isResurrectionTracked(entity)` - 检查实体是否在复活追踪中
- `getResurrectionTrackedCount()` - 获取当前追踪的实体数量
- `clearAllResurrectionTargets()` - 清除全部复活追踪目标
- `setResurrectionPollInterval(ms)` - 设置复活轮询间隔（毫秒，范围 1–10000，默认 25）
- `getResurrectionPollInterval()` - 获取当前轮询间隔（毫秒）
- `getResurrectionTotalRevived()` - 获取累计复活次数
- `getResurrectionTotalChecks()` - 获取累计检查次数
- `checkResurrectionTarget(level, entity)` - 进行一次容器完整性检查
- `reviveResurrectionTarget(level, entity)` - 手动强制复活被追踪的实体
- `createFaction(id, displayName, color)` - 创建并注册阵营（仅内存）
- `createFaction(id, displayName, color, level)` - 创建并注册阵营，持久化到世界存档
- `removeFaction(id)` - 删除阵营定义（仅内存）
- `removeFaction(id, level)` - 删除阵营定义，并清除指向它的全部实体绑定
- `mergeFactions(intoId, fromId, level)` - 将一个阵营并入另一个：成员改绑、关系覆盖归并，被解散的阵营随后删除。返回迁移的成员数，无法执行时返回 -1
- `getFaction(id)` - 按 ID 获取阵营定义
- `getAllFactions()` - 获取全部已注册阵营
- `joinFaction(entity, factionId)` - 将实体绑定到阵营
- `leaveFaction(entity)` - 将实体移出所属阵营
- `getEntityFaction(entity)` - 获取实体所属阵营 ID（无阵营返回 null；驯服动物回退为主人的阵营）
- `areSameFaction(a, b)` - 判断两个实体是否属于同一阵营
- `isFriendly(a, b)` - 完整判断友方关系：ECA 同阵营/友好阵营、原版计分板同盟或宠物主从同盟（不含创造、旁观和 ECA 无敌）
- `getFactionMembers(level, factionId)` - 将阵营成员表解析为指定维度中的已加载实体
- `kickAllFromFaction(factionId, level)` - 全局移出全部显式成员，包括未加载和其他维度中的成员
- `setFactionRelation(a, b, relation)` - 设置阵营 A 对阵营 B 的关系（仅内存）
- `setFactionRelation(a, b, relation, level)` - 设置阵营 A 对 B 的关系并持久化
- `getFactionRelation(a, b)` - 查询 A 对 B 的显式关系（无覆盖返回 null）
- `getEffectiveFactionRelation(source, target)` - 解析两个实体之间的有效关系
- `canHarm(source, target)` - 判断阵营规则是否允许 source 攻击 target
- `canTarget(source, target)` - 判断完整阵营与保护规则是否允许 source 主动锁定 target
- `alertFactionMembers(factionId, attacker, victim, level)` - 让附近无目标的同阵营盟友反击攻击者
- `joinFaction(uuid, typeId, isPlayer, factionId, level)` - 按 UUID 将实体加入阵营，无需实体在线或已加载
- `leaveFaction(uuid, level)` - 按 UUID 将实体移出所属阵营，无需实体在线
- `getEntityFaction(uuid)` - 按 UUID 查询所属阵营（纯索引查询；不含需要实体才能解析的宠物继承）
- `isFactionMember(uuid, factionId)` - 判断指定 UUID 是否为该阵营成员
- `getFactionMemberRecords(factionId)` - 获取阵营全部成员记录（UUID + 实体类型），无需加载实体
- `getFactionMemberUuids(factionId)` - 获取阵营全部成员 UUID，无需加载实体
- `getFactionMembersByType(factionId, typeId)` - 按实体类型筛选阵营成员，无需加载实体
- `getFactionMemberCount(factionId)` - 获取阵营成员数量，无需加载实体
- `resolveFactionMembers(factionId, level)` - 将阵营成员解析为该维度中实际存在的实体
- `setFactionLeader(factionId, leader, level)` - 设置阵营首领（若未入营则自动加入）
- `clearFactionLeader(factionId, level)` - 清除阵营首领，原首领仍保留成员身份
- `getFactionLeader(factionId)` - 获取阵营首领记录，无需加载实体
- `getFactionLeaderUuid(factionId)` - 获取阵营首领的 UUID
- `resolveFactionLeader(factionId, server)` - 将阵营首领解析为实体，跨全部维度搜索
- `isFactionLeader(entity)` - 判断实体是否为任意阵营的首领
- `getFactionByLeader(uuid)` - 反查某实体担任首领的阵营
- `startRaid(level, pos, raidId)` - 在目标结构内发起袭击（中心取自结构包围盒）
- `startRaidAt(level, center, raidId)` - 以指定坐标为中心发起袭击，跳过结构查询
- `endRaid(level, raid, victory)` - 结束袭击并清除全部仍存活的袭击者
- `endRaid(level, raidId, victory)` - 按实例 ID 结束袭击并清除全部仍存活的袭击者
- `getRaid(level, raidId)` - 按实例 ID 获取活跃袭击
- `getActiveRaids(level)` - 获取该世界中的全部活跃袭击
- `getNearestRaid(level, pos, maxDistance)` - 获取指定范围内最近的活跃袭击
- `getAllRaidDefinitions()` - 获取全部已注册的袭击定义

以下是一个简易示例：

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
EcaAPI.hurt(entity, damageSource, 50.0f);
EcaAPI.hurt(entity, player, 50.0f);  // 伤害源由攻击者推导
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
EcaAPI.memoryRemove(entity, Entity.RemovalReason.CHANGED_DIMENSION);  // 通过 LWJGL 的内部 Unsafe 实例清除实体
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
EcaAPI.enableAllReturn(entity);  // 玩家针对盔甲及主副手装备所属模组，其他实体针对自身所属模组
EcaAPI.setGlobalAllReturn(true);  // 对所有非白名单 mod 启用
boolean enabled = EcaAPI.isAllReturnEnabled();
EcaAPI.disableAllReturn();  // 关闭并清除全部 AllReturn

// 白名单 — AllReturn 级别（跳过 AllReturn，防御性 Hook 仍生效）
EcaAPI.addAllReturnWhitelist("com.yourmod.");
boolean removed = EcaAPI.removeAllReturnWhitelist("com.yourmod.");
boolean isProtected = EcaAPI.isAllReturnWhitelisted("com.yourmod.YourClass");

// 白名单 — 转换级别（跳过全部 ECA 转换，包括防御性 Hook）
EcaAPI.addTransformWhitelist("com.yourmod.");
boolean removedTransform = EcaAPI.removeTransformWhitelist("com.yourmod.");
boolean isFullyProtected = EcaAPI.isTransformWhitelisted("com.yourmod.YourClass");

Set<String> allWhitelisted = EcaAPI.getAllWhitelistedPackages();

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

// 线程复活
EcaAPI.startResurrection();
EcaAPI.addResurrectionTarget(entity);
boolean tracked = EcaAPI.isResurrectionTracked(entity);
EcaAPI.removeResurrectionTarget(entity);
EcaAPI.setResurrectionPollInterval(50);
EcaAPI.stopResurrection();

// 阵营
EcaAPI.createFaction("undead_legion", "亡灵军团", 0xFF884400, serverLevel);
EcaAPI.setFactionRelation("undead_legion", "village_guard", FactionRelation.HOSTILE, serverLevel);
EcaAPI.joinFaction(entity, "undead_legion");
String factionId = EcaAPI.getEntityFaction(entity);
boolean sameFaction = EcaAPI.areSameFaction(entityA, entityB);
boolean friendly = EcaAPI.isFriendly(entityA, entityB);
boolean mayAttack = EcaAPI.canHarm(attacker, target);
boolean mayTarget = EcaAPI.canTarget(attacker, target);
FactionRelation relation = EcaAPI.getEffectiveFactionRelation(attacker, target);
EcaAPI.leaveFaction(entity);

// 袭击
RaidInstance raid = EcaAPI.startRaid(serverLevel, pos, "undead_siege");        // 中心取自目标结构
RaidInstance forced = EcaAPI.startRaidAt(serverLevel, center, "undead_siege"); // 指定中心，跳过结构查询
List<RaidInstance> active = EcaAPI.getActiveRaids(serverLevel);
RaidInstance nearest = EcaAPI.getNearestRaid(serverLevel, pos, 128.0);
EcaAPI.endRaid(serverLevel, raid, true);                                       // 以胜利结束并清除存活袭击者
```

### 实体扩展

本 Mod 还提供了一个可自定义的实体类型扩展功能，用于为你的实体增加一些特殊的视觉效果。你需要创建继承 `EntityExtension` 的子类，并在类上标注 `@RegisterEntityExtension` 进行注册扩展。以下是一个快速上手的示例：

自定义 Boss 血条可使用 `enableBossBar()`、`shouldShowBossBar(LivingEntity)` 和 `bossBarExtension()`。在 `BossBarExtension` 中，`showValueText()` 用于开启居中的“当前值/最大值”文本；覆写 `getDisplayCurrentValue(LivingEntity)` 与 `getDisplayMaxValue(LivingEntity)` 可以返回自定义显示数值，默认分别使用实体当前生命值和最大生命值。

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
    public String getFactionId() {
        return "undead_legion";  // 该类型实体自动加入的阵营，默认 null 表示不加入。阵营必须已通过 @RegisterFaction 或 EcaAPI.createFaction() 注册，否则绑定会被拒绝并记录日志
    }

    @Override
    public boolean enableBossBar() {
        return true;  // 血条接管总开关：是否由 ECA 清除实体原生血条并改用 ECA 的。默认 false——不启用时 ECA 完全不碰实体自带血条。bossBarExtension() / shouldShowBossBar() / 自定义血量覆盖均需此项为 true 才生效
    }

    @Override
    public boolean shouldShowBossBar(LivingEntity entity) {
        return entity != null && entity.isAlive();  // Boss 血条显示条件（仅在 enableBossBar() 为 true 时生效）
    }

    @Override
    public boolean enableCustomHealthOverride() {
        return true;  // 若为 true，ECA 自定义血条当前血量将从 getCustomHealthValue() 读取，而非原版 getHealth()
    }

    @Override
    public Number getCustomHealthValue(LivingEntity entity) {
        return entity.getEntityData().get(YOUR_CUSTOM_HEALTH_DATA);  // 实际用作当前血量的值（如实体数据、自定义字段等），为 null 则回退到原版
    }

    @Override
    public boolean enableCustomMaxHealthOverride() {
        return true;  // 若为 true，ECA 自定义血条最大血量将从 getCustomMaxHealthValue() 读取，而非原版 getMaxHealth()
    }

    @Override
    public Number getCustomMaxHealthValue(LivingEntity entity) {
        return entity.getEntityData().get(YOUR_CUSTOM_MAX_HEALTH_DATA);  // 实际用作最大血量的值（如实体数据、自定义字段等），为 null 则回退到原版
    }

    // 自定义 Boss 血条（需 enableBossBar() = true）
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
            @Override public float getFrameAlpha() { return 1.0f; }  // 外框不透明度，0.0~1.0（默认 1.0）
            @Override public float getFillAlpha() { return 1.0f; }  // 填充不透明度，0.0~1.0（默认 1.0）
            @Override public boolean showValueText() { return true; }  // 在血条中央显示当前值/最大值
            @Override public Number getDisplayCurrentValue(LivingEntity entity) { return entity.getHealth(); }  // 可覆写为自定义当前值来源
            @Override public Number getDisplayMaxValue(LivingEntity entity) { return entity.getMaxHealth(); }  // 可覆写为自定义最大值来源
        };
    }

    // 实体额外渲染层
    @Override
    public EntityLayerExtension entityLayerExtension() {
        return new EntityLayerExtension() {
            @Override public boolean enabled() { return true; }  // 启用渲染层
            @Override public List<ShaderMaskPass> getShaderPasses() {
                ResourceLocation mask = texture("entity/boss_mask.png");
                return List.of(
                    ShaderMaskPass.masked(ArcaneRenderTypes.BOSS_LAYER, mask, 0x000000, 0.05f, 0.8f),
                    ShaderMaskPass.masked(VolcanoRenderTypes.BOSS_LAYER, mask, 0xFF0000, 0.05f, 0.8f),
                    ShaderMaskPass.masked(OceanRenderTypes.BOSS_LAYER, mask, 0x0000FF, 0.05f, 0.8f)
                );
            }
            @Override public boolean isGlow() { return true; }  // 额外渲染层发光
            @Override public boolean isHurtOverlay() { return true; }  // 在该层上显示受伤覆盖效果
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

    // 条件开关：迷雾/天空盒/音乐仅在对应 shouldEnableXxx 返回 true 时激活；框架约每秒按该维度主实体重新检查一次
    @Override
    public boolean shouldEnableFog(LivingEntity entity) {
        return entity.getHealth() < entity.getMaxHealth() * 0.5f;  // 示例：实体血量低于 50% 时触发迷雾
    }

    @Override
    public boolean shouldEnableSkybox(LivingEntity entity) {
        return true;  // 天空盒触发条件
    }

    @Override
    public boolean shouldEnableMusic(LivingEntity entity) {
        return true;  // 战斗音乐触发条件
    }

    /*
     * 条件切换 — 重写带实体参数的重载（五个扩展方法都有：bossBar / entityLayer / globalFog / globalSkybox / combatMusic），按实体状态返回不同的扩展对象。
     * ECA 只在 entity 非 null 时调用带参重载（其余走无参版），重写抛出的异常会被捕获并退回无参版，写错不会崩客户端。
     * 全局效果约每秒按维度主实体重新求值，实例效果（Boss 血条/渲染层）每帧按实体重新求值。
     */
    @Override
    public GlobalSkyboxExtension globalSkyboxExtension(LivingEntity entity) {
        if (entity.getHealth() < entity.getMaxHealth() * 0.5f) {
            return phase2Skybox;  // 血量低于 50%：切换到你定义的另一个 GlobalSkyboxExtension
        }
        return globalSkyboxExtension();  // 默认：上面的无参天空盒
    }
}
```

### ECA Blender Model

ECA Blender Model 使得您可以通过使用 ECA 读取原生 `.blend` 文件或导出的 GLB 文件，并将其用于实体模型、动画的渲染。以下为一个参考实例：

#### 资源准备与模型配置

```text
assets/example/eca/blender/guardian/
  definition.json
  model.blend
  textures/body.png
```

```json
{
  "model": "model.blend",
  "scale": 1.0,
  "translation": [0.0, 0.0, 0.0],
  "rotation": [0.0, 0.0, 0.0],
  "default_animation": "Idle",
  "loop": true,
  "hidden_nodes": ["PresentationGround"],
  "node_time_source": "entity",
  "parameters": {}
}
```

模型 ID 由资源命名空间和模型目录相对于 `blender/` 的路径组成。例如，`assets/example/eca/blender/guardian/` 对应 `example:guardian`。您也可以在 `blender` 文件夹下使用子文件夹分类存放模型：放在 `assets/example/eca/blender/bosses/guardian/` 中的模型，其 ID 为 `example:bosses/guardian`。

| 字段 | 含义 |
| --- | --- |
| `model` | 相对模型文件名，默认 `model.glb`；原生文件应显式指定 `model.blend` |
| `scale` | 整体缩放，默认 1 |
| `translation`／`rotation` | XYZ 三分量偏移／角度制旋转，默认零 |
| `default_animation`／`loop` | 回退动作／循环，默认无动作／true |
| `hidden_nodes` | 隐藏指定对象／节点及其子级 |
| `collection`／`object` | 原生 .blend 集合及子集合／单对象选择，不能同时配置；默认当前场景主集合 |
| `node_time_source` | 原生节点时钟，`entity`（默认）或 `animation` |
| `parameters` | 按 socket identifier 配置的原生几何节点组标量输入 |

动画名称填写 Blender 中的 Action 名称。使用 `__scene__` 可按场景帧范围播放各对象当前绑定的动作。示例中的 `Idle`、`Walk` 等名称需要替换为您的模型实际使用的名称。

纹理可打包进模型，也可放在模型目录下，通过 `//textures/body.png` 这样的相对路径引用。使用 GLB 时，将导出的 `model.glb` 放入同一目录，并修改 `model` 字段即可。

模型朝向、大小或位置不合适时，可调整 `rotation`、`scale` 和 `translation`。修改资源后，通过游戏资源重载重新加载模型。

#### 通过实体扩展绑定

您可以通过实体扩展来为已经存在的实体启用 ECA Blender Model：

```java
package example;

import net.eca.api.RegisterEntityExtension;
import net.eca.util.entity_extension.BlenderModelExtension;
import net.eca.util.entity_extension.BlenderRenderMode;
import net.eca.util.entity_extension.BossBarExtension;
import net.eca.util.entity_extension.EntityExtension;
import net.eca.util.entity_extension.EntityExtensionManager;
import net.eca.util.entity_extension.EntityLayerExtension;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@RegisterEntityExtension
public final class GuardianExtension extends EntityExtension {
    static {
        EntityExtensionManager.register(new GuardianExtension());
    }

    private GuardianExtension() {
        super(EntityType.GUARDIAN, 0);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public BossBarExtension bossBarExtension() { return null; }

    @Override
    @OnlyIn(Dist.CLIENT)
    public EntityLayerExtension entityLayerExtension() { return null; }

    @Override
    @OnlyIn(Dist.CLIENT)
    protected String getModId() { return "example"; }

    @Override
    @OnlyIn(Dist.CLIENT)
    public BlenderModelExtension blenderModelExtension() { return Client.MODEL; }

    @OnlyIn(Dist.CLIENT)
    private static final class Client {
        private static final BlenderModelExtension MODEL = new BlenderModelExtension() {
            @Override
            public ResourceLocation modelId() {
                return new ResourceLocation("example", "guardian");
            }

            @Override
            public BlenderRenderMode renderMode() {
                return BlenderRenderMode.REPLACE;
            }
        };
    }
}
```

示例将模型绑定到守卫者。将 `EntityType.GUARDIAN` 换为目标实体类型，`example:guardian` 换为您的模型 ID；目标实体已有扩展时，在已有扩展中添加模型配置。

`BlenderRenderMode.REPLACE` 替换实体模型，`ADDITIVE` 则与原模型一起显示。替换模式会同时隐藏原模型的附加层，例如盔甲和手持物品；模型加载失败时保留原模型。

通过覆写 `shouldRender(entity)`、`scale(entity)`、`offsetX/Y/Z(entity)` 等方法，可以按实体状态调整显示效果。`blenderModelExtension(LivingEntity)` 可用于为同一种实体的不同实例选择模型。

#### 动画控制器

生命周期控制器负责待机、移动、受伤和死亡动作；技能控制器用于管理攻击、施法等需要主动触发的动作。以下 `GuardianAnimations` 为模型配置四种生命周期动作，以及一个带伤害标记的重击技能：

```java
package example;

import net.eca.blender.animation.controller.BlenderAnimationClip;
import net.eca.blender.animation.controller.BlenderControllerContext;
import net.eca.blender.animation.controller.BlenderControllerSet;
import net.eca.blender.animation.controller.BlenderSkillDefinition;
import net.eca.blender.animation.controller.BlenderSkillMarker;
import net.eca.blender.animation.controller.DefaultBlenderLifecycleController;
import net.eca.blender.animation.controller.DefaultBlenderSkillController;

import java.util.List;

public final class GuardianAnimations {
    public static final BlenderControllerSet CONTROLLERS = new BlenderControllerSet(
        new DefaultBlenderLifecycleController(
            BlenderAnimationClip.looping("Idle"),
            BlenderAnimationClip.looping("Walk"),
            BlenderAnimationClip.once("Hurt", 8),
            BlenderAnimationClip.once("Death", 20)
        ),
        new DefaultBlenderSkillController(List.of(
            new BlenderSkillDefinition(
                "heavy_attack",
                BlenderAnimationClip.once("Attack_Heavy", 30),
                10, true, true, false,
                List.of(new BlenderSkillMarker("hit", 12))
            )
        )) {
            @Override
            public void onSkillMarker(BlenderControllerContext context, BlenderSkillMarker marker) {
                if ("hit".equals(marker.name())) {
                    // 在这里执行伤害判定，context.entity() 为释放技能的实体。
                }
            }
        }
    );

    private GuardianAnimations() { }
}
```

在前面的 `GuardianExtension` 中返回该配置：

```java
import net.eca.blender.animation.controller.BlenderControllerSet;
import net.minecraft.world.entity.LivingEntity;

// GuardianExtension 中的方法
@Override
public BlenderControllerSet blenderAnimationControllers(LivingEntity entity) {
    return GuardianAnimations.CONTROLLERS;
}
```

模型配置只在客户端使用，动画控制器则在服务端运行。请保留示例中的客户端标注，并复用同一份 `CONTROLLERS` 配置；每次返回新控制器会中断当前技能。

`looping("Walk")` 表示循环播放，`once("Hurt", 8)` 表示播放 8 tick 的受伤动作。20 tick 为一秒，时长需要与模型动作对应。无需某个生命周期动作时可填 `null`。默认移动判定使用实体水平速度，也可以自行覆写。

技能 ID `heavy_attack` 用于代码调用，`Attack_Heavy` 是模型中的动作名称。示例技能持续 30 tick，在第 12 tick 调用 `hit` 标记回调。

| 技能参数 | 示例值 | 含义 |
| --- | --- | --- |
| `priority` | `10` | 技能优先级 |
| `interruptible` | `true` | 允许其他技能打断 |
| `interruptOnHurt` | `true` | 允许受伤动作打断 |
| `restartable` | `false` | 不允许再次触发同一技能来重启动作 |
| `markers` | `hit: 12` | 动作第 12 tick 的回调标记 |

默认情况下，新技能的优先级不低于当前技能且当前技能允许打断时，才能切换。受伤打断还需要配置受伤动作。标记名称应唯一，时间在技能时长内；速度改变时，标记随动画进度一起加速或减速。

#### 技能回调与自定义控制器

您可以继承 `DefaultBlenderLifecycleController` 和 `DefaultBlenderSkillController`，覆写其中的方法；也可以直接实现 `BlenderLifecycleController`、`BlenderSkillController` 接口。

例如，修改移动判定，或只允许更高优先级的技能打断当前技能：

```java
import net.eca.blender.animation.controller.BlenderControllerContext;
import net.eca.blender.animation.controller.BlenderSkillDefinition;

// 在 DefaultBlenderLifecycleController 子类中调整移动判定。
@Override
public boolean isMoving(BlenderControllerContext context) {
    return context.entity().getDeltaMovement().horizontalDistanceSqr() > 0.0004;
}

// 在 DefaultBlenderSkillController 子类中只允许更高优先级的技能打断。
@Override
public boolean canInterrupt(BlenderControllerContext context,
                            BlenderSkillDefinition current,
                            BlenderSkillDefinition incoming) {
    return current.interruptible() && incoming.priority() > current.priority();
}
```

技能控制器提供以下回调，均在服务端执行：

| 回调 | 用途 |
| --- | --- |
| `onSkillStarted(context)` | 技能开始时处理消耗、准备效果等逻辑 |
| `onSkillMarker(context, marker)` | 在指定时间执行伤害判定、生成弹射物等逻辑 |
| `onSkillCompleted(context)` | 技能正常结束 |
| `onSkillCancelled(context, reason)` | 技能被打断或主动取消，按原因清理效果 |

`context.entity()` 是释放技能的实体，`context.skill()` 是技能定义，`context.elapsedTicks()` 是当前动画进度。取消不会触发完成回调。每个标记在一次技能执行中只触发一次；0 tick 标记在开始后触发，结束时刻的标记先于完成回调。

回调中不能立即重新操作同一实体的动画。需要连招时，可记录下一招，在后续服务端 tick 中触发。控制器可能被多个实体共用，请将实体专属的技能数据保存在实体或您自己的数据管理对象中。

#### 播放与技能 API

在服务端逻辑中使用 `EcaAPI` 触发技能，或直接播放模型动作：

```java
import net.eca.api.EcaAPI;
import net.eca.blender.animation.controller.BlenderControllerContext;

// 以下为服务端业务方法中的调用示例，各操作按需调用。
EcaAPI.triggerBlenderSkill(entity, "heavy_attack");

boolean attacking = EcaAPI.isBlenderSkillActive(entity);
BlenderControllerContext execution = EcaAPI.getBlenderSkillExecution(entity);

EcaAPI.cancelBlenderSkill(entity);

// 直接播放资源中的动作，不经过技能定义。
EcaAPI.playAnimation(entity, "Attack_Heavy");
EcaAPI.playAnimation(entity, "Walk", 1.25f, true);
EcaAPI.pauseAnimation(entity);
EcaAPI.resumeAnimation(entity);
EcaAPI.stopAnimation(entity);
```

`triggerBlenderSkill` 按技能定义和打断规则执行；`cancelBlenderSkill` 主动取消当前技能。`getBlenderSkillExecution` 在没有技能执行时返回 `null`。触发动作应放在攻击、施法等事件中，重复调用会尝试重新触发。

直接调用 `playAnimation` 会中断当前技能，并接管动画播放。同名动作再次播放会从头开始，非循环动作播完后保持最后一帧，直到停止或切换。`stopAnimation` 将播放交还给控制器；没有控制器时，使用模型绑定选择的动作或 `default_animation`。

`pauseAnimation` 和 `resumeAnimation` 暂停、恢复动作及技能计时。暂停期间，受伤或死亡仍可引起动作切换。`isAnimationPlaying(entity)` 查询当前是否存在播放状态，暂停和末尾保持也计入；判断技能是否结束应使用 `isBlenderSkillActive`。

这些 API 需在逻辑服务端线程调用。死亡动作优先于其他动作，进入死亡状态后不再接受手动播放操作。死亡动画不会推迟实体移除，因此时长应与实体死亡流程匹配。

#### 节点动画与参数

通过 `node_time_source` 选择几何节点和材质时间驱动器使用的时间：

| 值 | 效果 |
| --- | --- |
| `entity`（默认） | 节点持续播放，不随动作循环、切换或暂停而重置 |
| `animation` | 节点跟随当前动作的时间、速度和暂停状态 |

持续旋转的光环等效果适合使用 `entity`。该时间随客户端实体年龄计算，实体卸载后重新出现时可能重置。需要按实体选择时间来源时，可覆写 `BlenderModelExtension.nodeTimeSource(entity, resourceDefault)`。

几何节点组的数值输入可以写在 `definition.json` 的 `parameters` 中，例如 `"parameters": {"Socket_2": 1.0}`。也可以在 `BlenderModelExtension` 中动态返回：

```java
import net.minecraft.world.entity.LivingEntity;
import java.util.Map;

// BlenderModelExtension 中的方法；Socket_2 替换为实际输入标识符。
@Override
public Map<String, Float> nodeParameters(LivingEntity entity) {
    return Map.of("Socket_2", 1.0f);
}
```

键名使用 Blender 节点组输入的 socket identifier，而非界面显示名称。当前支持标量输入，方法返回值覆盖资源配置。参数在客户端读取；如果数值来自服务端业务，需要由您的模组同步。

#### 直接注册模型与控制器

不使用实体扩展时，可以分别注册模型绑定和动画控制器。以下两个类沿用前面的 `GuardianAnimations`，`example` 替换为您的模组 ID。

模型在客户端初始化时注册：

```java
package example;

import net.eca.blender.client.entity.BlenderEntityBindings;
import net.eca.blender.entity.BlenderEntityBinding;
import net.eca.blender.entity.BlenderRenderPolicy;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = "example", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GuardianClientBindings {
    private static final BlenderEntityBinding BINDING = new BlenderEntityBinding() {
        @Override
        public ResourceLocation modelId() {
            return new ResourceLocation("example", "guardian");
        }

        @Override
        public BlenderRenderPolicy renderPolicy() {
            return BlenderRenderPolicy.REPLACE;
        }
    };

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> BlenderEntityBindings.register(EntityType.GUARDIAN, BINDING));
    }
}
```

动画控制器在公共初始化阶段注册：

```java
package example;

import net.eca.blender.animation.BlenderControllers;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod.EventBusSubscriber(modid = "example", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GuardianControllerBindings {
    @SubscribeEvent
    public static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() ->
            BlenderControllers.register(EntityType.GUARDIAN, GuardianAnimations.CONTROLLERS));
    }
}
```

`BlenderEntityBindings` 负责模型绑定，`BlenderControllers` 负责动画控制器。同一类型重复注册返回 `false`，显式注册的配置优先于实体扩展配置。取消注册使用各自的 `unregister(type, binding)` 或 `unregister(type, controllers)`。

#### 支持范围与使用限制

以下为原生 `.blend` 的支持范围，适用于 Blender 5.2.1 对应的文件格式，目前仍为实验性功能。GLB 使用导出后的模型和动画，不读取 Blender 节点网络。

##### 模型、骨骼与动画

| 功能 | 支持范围与使用条件 |
| --- | --- |
| 网格 | 顶点、UV、材质槽、多边形三角化、对象层级与变换；多套 UV 暂不支持选择 |
| 骨骼 | 基础骨骼父子关系与默认继承方式，支持骨骼父级对象；不支持分段骨骼变形 |
| Armature | 单个修改器，使用顶点组权重；每顶点最多四个有效骨骼权重，不支持骨骼包络和 Preserve Volume |
| 动作 | 对象与姿态骨骼的位置、旋转、缩放；支持欧拉角、四元数和轴角旋转 |
| 关键帧 | Constant、Linear、Bezier 插值；曲线范围外保持端点值，不支持线性外推和曲线修改器 |
| Action | 单层、单个关键帧片段，无片段时间偏移，层影响为 1；不支持层混合、NLA 和 Delta Transform |
| Bevel | 凸边和凸平面，Edges／Offset／Angle，圆形轮廓 0.5；需启用 Loop Slide，不支持自定义轮廓、材质覆盖、边标记和 Harden Normals |

几何节点和 Bevel 必须位于 Armature 之前。Bevel 不支持带蒙皮权重的网格，实例需先经过 Realize Instances。倒角发生重叠时，请启用 Clamp Overlap 或减小宽度。

##### 几何节点

| 节点或功能 | 支持范围与使用条件 |
| --- | --- |
| 节点组 | Group Input、Group Output、嵌套节点组、Reroute；组参数支持数值输入 |
| 输入与转换 | Position、Normal、Index、Scene Time，数值／向量／布尔输入，Combine／Separate XYZ，Euler to Rotation、Rotation to Euler |
| Transform | Translation／Rotation／Scale 分量模式，不支持矩阵模式 |
| Set Position | 网格顶点和点位置；不支持曲线，实例需先实体化 |
| Join Geometry | 合并受支持的几何输入 |
| Instance on Points | 按点放置实例，支持选择、旋转和缩放；不支持 Pick Instance，曲线需先转换 |
| Realize Instances | 完整实体化；不支持按 Selection 或 Depth 部分实体化 |
| Set Material | 固定材质引用，Selection 为统一值；不支持按点或面分别选择材质 |
| Curve Circle | Radius 模式 |
| Curve to Points | Count 模式，仅 Points 输出；不支持 Length 模式及 Tangent／Rotation 输出 |
| Curve to Mesh | 闭合平面折线轮廓和统一 Scale；不支持开放曲线封口，Fill Caps 对闭合曲线不起作用 |
| Icosphere | Mesh 输出，不支持 UV Map 输出 |

曲线目前限于 Curve Circle 生成及变换后的闭合折线，不支持 Bezier／NURBS 曲线。半径和 Curve to Mesh 的 Scale 必须为正数。

几何节点与材质节点中的数学运算支持：

| 节点 | 支持的运算 |
| --- | --- |
| Math | 加减乘除、乘加、幂、对数、指数、平方根、平方根倒数、绝对值、符号、最小／最大值、小于／大于／比较、取整、向下／向上取整、截断、小数部分、取模、向下取模、吸附、正弦／余弦／正切及反函数、Atan2、角度／弧度转换 |
| Vector Math | 加减乘除、叉积、点积、距离、长度、缩放、归一化、向下／向上取整、绝对值、最小／最大值 |

##### 材质与纹理节点

| 节点或功能 | 支持范围与使用条件 |
| --- | --- |
| 基础输入 | Value、RGB、Combine／Separate XYZ，节点组与 Reroute |
| Texture Coordinate | UV、Object、Generated 输出；不支持指定其他对象作为坐标来源 |
| Mapping | Point、Vector 模式 |
| Mix／Mix Color | 普通 Mix 混合；Float／Vector／Color 类型使用统一系数，不支持其他混合模式或逐分量系数 |
| Color Ramp | RGB 颜色模式，Linear／Ease／Constant 插值 |
| Image Texture | 平面投影，Linear／Closest 插值，Repeat／Extend／Clip 扩展 |
| Gradient Texture | Linear、Quadratic、Easing、Diagonal、Radial、Quadratic Sphere、Spherical |
| Noise Texture | 3D／4D fBM，Fac／Color 输出；支持 Scale、Detail、Roughness、Lacunarity、Distortion、Normalize，4D 支持 W |
| Emission | Color、Strength |
| Diffuse | 基础 Color；不支持连接 Roughness 或 Normal 输入 |
| Principled BSDF | Base Color、Alpha、Emission Color／Strength、Metallic、Roughness；不支持法线输入、次表面、透射、涂层和 Sheen |
| Mix Shader／Add Shader | 混合或叠加上述受支持的表面着色器 |

纹理可以打包进模型，或使用模型目录下的相对路径；外部绝对路径和上级目录路径不可用。图片需使用 Minecraft 可读取的格式。

##### 材质时间驱动器

支持在材质节点**未连线的浮点输入**上使用以下驱动表达式：

| 表达式 | 用法 |
| --- | --- |
| `frame` | 使用当前节点时间对应的帧数 |
| `frame / 常量` | 将帧数除以固定数值，例如 `frame / 12.0`；除数不能为零 |

在 Blender 中使用表达式驱动器，保留默认驱动曲线，不添加自定义驱动变量或曲线修改器。目前仅支持上述表达式，不支持 `frame * 2`、`sin(frame)` 等其他运算或函数。驱动曲线也可为空；自行修改曲线时，只支持两点恒等映射，即输出与输入相同。

驱动器使用 `node_time_source` 选择的节点时间。这项支持仅用于材质节点输入，不适用于对象变换或几何节点输入。

##### 其他使用限制

目前不支持约束、多轨动画混合、形态键、外部链接库、集合实例、节点树关键帧、几何节点模拟／重复区域，以及细分、布料、流体等修改器。盔甲和手持物品的骨骼挂点适配需要另行实现。

材质使用 Minecraft 光照，透明度、反射和金属表面的效果可能与 Blender 不同。Blender 灯光、体积、置换和色彩管理不受支持。

复杂模型和动态节点会增加内存占用与渲染开销，请根据目标设备自行评估。ECA 不设置资源预算，但资源仍需满足文件格式、渲染器和硬件的实际要求。

加载失败时，可在日志中查看模型 ID 和对应节点的错误信息，修正后重载资源。动画名称和时长需要与模型对应。

### 方块扩展

方块扩展会在原方块模型之上附加着色器层，不会替换正常模型。普通烘焙方块和下落方块使用 `getBlockShaderPasses()` 返回的 BLOCK profile pass；GeckoLib 方块实体使用 `getGeoShaderPasses(texture)` 返回的 NEW_ENTITY profile pass。逻辑预设 ID 仍会为两个 profile 提供默认 RenderType。

```java
@RegisterBlockExtension
public final class AmethystBlockExtension extends BlockExtension {
    static {
        BlockExtensionManager.register(new AmethystBlockExtension());
    }

    private AmethystBlockExtension() {
        super(Blocks.AMETHYST_BLOCK);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public ResourceLocation getShaderPresetId() {
        return new ResourceLocation("example", "amethyst_glow");
    }

    @Override
    public List<ShaderMaskPass> getBlockShaderPasses() {
        ResourceLocation mask = new ResourceLocation("example", "textures/block/amethyst_mask.png");
        return List.of(
            ShaderMaskPass.masked(getBlockRenderType(), mask, 0x000000, 0.05f, 1.0f),
            ShaderMaskPass.masked(CustomRenderTypes.BLOCK_FIRE, mask, 0xFF0000, 0.05f, 1.0f)
        );
    }
}
```

烘焙方块虽然使用图集，但外部遮罩会自动获得按 sprite 转换后的局部 UV。Geo 遮罩直接使用模型纹理的 UV 布局，并与 `overlayGeoBones()` 的骨骼范围取交集。普通世界方块按 section 建立稀疏索引并批量绘制覆盖层；下落方块与 GeckoLib 方块实体会自动接入。方块物品仍属于 `ItemExtension`。旧颜色键和单遮罩 getter 已标记为废弃兼容入口。

### 物品扩展

你可以创建物品扩展为指定物品附加着色器渲染效果：首先创建继承 `ItemExtension` 的子类，并在类上标注 `@RegisterItemExtension` 即可注册。

```java
import net.eca.api.RegisterItemExtension;
import net.eca.client.render.ArcaneRenderTypes;
import net.eca.client.render.ShaderMaskPass;
import net.eca.client.render.StarlightRenderTypes;
import net.eca.client.render.VolcanoRenderTypes;
import net.eca.util.ItemUtil;
import net.eca.util.item_extension.EcaTooltipLine;
import net.eca.util.item_extension.ItemExtension;
import net.eca.util.item_extension.ItemExtensionManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

@RegisterItemExtension
public class DiamondSwordExtension extends ItemExtension {

    static {
        ItemExtensionManager.register(new DiamondSwordExtension());
    }

    public DiamondSwordExtension() {
        super(Items.DIAMOND_SWORD);  // 目标物品
    }

    @Override
    protected String getModId() {
        return "your_mod_id";
    }

    @Override
    public boolean enabled() {
        return true;  // 全局总开关，返回 false 则完全禁用该扩展
    }

    @Override
    public boolean shouldRender(ItemStack stack) {
        return true;  // 单个物品堆的生效条件（例如检查 NBT、附魔、自定义名称）
    }

    @Override
    public List<ShaderMaskPass> getShaderPasses() {
        ResourceLocation mask = texture("item/diamond_sword_mask.png");
        return List.of(
            ShaderMaskPass.masked(ArcaneRenderTypes.ITEM, mask, 0x000000, 0.05f, 0.8f),
            ShaderMaskPass.masked(VolcanoRenderTypes.ITEM, mask, 0xFF0000, 0.05f, 0.8f),
            ShaderMaskPass.masked(StarlightRenderTypes.ITEM, mask, 0x0000FF, 0.05f, 0.8f)
        );
    }

    @Override
    public List<EcaTooltipLine> getTooltipLines(ItemStack stack, TooltipFlag flag) {
        return List.of(
            EcaTooltipLine.head(ItemUtil.of("Starlight Forged")
                .addEffect.GRADIENT(0x7C4DFF, 0x00E5FF)
                .addEffect.BOLD()),
            EcaTooltipLine.body(ItemUtil.of("Arcane resonance: unstable")
                .addEffect.SOLID(0xFFAA00)
                .addEffect.ITALIC()),
            EcaTooltipLine.tail(ItemUtil.of("Hold Shift for hidden lore")
                .addEffect.RAINBOW())
        );
    }
}
```

结构化 tooltip 行可以分别决定自己的插入位置：

- `EcaTooltipLine.head(...)`：插入到物品名下方。
- `EcaTooltipLine.body(...)`：插入到主体 tooltip 区域；存在高级物品 ID、NBT 或 disabled 提示时，会尽量放在这些行之前。
- `EcaTooltipLine.tail(...)`：插入到 tooltip 末尾。

每一行都可以传入普通 `Component`，也可以传入 `ItemUtil.of(...)` 创建的 `EcaText`，因此 tooltip 支持和物品名相同的富文本效果：渐变、彩虹、纯色、闪烁、乱码、粗体、斜体、下划线和删除线。旧的 `appendTooltip(ItemStack, TooltipFlag, List<Component>)` 仍然保留，适合需要直接修改最终 tooltip 列表的高级用法。

物品遮罩 pass 会自动使用 sprite 局部 UV。`ShaderMaskPass.masked(...)` 采样外部遮罩贴图，`ShaderMaskPass.baseTexture(...)` 则直接从物品贴图选择颜色。旧 `getRenderType()`、颜色键和单遮罩 getter 已标记为废弃兼容入口。

注意：和实体扩展一样，每个物品只能有一个扩展，重复注册会被拒绝并输出错误日志。实体层扩展（`EntityLayerExtension.getAlpha()`，默认 0.5）和物品扩展（`ItemExtension.getAlpha()`，默认 1.0）均支持调整着色器叠加层的透明度。


### 着色器遮罩 Pass

实体、物品和方块着色器覆盖层共用同一套 `ShaderMaskPass` 流程。每个 pass 包含一个 RenderType、可选的 UV 对齐遮罩贴图、目标 RGB 颜色（默认黑色）、近色容差和透明度。一个扩展可以返回多个 pass，让同一张遮罩中的不同颜色分别使用不同着色器。pass 按列表顺序绘制，选区重叠时后面的 pass 覆盖在前面的 pass 之上；透明或颜色不匹配的像素不会渲染。

### 着色器预设

Mod 内置的 ECA 专属文件统一使用 `assets/<namespace>/eca/<system>/`；需要服务端读取的定义使用对应的 `data/<namespace>/eca/<system>/`。纹理、语言文件等 Minecraft 标准资源仍保留在原生目录，旧版 ECA 已公开的路径继续作为兼容回退来源。

本 Mod 还提供了一些用于实体扩展、物品扩展和方块扩展系统的着色器预设，可以直接在扩展中使用相关的 RenderType。使用时将示例代码中的 `CustomRenderTypes` 替换为对应预设名字即可。每个内置预设类提供 5 个现成 RenderType：实体扩展用的 `BOSS_BAR`、`BOSS_LAYER`、`SKYBOX`，物品扩展用的 `ITEM`，方块扩展用的 `BLOCK`；另有 `createEntityEffect(texture)` 用于实体纹理叠加。实体纹理叠加通过 `EntityLayerExtension.getTexture()` 支持——返回纹理即可叠加到实体模型上，可与着色器 RenderType 组合，实现 Boss 血条同款的纹理+着色器叠加效果。

每个内置预设同时以五文件形式打包，因此会注册为预设 ID `eca:<name>`，可由 `BlockExtension.getShaderPresetId()` 返回。GeckoLib 方块实体所需的 NEW_ENTITY 档通过 `EcaPresets.geoBlock("eca:<name>", texture)` 获取——预设类本身没有 geo 字段。

可用预设：
- `TheLastEndRenderTypes` / `eca:the_last_end` — 终焉
- `DreamSakuraRenderTypes` / `eca:dream_sakura` — 梦之樱
- `ForestRenderTypes` / `eca:forest` — 森林
- `OceanRenderTypes` / `eca:ocean` — 海洋
- `StormRenderTypes` / `eca:storm` — 风暴
- `VolcanoRenderTypes` / `eca:volcano` — 火山
- `ArcaneRenderTypes` / `eca:arcane` — 奥术
- `AuroraRenderTypes` / `eca:aurora` — 极光
- `HackerRenderTypes` / `eca:hacker` — 黑客
- `StarlightRenderTypes` / `eca:starlight` — 星辉
- `CosmosRenderTypes` / `eca:cosmos` — 宇宙
- `BlackHoleRenderTypes` / `eca:black_hole` — 黑洞

### 屏幕滤镜

本 Mod 提供了一组全屏后处理滤镜预设，可由服务端按玩家施加，通过命令或 API 均可。滤镜会同步到客户端，作为一道着色器 pass 叠加在世界画面上。每个玩家同一时刻只能激活一个滤镜——施加新滤镜会替换当前滤镜。

滤镜预设：
- `SKETCH` — 素描
- `SPOTLIGHT` — 聚光灯
- `MATRIX` — 矩阵
- `RAIN` — 雨
- `DESERT` — 沙漠
- `SNOW` — 雪
- `TOXIC` — 剧毒
- `COSMOS` — 宇宙

### ECA 着色器生成器

ECA 提供了游戏内着色器预设生成器，用于在不手写 GLSL 的情况下制作可移植的 Minecraft core shader 预设。使用以下命令打开：

```mcfunction
/eca shaderGenerator
```

生成器编辑的是一个分层合成工程。每个图层可以包含多个视觉模块，例如基础形状、星空效果、魔法符号和图片元素。编辑器支持实时预览、撤销/重做、图层显隐、图层排序、混合模式、画布编辑、工程保存/读取、标准五文件导出，以及删除工程（**文件 -> 删除当前工程**，二次确认后永久删除该工程目录及其源码、贴图与已导入的依赖）。

每个工程还拥有一套独立持久化的五文件源码工作区。使用 **文件 -> 源码编辑器** 可在同一工程中切换到手写 GLSL/JSON，源码页面提供单行菜单、基于注释的快速导航、撤销/重做、保存、编译快捷键和防抖实时预览；右侧上方是预览，下方是可滚动的编译信息与报错面板。生成的片段源码会为图层和元素写入 `// @eca-nav layer: ...` 与 `// @eca-nav element: ...` 标记，手写 `// @eca-nav ...` 注释也可创建自定义导航点。返回图层编辑器不会丢弃任意一侧的数据。使用 **文件 -> 导入已有着色器文件夹** 会从 Forge 规范化后的当前游戏目录打开系统原生文件夹选择器，把所选文件夹中的 JSON/VSH/FSH 着色器复制为新的本地 ECA 工程；一个文件夹检测到多个程序时会先要求选择。源码位于 `assets/<modid>/` 下时，工程对话框会自动填写该 Mod ID，否则保持空白。标准三文件会复制到 BLOCK 与 NEW_ENTITY 源码槽位并分别接受编译验证；符合 ECA 共享片元 `_block`/`_entity` 命名的五文件则直接保留两个 profile。源文件夹不会被修改。

导入范围是标准 Minecraft core shader 的 JSON/VSH/FSH 资源。预览运行时会为常见的时间、相机、缩放、不透明度及 cosmic UV uniform 提供绑定。若着色器依赖原 Mod 专用渲染管线、Java 回调、纹理或特殊 uniform，仍可能需要单独适配；无法支持的片元结构会明确报告编译错误，不会静默改写。

贴图依赖同样会被解析。外部着色器引用多张连续编号的 PNG 时，ECA 会尝试复制这些文件，在预览时将它们组合成一张纹理，并向对应的 sampler 和 uniform 提供每张图片在组合纹理中的 UV 范围。带 `.mcmeta` 动画段的 PNG 会在组合纹理中逐帧更新，并遵循帧序、每帧时长与插值设置。如果无法确认文件与 sampler 或 uniform 的对应关系，编译输出面板会说明未解析的依赖和扫描目录，而不是静默失败；着色器编译本身仍可成功。

当前预览目标包括平面、物品、实体、天空盒和 Boss 血条。导出的预设使用标准 core shader 五文件结构：

```text
assets/<namespace>/eca/shader_presets/<name>.fsh
assets/<namespace>/eca/shader_presets/<name>_block.vsh
assets/<namespace>/eca/shader_presets/<name>_block.json
assets/<namespace>/eca/shader_presets/<name>_entity.vsh
assets/<namespace>/eca/shader_presets/<name>_entity.json
```

片元着色器由两个 profile 共享。顶点着色器必须分成两个 profile，因为 Minecraft 不同渲染目标使用的顶点格式不同：

- `<name>_block.*` 使用 `DefaultVertexFormat.BLOCK`，用于天空盒、平面预览和 Boss 血条。
- `<name>_entity.*` 使用 `DefaultVertexFormat.NEW_ENTITY`，用于实体额外渲染层、物品额外渲染层和带纹理的实体效果层。

导出模式：

- `PORTABLE`：标准 Minecraft core shader 输出，不包含 ECA 专属 uniform。
- `PORTABLE_WITH_ECA_HINTS`：包含 ECA uniform 钩子和无害默认值，但脱离 ECA 仍可使用。
- `ECA_ENHANCED`：包含 ECA 专属 uniform，并预期由 ECA 增强运行时加载。

工程会保存到 `config/eca/shadergenerator/<namespace>/<name>/project.json`。使用 **File -> Export As <shader>** 可以把当前工程导出为运行时可加载的五文件预设，位置为 `config/eca/shadergenerator/<namespace>/<name>/`。ECA 会自动发现 mod assets 内的预设，以及 config 中导出的预设。预设 ID 固定为 `<namespace>:<name>`。

如果要把预设打包进 Mod，将五个文件放到 `src/main/resources/assets/<namespace>/eca/shader_presets/`。旧目录 `assets/<namespace>/shaders/core/` 继续兼容；同一 ID 同时存在时以新的 ECA 目录为准。也可以使用 `@RegisterShaderPreset` 显式声明预设。这个注解会在启动扫描阶段注册对应的预设 ID，适合希望通过 Java 标记类明确暴露自定义预设的 Mod：

```java
import net.eca.api.RegisterShaderPreset;

@RegisterShaderPreset("mymod:my_nebula")
public final class MyNebulaPreset {
}
```

运行时可以通过 `EcaPresets` 获取生成后的 RenderType：

```java
import net.eca.client.render.preset.EcaPresets;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public final class MyPresetRenderTypes {
    public static RenderType bossBar() {
        return EcaPresets.bossBar("mymod:my_nebula");
    }

    public static RenderType bossLayer() {
        return EcaPresets.bossLayer("mymod:my_nebula");
    }

    public static RenderType skybox() {
        return EcaPresets.skybox("mymod:my_nebula");
    }

    public static RenderType item() {
        return EcaPresets.item("mymod:my_nebula");
    }
}
```

也可以通过 `EcaAPI` 查询预设对象：

```java
import net.eca.api.EcaAPI;
import net.eca.client.render.preset.ShaderPreset;
import net.minecraft.resources.ResourceLocation;

ShaderPreset preset = EcaAPI.shaderPreset(new ResourceLocation("mymod", "my_nebula"));
```

返回的 `ShaderPreset` 提供 `bossBar()`、`bossLayer()`、`skybox()`、`item()`、`block()`、`geoBlock(texture)` 和 `entityForPreview(texture)`；`EcaPresets` 以预设 ID 静态查询的形式镜像了前六个。`block()` 与 `geoBlock(texture)` 分别是方块扩展使用的 BLOCK 与 NEW_ENTITY profile。实体纹理叠加请通过 `EntityLayerExtension.getTexture()` 配合 `bossLayer()` 使用。

#### ECA 着色器 AI 助手

着色器生成器内置 AI 助手，可以让所选模型直接操作当前工程。支持 OpenAI Responses、OpenAI Chat 兼容和 Anthropic Messages 三种接口格式；每种格式保存为独立 profile，可配置 API URL、模型、API Key 或 Key 环境变量、自定义请求头及 10–600 秒超时。配置保存在 `config/eca/shadergenerator/settings.json`。

模型可以读取工程与模块参数、编辑图层和元素、导入 PNG、局部修改五个源码文件、保存工程、导出五个着色器文件、编译并读取诊断、获取实时预览，以及撤销或重做自己的改动。用户可以分别控制自动编辑、编辑后自动编译和向视觉模型发送预览图；关闭自动编辑后，模型只能查看和讲解工程。

#### MCP

AI 助手页面中的 **MCP** 按钮会启动本地 ECA Shader MCP，让外部 Agent 操作当前着色器工程。服务使用 Streamable HTTP，只监听 `127.0.0.1`，无需访问令牌；MCP 页面会显示端口和已连接 Agent，端口配置保存在 `config/eca/shadergenerator/mcp_settings.json`。URL 端口应与 MCP 页面一致，并且 Minecraft 和 MCP 服务需要保持运行。

使用顺序：先在游戏内 **着色器生成器 → AI 助手 → MCP** 点击按钮启动服务，再启动并连接 Agent。服务未启动时 Agent 无法连接。

##### Codex

命令行注册：

```bash
codex mcp add eca_shader --url http://localhost:8767/mcp
```

##### Claude Code

命令行注册：

```bash
claude mcp add --transport http eca_shader http://127.0.0.1:8767/mcp
```

### BossShow 演出

BossShow 会把玩家的镜头锁定在围绕目标实体录制的路径上播放一段过场动画，支持字幕和服务端事件回调。镜头路径用游戏内编辑器录制，不需要手写关键帧。

**默认编辑器快捷键**：

| 按键 | 功能 |
|------|------|
| `J` | 开始 / 恢复录制 |
| `I` | 暂停录制 |
| `ENTER` | 保存录制 |
| `ESC` | 放弃录制 |

**编辑器流程**：
1. 在至少一个 LivingEntity 附近（64 格内）执行 `/eca bossShow edit`。
2. 在主页面点击 **+ New cutscene from entity** → 瞄准一个实体 → 右键选中作为摄像机锚点；或点击 **Edit** 编辑已有演出。
3. 配置触发方式（Range 范围触发 / Custom 自定义触发）、目标实体类型、电影黑边、是否允许重复播放等。
4. 点击 **● Record**，按 `J` 开始录制。在旁观模式下自由移动镜头——每个 tick 都会被记录为可编辑帧。
5. 录制完成后点击时间轴上的任意 tick，编辑位置和镜头朝向；使用 **▶ 预览** 播放时间轴。需要从游戏视角重新取样时，点击 **自由镜头回写**，移动镜头后按回车写入当前 tick。
6. 按 `ENTER` 保存，`ESC` 放弃。保存的文件位于 `config/eca/bossshow/<命名空间>/<路径>.json`。

**时间轴编辑**（录制完成后，在编辑器界面内）：每一帧都可以选择。点击或拖动时间轴 scrub，使用方向键逐 tick 移动，并在属性面板编辑 `dx/dy/dz/yaw/pitch`。使用 **添加内容** 在当前 tick 挂载事件或字幕。时间轴还支持 **设入点** / **设出点**、**复制** / **剪切** / **删除**、**粘贴**、范围统一偏移/渐变偏移和 `Ctrl+Z` / `Ctrl+Y`。

编辑器内按 `Space` 播放或暂停，末帧时会从头播放；`Shift+←/→` 跨 10 tick 移动，`Home/End` 跳到首尾，`I/O` 设置入点和出点，`Ctrl+C/X/V` 操作选区。右键镜头、事件或字幕轨道会定位到对应 tick，并打开该轨道的上下文菜单；曲线和选区操作位于二级菜单。完整按键表可从 **编辑 → 快捷键说明** 查看。

录制时飞行惯性默认关闭，可在配置文件 `BossShow` 项的 `Enable Recording Flight Inertia` 中开启。

**Mod 开发者**

定义演出有两种方式：

1. **纯 JSON** — 将文件放在 `data/<modid>/eca/bossshow/<path>.json`，启动时自动加载；旧目录 `data/<modid>/bossshow/<path>.json` 继续兼容。不需要服务端事件处理的话不用写 Java 代码。

2. **Java + JSON** — 继承 `BossShow` 并使用 `@RegisterBossShow` 注解，可以在播放过程中收到服务端事件回调。

当前 v4 JSON 示例。此最小示例仅含 3 帧（tick 0–2，20 TPS 下为 0.15 秒），因此事件、字幕和特效也安排在该范围内。实际演出请使用编辑器录制更长的镜头轨道，并在需要的 tick 添加内容：

```json
{
  "format_version": 4,
  "target_type": "minecraft:warden",
  "trigger": { "type": "range", "effect_radius": 32.0 },
  "cinematic": true,
  "allow_repeat": false,
  "anchor_yaw": 0.0,
  "frames": [
    { "dx": 0.0, "dy": 1.8, "dz": -6.0, "yaw": 0.0, "pitch": 10.0 },
    { "dx": 0.0, "dy": 1.8, "dz": -5.8, "yaw": 2.0, "pitch": 10.0 },
    { "dx": 0.0, "dy": 1.8, "dz": -5.6, "yaw": 4.0, "pitch": 10.0 }
  ],
  "events": [
    { "tick": 0, "event_id": "intro" },
    { "tick": 2, "event_id": "finisher" }
  ],
  "subtitles": [
    { "tick": 0, "text": "mymod.bossshow.warden.intro" }
  ],
  "effects": [
    { "tick": 0, "type": "camera_shake", "duration": 2, "fade_out": 1,
      "parameters": { "yaw": 1.5, "pitch": 1.0, "roll": 0.5, "frequency": 1.2 } },
    { "tick": 2, "type": "shader_effect", "effect": "chromatic_aberration",
      "duration": 1,
      "parameters": { "strength": 0.012, "angle": 15.0, "pulse_amount": 0.4, "pulse_speed": 2.0 } }
  ]
}
```

- `format_version`：当前保存版本为 `4`，位置偏移与镜头 yaw 使用同向的锚点局部坐标系。旧版逐帧文件在加载时根据 `anchor_yaw` 转换坐标，不能只改版本号来迁移。
- `frames`：每 tick 一个对象，按播放顺序排列。帧在数组里的下标就是它的 tick，没有单独的时间字段。由编辑器录制生成。
- `frames[].dx/dy/dz`：相机在锚点局部坐标系下的偏移。
- `frames[].yaw/pitch`：相机朝向（yaw 为锚点局部）。
- `events`：独立事件轨道。每项包含 `tick` 和 `event_id`，在对应 tick 传递给 `BossShow.onKeyframeEvent()`。
- `subtitles`：独立字幕轨道。每项包含 `tick` 和 `text`，可使用纯文本或翻译 key。
- `effects`：客户端屏幕效果轨道。公共字段包括 `tick`、`type`、`duration`、`fade_in`、`fade_out`、`easing` 和 `parameters`。类型支持 `camera_shake`、`shader_effect` 与 `filter`；着色器效果支持色差、波浪扭曲、热浪、亮度脉冲、色相循环、扫描线和暗角，滤镜名称沿用 ECA 滤镜系统。
- `frames[].keyframe`：旧格式兼容字段，新文件建议使用 `events` 和 `subtitles`。
- `trigger`：`{"type":"range","effect_radius":N}` 玩家进入目标实体范围时自动触发；`{"type":"custom","event_name":"..."}` 仅通过 `EcaAPI.launchBossShowEvent(...)` 匹配触发。

> 旧版 `samples` + `markers` 格式已不再识别——用旧格式的文件会被加载为零帧演出。请重新录制或迁移到 `frames`。

事件处理示例 — JSON 中的 `event_id` 会在对应 tick 被分发到服务端的 `onKeyframeEvent`：

```java
@RegisterBossShow
public class WardenIntroShow extends BossShow {
    public static final ResourceLocation ID = new ResourceLocation("mymod", "warden_intro");
    static { BossShowManager.register(new WardenIntroShow()); }

    public WardenIntroShow() { super(ID, EntityType.WARDEN); }

    @Override
    public void onKeyframeEvent(String eventId, BossShowContext ctx) {
        LivingEntity target = ctx.target();
        ServerPlayer viewer = ctx.viewer();
        if (target == null || !target.isAlive()) return;

        switch (eventId) {
            case "intro" -> target.level().playSound(null, target.blockPosition(),
                                SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 2f, 1f);
            case "phase2" -> EcaAPI.setInvulnerable(target, true);
            case "finisher" -> EcaAPI.kill(target, target.level().damageSources().generic());
        }
    }

    @Override
    public void onStart(BossShowContext ctx) {
        // 演出开始时调用
    }

    @Override
    public void onEnd(BossShowContext ctx, boolean skipped) {
        // 演出结束时调用；skipped = true 表示观看者按了 ESC 跳过
    }
}
```

代码触发方式：

```java
EcaAPI.playBossShow(viewer, target, cutsceneId); // 强制播放（无视历史记录）
EcaAPI.playBossShowIfNew(viewer, target, cutsceneId); // 仅在未看过时播放
EcaAPI.launchBossShowEvent("phase2", viewer, target); // 匹配所有自定义触发的同名演出
EcaAPI.stopBossShow(viewer); // 停止当前演出
EcaAPI.isBossShowPlaying(viewer); // 检查是否在演出中
```

> 如果 `@RegisterBossShow` 类在首次启动时还没有对应 JSON，会在 `config/eca/bossshow/` 下自动生成只含 `target_type` 的空壳文件作为兜底。

**整合包开发者**

- **覆盖演出** — 将修改后的 JSON 放到 `config/eca/bossshow/<命名空间>/<路径>.json`。该目录下的文件会覆盖 Mod 内置的同 id 定义（规范目录为 `data/<modid>/eca/bossshow/`，同时兼容 `data/<modid>/bossshow/`）。
- **游戏内调整** — `/eca bossShow edit` 可以重新录制镜头路径、逐 tick 编辑位姿、调整触发方式、添加事件/字幕内容，并在时间轴上复制/剪切/删除/粘贴帧区间。保存写入 `config/eca/bossshow/`，不影响 Mod 原始文件。
- **翻译/改写字幕** — 在 `config/eca/bossshow/lang/<locale>.json`（如 `en_us.json`、`zh_cn.json`）中覆盖字幕翻译 key，优先级高于 Mod 自带的语言文件：
    ```json
    {
      "mymod.bossshow.warden.intro": "深处传来一阵低沉的回响……"
    }
    ```
- **热重载** — `/eca bossShow reload` 立即加载所有 JSON 更改，无需重启游戏。

### 自定义阵营

本 Mod 提供了一套约束目标选择与伤害关系的阵营系统。实体绑定阵营后，原版同盟判断和目标设置会遵守同阵营、友好与中立规则，无需实现接口或编写混入。绑定阵营的生物会周期性地通过 `Mob.setTarget` 获取附近与之为 `HOSTILE` 关系的阵营实体，其既有战斗 Goal 仍负责移动与攻击；无阵营的实体不会被这一索敌流程选中。标准 `LivingEntity` 伤害路径会执行友军保护，直接修改状态的 API 则仍需调用方自行判断。`FactionUtil.isFriendly` 负责解析同盟关系，`FactionUtil.canAttack` 则额外执行创造/旁观与 ECA 无敌保护。

对外可通过 `EcaAPI.isFriendly(a, b)` 完整判断友方关系。它涵盖 ECA 同阵营、ECA 友好阵营、原版计分板同盟、玩家与自己的宠物、同主人的宠物，以及主人属于原版同盟队伍的宠物。创造模式、旁观模式和 ECA 无敌刻意不计入友方，因为它们属于攻击保护而非同盟关系。只有需要判断 ECA 阵营 ID 完全相同时才使用 `areSameFaction`；`canHarm` 只检查 ECA 阵营伤害关系，`canTarget` 则同时拒绝中立关系和完整目标免疫。

注册阵营需要创建继承 `FactionDefinition` 的子类，并在类上标注 `@RegisterFaction`。ECA 在 `FMLLoadCompleteEvent` 期间扫描全部 Mod，重复 ID 会被记录并跳过（先扫描到的生效）。也可以通过 `EcaAPI.createFaction` 在运行时创建阵营，可选择是否持久化。实体扩展还可以重写 `getFactionId()`，使该类型实体自动加入某个阵营——阵营必须在这些实体生成前完成注册，否则绑定会被拒绝并记录日志。

四种关系：
- `SAME_FACTION` — 同一阵营，完全免伤且不会被设为目标
- `FRIENDLY` — 不同阵营但结盟，不造成伤害也不设目标
- `NEUTRAL` — 不会被主动设为目标，但误伤仍然生效
- `HOSTILE` — 正常敌对

`SAME_FACTION` 是派生关系而非可存储关系：它由"双方解析出同一阵营 ID"产生，写成跨阵营覆盖永远不会被读回。要让两个阵营真正成为同一个，只能合并——`EcaAPI.mergeFactions(intoId, fromId, level)`，`/eca faction relation A B same_faction` 即以 A 为存活方调用它。B 的成员改绑到 A，B 的关系覆盖仅在 A 未显式设定时被继承，第三方指向 B 的条目改指 A 或直接丢弃，随后删除 B。A 保留自己的显示名、颜色、默认关系与首领，仅在自身无首领时接手 B 的首领。

关系解析按以下顺序进行，命中即返回：
1. 阵营 ID 相同 → `SAME_FACTION`
2. A 的 `getRelation(self, target)` 条件覆写
3. A 的静态 `hostileTo` / `friendlyTo` / `neutralTo` 预设
4. 对称回退 —— 从 B 的角度重复上述两项判断
5. A 的 `getDefaultRelation(self, target)` 条件覆写（仅当对方无阵营时）
6. A 的静态默认关系

每个阵营拥有自己的成员表。成员以 UUID 加实体类型的形式记录，因此列出名单、按类型筛选、统计数量都无需加载任何实体——处于未加载区块或其他维度的成员同样可见、可管理。阵营存储于主世界存档，成员归属跨维度全局共享并能在重启后恢复。

实体被永久移除时绑定会被清除；区块卸载和跨维度传送会保留绑定，玩家的绑定则在死亡重生后始终保留。成员身份不能脱离阵营存在——注销阵营会一并清空其成员表，加入不存在的阵营会被拒绝而不是被静默记录。

驯服动物会自动继承主人的阵营，因此宠物同样受主人盟友保护，并可响应附近的阵营求援。继承在查询时解析而非落库：继承阵营的宠物不会出现在持久化成员表、离线查询、成员计数或遍历成员表的首领传导中。宠物会始终跟随主人换营且自身不会产生绑定——对这类宠物调用 `leaveFaction` 不会有任何效果。若希望宠物归属其他阵营或参与成员表操作，需要显式绑定；显式绑定始终优先于继承。

阵营系统只负责成员归属、关系和首领。`FactionDefinition` 不提供实体组成或生成权重 API；这些内容由实际执行生成的系统自行管理，因此同一阵营可以被不同系统使用而不共享生成规则。

```java
@RegisterFaction
public class UndeadLegionFaction extends FactionDefinition {

    @Override public String getId() { return "undead_legion"; }
    @Override public String getDisplayName() { return "faction.your_mod.undead_legion"; }
    @Override public int getColor() { return 0xFF884400; }

    // 对无阵营实体的默认态度
    @Override public FactionRelation getStaticDefaultRelation() { return FactionRelation.HOSTILE; }

    @Override public String[] getFriendlyTo() { return new String[]{"lich_coven"}; }
    @Override public String[] getHostileTo() { return new String[]{"village_guard"}; }

    // 可选：条件关系，返回 null 则回退到上面的静态预设
    @Override
    public FactionRelation getRelation(LivingEntity self, Entity target) {
        if (target instanceof Player player && player.isCreative()) {
            return FactionRelation.NEUTRAL;
        }
        return null;
    }

}
```

**首领**：阵营可以指定一名成员作为首领。设置首领时若该实体尚未入营会自动加入——首领不属于自己的阵营是自相矛盾的状态。退出阵营同时卸任首领，首领被永久移除时首领记录会自动清除。

**仇恨传导**：当首领攻击某个实体或被某个实体攻击时，系统会尝试把该实体交给成员表中可解析的全部生物作为目标；已有目标和阵营目标权限仍可能阻止切换。系统中并存两套机制：

| | 触发条件 | 范围 |
|---|---|---|
| 首领保护 | 首领攻击他人或被攻击 | 整张成员表 |
| 成员求援 | 任意成员受到伤害 | 受害者周围的可配置半径 |

首领保护刻意不设范围限制：直接遍历成员表，因此远离主人的召唤物同样会响应。无法在首领所在维度解析到的成员会被跳过，传导也绝不会让成员获得一个它本就不允许攻击的目标。同一 tick 内对同一目标的重复传导会被丢弃，避免首领连续攻击时反复遍历成员表。

两套机制完全由配置文件控制，不提供按阵营覆写，因此同一服务器上所有阵营表现一致：

- `Leader Protection Enabled`（默认 `true`）
- `Immediate Leader Protection`（默认 `false`）
- `Alert Enabled`（默认 `true`）/ `Alert Range`（默认 `32`）/ `Immediate Member Alert`（默认 `false`）

"Immediate" 关闭时只有当前没有目标的成员才会响应；开启时成员会放弃正在交战的目标。

**查询**：归属关系可以从两个方向查询，其中不解析实体的方法完全离线可用：

| 方向 | 方法 |
|---|---|
| 实体关系 | `areSameFaction(a, b)`（仅 ECA 同阵营）、`isFriendly(a, b)`（完整 ECA + 原版友方判断）、`getEffectiveFactionRelation(a, b)`、`canHarm(a, b)`（仅 ECA 阵营伤害规则）、`canTarget(a, b)`（包含中立与免疫的目标检查） |
| 成员 → 阵营 | `getEntityFaction(entity)`（含宠物继承）、`getEntityFaction(uuid)`、`isFactionMember(uuid, id)` |
| 阵营 → 成员 | `getFactionMemberRecords(id)`、`getFactionMemberUuids(id)`、`getFactionMembersByType(id, typeId)`、`getFactionMemberCount(id)` |
| 阵营 → 实体 | `resolveFactionMembers(id, level)` |
| 阵营 → 首领 | `getFactionLeader(id)`、`getFactionLeaderUuid(id)`、`resolveFactionLeader(id, server)`（跨全部维度搜索） |
| 首领 → 阵营 | `getFactionByLeader(uuid)`、`isFactionLeader(entity)` |

`joinFaction` 与 `leaveFaction` 均提供 UUID 重载，用于管理实体未加载的成员。

阵营成员还可以按关系颜色对附近玩家发光，该功能可在配置中开关，默认关闭。

### 自定义袭击

本 Mod 提供了一套可自定义的袭击系统。原版袭击只能作用于村庄、只接受实现了 `Raider` 接口的实体，且胜利条件与奖励全部硬编码；ECA 的袭击可以指向任意结构、使用任意实体类型，并且能替换掉决定袭击如何推进与结束的每一条规则。

注册袭击需要创建继承 `RaidDefinition` 的子类，并标注 `@RegisterRaid`。扫描排在阵营扫描之后，因此袭击定义可以自由引用阵营 ID。只有 `getId()`、`getDisplayName()` 和 `getWaves()` 必须覆写，其余全部带有仿原版的可用默认值。

**目标锚定**：覆写 `getTargetStructure()` 指向单一结构，或覆写 `getTargetStructureTag()` 匹配带有某个标签的任意结构，使一个袭击适用于多种结构。锚定决定了默认的失败条件：当目标结构不再覆盖袭击中心时判定防守失败。两者都不声明则袭击不锚定结构，此时只能通过胜利、超时或主动结束来终止。

**波次**：每个 `RaidWave` 可自由混用两种生成源——显式指定实体类型，以及通过 `addFaction(String factionId, int count, Map<EntityType<?>, Integer> typeWeights)` 在本波内部配置实体类型和权重的阵营抽取。同一阵营在不同波次可以使用完全不同的生成组合和权重，例如：`new RaidWave().addFaction("undead_legion", 10, Map.of(EntityType.ZOMBIE, 8, EntityType.SKELETON, 2))`。

**袭击者**：通过 `addEntry(...)` 添加的显式条目绑定到 `getRaiderFactionId()`，每个 `addFaction(...)` 生成组则绑定到该条目自己的阵营 ID；波次首领同样使用 `getRaiderFactionId()`。生成的 `Mob` 实例还会被注入一个前往袭击中心的寻路 Goal。该 Goal 默认优先级为 3，与原版 `PathfindToRaidGoal` 一致——低于常见的近战攻击 Goal，因此袭击者会优先处理已经取得的敌对阵营目标，否则向中心推进。任意实体类型均可生成且不要求实现接口，但非 `Mob` 实体不会获得阵营索敌、导航 Goal 或生物回调。可覆写 `getRaiderGoalPriority()` 调整优先级，返回负数则禁用注入。

**Boss**：波次可以通过 `RaidWave.setLeader(type)` 声明一名首领。生成的实体会被设为该袭击所属袭击者阵营的首领，从而使用阵营仇恨传导。默认只有已加载、符合目标权限且当前没有目标的生物会响应；开启 `Immediate Leader Protection` 后才会替换已有目标。声明首领需要 `getRaiderFactionId()`；没有阵营就没有可领导的对象，该条目会作为普通袭击者生成。

需要注意传导遍历的是整张阵营成员表，而非仅本场袭击的参与者。若该袭击者阵营在世界其他地方还有成员，它们同样会响应。希望响应范围限定在本场袭击内，请为袭击使用专属阵营。

**启动校验**：发起袭击时会校验其引用的阵营。非空但未注册的袭击者阵营会直接拒绝启动，因为所请求的友伤和求援规则无法应用。主动返回 `null` 则是允许的，此时每个生成实体完全由自身 AI 控制。波次抽取的阵营若未注册或本波没有正权重实体类型，则记录错误并跳过该组，袭击仍会启动。

**流程控制**：`shouldAdvanceWave`、`checkVictory` 和 `checkDefeat` 均可覆写。默认实现复现原版语义：上一波清空后生成下一波，全部波次生成完毕且袭击者全灭时防守方获胜。

**时间与回调**：`getMaxDurationTicks()` 默认 48000，`getWaveCooldownTicks()` 默认 300，`getParticipantRadius()` 默认 96 格，`getCelebrationTicks()` 默认 600。每个波次还可设置 `spawnDelay()` 和 `spawnRadius()`。生命周期回调包括 `onStart`、`onWaveStart`、`onWaveEnd`、`onVictory`、`onDefeat` 与 `onStop`；客户端 `bossBarExtension()` 可在保留服务端袭击状态同步的同时替换血条外观。

**无限波次**：`isEndless()` 会循环使用波次列表且永远不满足默认胜利条件。此类袭击需要通过 `EcaAPI.endRaid` 收尾，该方法会清除全部仍存活的袭击者。

袭击按维度独立运行，并会在重启后自动恢复最近一次周期检查点。永久减员和终止操作会立即保存，普通流程每秒保存一次。袭击期间只强制加载中心区块；走入其他未加载区块的袭击者不会随中心区块一起强制加载。

```java
@RegisterRaid
public class UndeadSiege extends RaidDefinition {

    @Override public String getId() { return "undead_siege"; }
    @Override public String getDisplayName() { return "raid.your_mod.undead_siege"; }
    @Override public String getRaiderFactionId() { return "undead_legion"; }

    @Override
    public ResourceKey<Structure> getTargetStructure() {
        return ResourceKey.create(Registries.STRUCTURE, new ResourceLocation("minecraft", "village_plains"));
    }

    @Override
    public List<RaidWave> getWaves() {
        return List.of(
            // 显式指定实体类型
            new RaidWave().addEntry(EntityType.ZOMBIE, 6),
            // 使用本波自己的权重从阵营中抽取
            new RaidWave().addFaction("undead_legion", 10, Map.of(EntityType.ZOMBIE, 8, EntityType.SKELETON, 2)),
            // 两种来源混用，并对每个生成的实体做后处理
            new RaidWave()
                .addEntry(EntityType.WITHER_SKELETON, 4, mob -> mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD)))
                .addFaction("undead_legion", 8, Map.of(EntityType.ZOMBIE, 1, EntityType.WITHER_SKELETON, 4))
                .setLeader(EntityType.WITHER, mob -> mob.setCustomName(Component.literal("Undead Warlord")))
                .spawnRadius(32.0)
        );
    }

    // 可选：替换默认胜利条件
    @Override
    public boolean checkVictory(RaidContext ctx) {
        return ctx.isAllWavesSpawned() && ctx.getAliveRaiderCount() == 0;
    }

    @Override
    public void onVictory(RaidContext ctx) {
        for (ServerPlayer player : ctx.getNearbyPlayers()) {
            player.giveExperiencePoints(500);
        }
    }
}
```

注册定义本身不会启动任何东西。袭击需要显式发起，因此任何触发条件都可以驱动它——进入某个区域、使用某个物品、执行命令、定时事件等：

```java
// 在目标结构内发起，中心取自结构包围盒
RaidInstance raid = EcaAPI.startRaid(serverLevel, pos, "undead_siege");

// 指定中心，跳过结构查询
RaidInstance forced = EcaAPI.startRaidAt(serverLevel, center, "undead_siege");

// 提前结束，并清除全部仍存活的袭击者
EcaAPI.endRaid(serverLevel, raid, true);
```

### ECA Transformer 白名单

尽管我尽可能的添加了常见的库和 Mod 作为 ECA Transformer 的白名单，但是仍然不排除有 mod 因为被 ECA 转换导致崩溃的问题，所以我准备了一个可供整合包开发者使用的 JSON 配置文件来添加包名白名单给 ECA Transformer。你可以在 `config/eca/` 文件夹下添加 JSON 文件来添加白名单，首次启动时如果文件夹为空会自动生成示例文件。

只有 `type` 和 `packages` 字段是必须的，其他字段会被忽略：

单个 mod 示例（`allreturn` — 仅跳过 AllReturn 转换，防御性 Hook 仍然生效）：
```json
{
  "type": "allreturn",
  "packages": [
    "com.example.yourmod."
  ]
}
```

多个 mod 示例（`transform` — 跳过全部 ECA 转换）：
```json
{
  "type": "transform",
  "packages": [
    "com.example.modA.",
    "com.example.modB.",
    "net.example.modC."
  ]
}
```

文件名随意，可以有多个文件。

---

**Author / 作者**: CJiangqiu
