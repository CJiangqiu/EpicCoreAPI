# EpicCoreAPI

This mod provides entity manipulation APIs and commands based on CoreMod (ITransformationService), Java Agent, and Mixin technologies, plus a set of feature modules: BossShow, entity extensions, block extensions, item extensions, screen filters, the ECA shader generator, custom factions, and custom raids. Note that while the entity-manipulation methods may share names with vanilla logic, the underlying implementation is completely different. For example, the set health API can modify entities using custom health values (including but not limited to entity data, numeric fields, and hash tables); the remove API performs low-level Minecraft container cleanup; the set invulnerable API provides a more powerful implementation than vanilla creative mode invulnerability. Additionally, this mod unlocks vanilla attribute limits to Double.MAX_VALUE by default. You can disable this in the config file with "Unlock Attribute Limits" option.

The original intent of this mod is to provide developers with simplified entity manipulation APIs while achieving a certain level of strength under the premise of ensuring performance and compatibility. Therefore, please do not use this mod for mod power comparisons or endless code arms races. Additionally, in modpack survival environments, it is best to ensure that the Attack and Defence Radical Logic config options are disabled.

This mod also provides an [MCreator plugin](https://mcreator.net/plugin/121284/20244epic-core-api-plugin) for MCreator users to conveniently use the APIs in this mod.

## Usage for Players

Players can use the following `/eca` commands (requires permission level ≥ 2):
- `/eca setHealth <targets> <health>` - Set entity health
- `/eca setMaxHealth <targets> <maxHealth>` - Set entity max health (reverse-calculates attribute base value)
- `/eca setInvulnerable <targets> <true|false>` - Set entity invulnerability
 - `/eca lockHealth <targets> true <value>` - Lock entity health at specific value
 - `/eca lockHealth <targets> false` - Unlock entity health
 - `/eca lockMaxHealth <targets> true <value>` - Lock entity max health at specific value
 - `/eca lockMaxHealth <targets> false` - Unlock entity max health
 - `/eca banHealing <targets> true [value]` - Ban healing for entities (value optional, defaults to current health)
 - `/eca banHealing <targets> false` - Unban healing for entities
 - `/eca hurt <targets> <amount>` - Force entity damage (vanilla hurt first, forced write when the health loss does not land; kill credit goes to the executor when run by a living entity)
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
- `/eca setFilter <targets> true <type>` - Apply a screen filter to players (type: sketch, spotlight, matrix, rain, desert, snow, toxic, cosmos)
- `/eca setFilter <targets> false` - Remove all active filters from players
- `/eca bossShow edit` - Open the in-game cinematic editor (switches to spectator mode)
- `/eca bossShow exit` - Exit the editor and restore the previous game mode
- `/eca bossShow list` - List all loaded cutscenes
- `/eca bossShow play <viewer> <target> <id>` - Force-play a cutscene
- `/eca bossShow stop <viewer>` - Stop the viewer's current cutscene
- `/eca bossShow reload` - Reload all cutscene JSON definitions from disk
- `/eca bossShow clearHistory <player>` - Clear a player's "already seen" records
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
- `/eca faction relation <factionA> <factionB> <relation>` - Set A's relation toward B (hostile/neutral/friendly)
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
- `banHealing(entity, value)` - Ban healing for entity at specified value (entity cannot heal but can take damage)
- `unbanHealing(entity)` - Unban healing for entity
- `getHealBanValue(entity)` - Get current heal ban value (null if not banned)
- `isHealingBanned(entity)` - Check if entity has healing banned
- `getHealth(entity)` - Read the health observed through the entity's active life protocol: the analyzer's health anchor, falling back to vanilla `DATA_HEALTH_ID` when no anchor resolves
- `setHealth(entity, health)` - Verified health transaction that escalates through channels only when the previous one fails verification: vanilla write (write `DATA_HEALTH_ID` directly) → dataflow reversal (ASM dataflow analysis of `getHealth()` locates the real storage and inverts its read expression) → external scan (reverse `isAlive` / `isDeadOrDying` / `hurt` / `actuallyHurt` to locate storage, including effective-health models that need conversion) → method probe (borrow the entity's own writer: reflective setters, functional fields, injected bridges) → numeric inversion (search the object graph for writable numeric cells when the storage cannot be inverted). Each attempt is verified by reading the health anchor back within `max(0.5, abs(target) * 2%)`, and a failed write rolls the whole transaction back. Players run the vanilla write only. Every channel past the vanilla write requires Attack Radical Logic plus its own switch under `Attack → setHealth` (Const Override / External Scan / Method Probe / Numeric Inversion), all off by default.
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
- `teleport(entity, x, y, z)` - Teleport via direct field access with client sync
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
- `getFactionMemberTypes(factionId)` - Get the entity type pool a faction declares, mapped to spawn weights
- `rollFactionMemberType(factionId, random)` - Pick one entity type from a faction's pool by weight
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

### Entity Extensions

This mod also provides a customizable entity type extension feature for adding special visual effects to your entities. You need to create a subclass extending `EntityExtension` and annotate it with `@RegisterEntityExtension` to register the extension.

Entity shader layers use ordered `ShaderMaskPass` lists. Each pass can select a different color from the same UV-aligned mask and render that region with a different shader; black is the default target color and near-color tolerance is configurable. The same mask pipeline works with vanilla and GeckoLib entity renderers, while Geo masks also intersect with bone filtering.


### Block Extensions

Block extensions use the same ordered mask-pass pipeline for ordinary baked models, falling blocks, and GeckoLib block entities. World and falling blocks use BLOCK-profile passes with atlas UVs converted per sprite; Geo blocks use NEW_ENTITY-profile passes and model-local UVs. Multiple mask colors may select different shaders, and legacy Color-Key/single-mask getters remain only as deprecated adapters. Section-based sparse indexing and Oculus-compatible delayed drawing are retained. Block items continue to use Item Extensions; custom non-Gecko block entity renderers require their own integration.

### Item Extensions

Item extensions return ordered `ShaderMaskPass` lists. Multiple passes may share one mask texture while selecting different colors and shaders; baked item atlas UVs are converted per sprite before external masks are sampled. The former single RenderType, Color-Key, and single-mask methods are deprecated compatibility adapters.


Structured tooltip lines can choose their own insertion position:

- `EcaTooltipLine.head(...)`: below the item name.
- `EcaTooltipLine.body(...)`: in the main tooltip body, before advanced item id/NBT/disabled lines when present.
- `EcaTooltipLine.tail(...)`: at the end of the tooltip.

Each line accepts either a normal `Component` or an `EcaText` built through `ItemUtil.of(...)`, so tooltip text supports the same rich effects as item names: gradient, rainbow, solid color, shimmer, glitch, bold, italic, underline, and strikethrough. The older `appendTooltip(ItemStack, TooltipFlag, List<Component>)` hook is still available when you need to directly edit the final tooltip list.

Note: Like entity extensions, each item can only have one extension. Duplicate registrations are rejected with an error log. Both entity layer extensions (`EntityLayerExtension.getAlpha()`, default 0.5) and item extensions (`ItemExtension.getAlpha()`, default 1.0) support adjustable transparency for their shader overlay layers.

### Shader Presets

This mod also provides several shader presets for the entity extension and item extension systems, which can be used directly in your extensions. Simply replace `CustomRenderTypes` in the example code with the corresponding preset name. Each preset provides 4 RenderTypes: `BOSS_BAR`, `BOSS_LAYER`, `SKYBOX` for entity extensions, and `ITEM` for item extensions. Entity texture overlays are supported through `EntityLayerExtension.getTexture()` — return a texture to overlay it on the entity model, optionally combined with the shader RenderType for a texture‑plus‑shader effect (matching the boss‑bar overlay technique).

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

The generator edits a layered composition project. Each layer can contain multiple visual modules, including basic shapes, starry sky effects, magic symbols, and image elements. The editor supports live preview, undo/redo, layer visibility, layer ordering, blend modes, canvas editing, project save/load, five-file shader export, project deletion, a source editor for hand-writing the five GLSL/JSON files, and importing an existing standard shader folder into a project. It also ships an AI assistant that drives the current project through a model of your choice, and a local MCP server that lets an external agent do the same.

Preview targets currently include plane, item, entity, skybox, and Boss bar. The exported preset uses the standard core shader five-file layout:

```text
assets/<namespace>/shaders/core/<name>.fsh
assets/<namespace>/shaders/core/<name>_block.vsh
assets/<namespace>/shaders/core/<name>_block.json
assets/<namespace>/shaders/core/<name>_entity.vsh
assets/<namespace>/shaders/core/<name>_entity.json
```

The fragment shader is shared by both profiles. The two vertex profiles are generated separately because Minecraft uses different vertex formats for different render targets:

- `<name>_block.*` uses `DefaultVertexFormat.BLOCK`, for skybox, plane preview, and Boss bar rendering.
- `<name>_entity.*` uses `DefaultVertexFormat.NEW_ENTITY`, for entity layers, item layers, and textured entity effects.

Export modes:

- `PORTABLE`: standard Minecraft core shader output with no ECA-specific uniforms.
- `PORTABLE_WITH_ECA_HINTS`: includes ECA uniform hooks with harmless defaults, while remaining usable without ECA.
- `ECA_ENHANCED`: includes ECA-specific uniforms and expects ECA's enhanced shader runtime.

Project files are saved under `config/eca/shadergenerator/<namespace>/<name>/project.json`. Use **File -> Export As <shader>** to export a runtime-loadable five-file preset into `config/eca/shadergenerator/<namespace>/<name>/`. ECA automatically discovers presets from both mod assets and exported config presets. A preset ID is always `<namespace>:<name>`.

For mod-packaged presets, place the five files under `src/main/resources/assets/<namespace>/shaders/core/`. You may also declare the preset with `@RegisterShaderPreset`, which registers the preset ID during startup scanning.

At runtime, obtain the generated RenderTypes through `EcaPresets`, or query the preset object through `EcaAPI.shaderPreset(id)`. The returned `ShaderPreset` exposes `bossBar()`, `bossLayer()`, `skybox()`, `item()`, `block()`, `geoBlock(texture)` and `entityForPreview(texture)`; `block()` and `geoBlock(texture)` are the BLOCK and NEW_ENTITY profiles used by block extensions. For entity texture overlays, use `EntityLayerExtension.getTexture()` with `bossLayer()`.

### BossShow Cinematics

BossShow plays a cutscene that locks the player's camera onto a pre-recorded path around a target entity, with subtitles and server-side event callbacks. Camera paths are recorded with the built-in in-game editor — you don't need to write keyframes by hand.

**Default Editor Keybindings**:

| Key | Action |
|-----|--------|
| `J` | Start / resume recording |
| `I` | Pause recording |
| `K` | Mark current frame as keyframe |
| `ENTER` | Save recording |
| `ESC` | Discard recording |

**Editor Workflow**:
1. Run `/eca bossShow edit` near at least one LivingEntity (within 64 blocks).
2. In the Home GUI, click **+ New cutscene from entity** → aim at an entity → right-click to select it as the camera anchor. Or click **Edit** on an existing cutscene.
3. Configure trigger type (Range / Custom), target entity type, cinematic bars, allow repeat, etc.
4. Click **● Record**, press `J` to start. Move the camera freely in spectator mode — each tick is captured as a frame. Press `K` to mark the current frame as a keyframe at any point.
5. Edit each keyframe's `event_id` (triggers server-side Java callbacks), `subtitle` (displayed text), and `curve` (easing from this keyframe to the next).
6. Press `ENTER` to save, `ESC` to discard. Saved files go to `config/eca/bossshow/<namespace>/<path>.json`.

**Timeline editing** (after recording, in the editor GUI): the bottom timeline bar shows the playhead, keyframe ticks, and the in/out range. Click or drag the bar to scrub — the camera previews that frame in first person. Use **Set In** / **Set Out** to mark a range, then **Copy** / **Cut** / **Delete** to operate on it, and **Paste** to insert the clipboard at the playhead. All edits ripple: frames after the cut shift to close the gap, since a frame's array index is its tick.

**For Mod Developers**

Two ways to define a cutscene:

1. **JSON only** — place a file at `data/<modid>/bossshow/<path>.json`. Loaded automatically on startup. No Java code needed if you don't need server-side event handling.

2. **Java + JSON** — extend `BossShow` and annotate with `@RegisterBossShow` to get server-side event callbacks during playback.

JSON example — `frames` are generated by the recorder; you typically only hand-edit the `keyframe` sub-objects:

```json
{
  "target_type": "minecraft:warden",
  "trigger": { "type": "range", "effect_radius": 32.0 },
  "cinematic": true,
  "allow_repeat": false,
  "anchor_yaw": 0.0,
  "frames": [
    { "dx": 0.0, "dy": 1.8, "dz": -6.0, "yaw": 0.0, "pitch": 10.0 },
    { "dx": 0.0, "dy": 1.8, "dz": -5.8, "yaw": 2.0, "pitch": 10.0,
      "keyframe": { "event_id": "intro", "subtitle": "mymod.bossshow.warden.intro", "curve": "ease_in_out" } },
    { "dx": 0.0, "dy": 1.8, "dz": -4.0, "yaw": 8.0, "pitch": 10.0,
      "keyframe": { "event_id": "finisher", "curve": "step" } }
  ]
}
```

- `frames`: one object per tick, in playback order. A frame's index in the array is its tick — there is no separate time field. Generated by the editor.
- `frames[].dx/dy/dz`: camera offset in anchor-local coordinates.
- `frames[].yaw/pitch`: camera orientation (yaw is anchor-local).
- `frames[].keyframe`: optional. Its presence marks this frame as a keyframe (an empty object `{}` is a valid bare keyframe). Fields inside:
  - `event_id`: delivered to `BossShow.onKeyframeEvent()` on the server. Optional.
  - `subtitle`: shown on the viewer's screen. Plain text or a translation key (see subtitle override below). Optional.
  - `curve`: playback easing from this keyframe to the next keyframe — `none` (default), `ease_in`, `ease_out`, `ease_in_out`, `ease_out_in`, `step`, `bezier`. Only affects camera interpolation speed, not event timing.
- `trigger`: `{"type":"range","effect_radius":N}` auto-triggers when a player enters range of a matching entity. `{"type":"custom","event_name":"..."}` only fires via `EcaAPI.launchBossShowEvent(...)`.

> The old `samples` + `markers` format is no longer recognized — files using it load as zero-frame cutscenes. Re-record or migrate to `frames`.

The `event_id` strings in the JSON above are dispatched to `onKeyframeEvent` on the server at the corresponding tick, and cutscenes can also be triggered from code through `EcaAPI.playBossShow(...)` / `launchBossShowEvent(...)`.

> If a `@RegisterBossShow` class has no matching JSON on first launch, an empty template JSON is auto-generated at `config/eca/bossshow/<namespace>/<path>.json`.

**For Modpack Developers**

- **Override cutscenes** — place your modified JSON at `config/eca/bossshow/<namespace>/<path>.json`. Config files override mod-bundled definitions (`data/<modid>/bossshow/`) with the same id.
- **Edit in-game** — `/eca bossShow edit` lets you re-record camera paths, adjust triggers, or re-time keyframes (split / copy / cut / delete / paste frame ranges on the timeline). Saves go to `config/eca/bossshow/`, leaving the mod jar untouched.
- **Translate or rewrite subtitles** — create `config/eca/bossshow/lang/<locale>.json` (e.g. `en_us.json`, `zh_cn.json`). These take priority over the mod's own lang files for subtitle keys:
    ```json
    {
      "mymod.bossshow.warden.intro": "A sound echoes from the deep..."
    }
    ```
- **Hot reload** — `/eca bossShow reload` picks up all JSON changes without restarting.

### Custom Factions

ECA provides a faction system that constrains targeting and damage relationships. Binding an entity makes vanilla alliance checks and target assignment respect same-faction, friendly and neutral rules without requiring an interface or mixin. Faction-bound mobs periodically acquire the nearest faction-bound entity with a `HOSTILE` relation through `Mob.setTarget`; their existing combat goals still perform movement and attacks, and factionless entities are never selected. Standard `LivingEntity` damage paths enforce friendly protection; direct state-changing APIs remain the caller's responsibility. `FactionUtil.isFriendly` resolves alliances, while `FactionUtil.canAttack` additionally enforces creative/spectator and ECA invulnerability protection.

`EcaAPI.isFriendly(a, b)` is the public complete friendly check. It returns true for the same ECA faction, friendly ECA factions, vanilla scoreboard allies, owner-pet pairs, pets with the same owner, and pets whose owners are scoreboard allies. Creative mode, spectator mode and ECA invulnerability are deliberately excluded because they are attack protections rather than alliance relationships. Use `areSameFaction` only when exact ECA faction identity matters; `canHarm` checks ECA faction relations only, while `canTarget` also rejects neutral relations and complete target immunity.

Factions are registered by extending `FactionDefinition` and annotating the class with `@RegisterFaction`. Definitions are scanned during `FMLLoadCompleteEvent`; duplicate ids are logged and skipped (first one scanned wins). Factions can also be created at runtime through `EcaAPI.createFaction`, with or without persistence.

Four relations are available:
- `SAME_FACTION` — same faction id, fully immune to each other and never targeted
- `FRIENDLY` — different factions but allied, no damage and no targeting
- `NEUTRAL` — not deliberately targeted, but incidental damage still applies
- `HOSTILE` — normal combat

Relation resolution runs in this order, and the first match wins:
1. Same faction id → `SAME_FACTION`
2. A's `getRelation(self, target)` conditional override
3. A's static `hostileTo` / `friendlyTo` / `neutralTo` arrays
4. Symmetric fallback — the same two checks evaluated from B's side
5. A's `getDefaultRelation(self, target)` conditional override (only when the other side has no faction)
6. A's static default relation

Each faction owns its member table. A member is recorded as a UUID plus its entity type, which means a roster can be listed, filtered by type and counted without loading a single entity — members sitting in unloaded chunks or other dimensions are still fully visible and manageable. Factions live in the overworld's SavedData, so membership is global across dimensions and survives restarts.

A binding is dropped when the entity is permanently removed; chunk unloads and dimension changes keep it, and players keep theirs across death and respawn. Membership cannot outlive its faction — unregistering a faction drops its whole member table, and joining a faction that does not exist is refused rather than silently recorded.

Tamed animals inherit their owner's faction automatically, so a pet is protected by its owner's allies and can answer nearby faction alerts. Inheritance is resolved at lookup time rather than stored: an inherited pet is not included in the persistent member table, offline queries, counts or table-wide leader propagation. It follows its owner across faction changes and never creates a binding of its own — calling `leaveFaction` on such a pet therefore does nothing. Bind a pet explicitly if it must belong elsewhere or participate in member-table operations; an explicit binding always takes precedence over inheritance.

A faction may optionally declare which entity types it consists of through `getMemberEntityTypes()`, mapping types to spawn weights. This lets other systems spawn "some members of this faction" without naming concrete types — the raid system uses it for faction-drawn waves.


**Leaders:** A faction may designate one member as its leader. Setting a leader adds it to the faction automatically if it was not a member — a leader outside its own faction would be a contradictory state. Leaving the faction also vacates the post, and a leader that is permanently removed is cleared automatically.

**Threat propagation:** When a leader attacks something, or is attacked, that entity is offered as the target of every resolvable mob in the faction member table. Existing targets and faction target permissions may still prevent a switch. Two mechanisms coexist:

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

**Waves:** Each `RaidWave` mixes two spawn sources freely — explicit entity entries, and faction draws that pull from a faction's `getMemberEntityTypes()` pool by weight.

**Raiders:** Spawned raiders are bound to `getRaiderFactionId()`. Spawned `Mob` instances also receive an injected goal that paths them to the raid center. The goal sits at priority 3 by default, matching vanilla's `PathfindToRaidGoal` — below the usual melee attack goal, so raiders fight an already acquired hostile-faction target and otherwise advance. Any entity type can be spawned and no interface is required, but non-`Mob` entities receive neither faction target acquisition, the navigation goal, nor mob callbacks. Override `getRaiderGoalPriority()` or return a negative value to change or disable goal injection.

**Boss:** A wave may declare a leader with `RaidWave.setLeader(type)`. The spawned entity becomes the leader of the raid's raider faction, so the faction's threat propagation applies to it for free. Eligible, loaded mobs without an existing target respond by default; `Immediate Leader Protection` allows them to replace an existing target. Declaring a leader requires `getRaiderFactionId()`; without a faction there is nothing to lead and the entry spawns as an ordinary raider.

Note that propagation walks the entire faction member table, not just this raid's participants. If the raider faction has other members elsewhere in the world, they answer too. Use a raid-specific faction if you want the response confined to the raid.

**Validation:** Starting a raid verifies the factions it references. A non-empty but unregistered raider faction refuses the start outright because the requested friendly-fire and alert rules could not be applied. Returning `null` intentionally is allowed and leaves each spawned entity governed by its own AI. A wave drawing from a faction that is unregistered or declares no member pool logs an error and skips that group, but the raid still starts.

**Progression:** `shouldAdvanceWave`, `checkVictory` and `checkDefeat` are all overridable. The defaults reproduce vanilla semantics: the next wave spawns once the previous one is dead, and the defenders win when every wave has spawned and every raider is gone.

**Timing and callbacks:** `getMaxDurationTicks()` defaults to 48000, `getWaveCooldownTicks()` to 300, `getParticipantRadius()` to 96 blocks and `getCelebrationTicks()` to 600. A wave can add its own `spawnDelay()` and `spawnRadius()`. Lifecycle hooks are `onStart`, `onWaveStart`, `onWaveEnd`, `onVictory`, `onDefeat` and `onStop`. Client-side `bossBarExtension()` can replace the raid bar appearance while retaining server-synced raid state.

**Endless raids:** `isEndless()` cycles the wave list forever and never satisfies the default victory condition. Finish one with `EcaAPI.endRaid`, which discards every surviving raider.

Raids run per dimension and automatically restore their latest periodic checkpoint after a restart. Permanent casualties and terminal operations are saved immediately; ordinary progression is checkpointed once per second. The center chunk is force-loaded for the duration, but raiders that travel into other unloaded chunks are not force-loaded with it.


Registering a definition does not start anything. Raids are started explicitly through `EcaAPI.startRaid(...)` / `startRaidAt(...)` so that any trigger condition can drive them — entering a region, using an item, a command, a scheduled event.


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

