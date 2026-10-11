package net.eca.client.music;

import net.eca.EcaMod;
import net.eca.util.EcaLogger;
import net.eca.util.entity_extension.CombatMusicExtension;
import net.eca.util.entity_extension.EntityExtensionClientState;
import net.eca.util.raid.RaidBarState;
import net.eca.util.raid.RaidClientState;
import net.eca.util.raid.RaidDefinition;
import net.eca.util.raid.RaidManager;
import net.eca.util.raid.RaidMusicExtension;
import net.eca.util.raid.RaidSafeAccess;
import net.eca.util.raid.RaidStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CombatMusicManager {

    private static final int CHECK_INTERVAL = 20;
    private static final Map<String, Set<ResourceLocation>> COMPLETED_TRACKS = new HashMap<>();
    private static final Set<Class<?>> LOGGED_FAILURES = new HashSet<>();
    private static SoundInstance currentMusic;
    private static Candidate currentCandidate;
    private static WeighedSoundEvents currentEvent;
    private static ResourceLocation currentDimension;
    private static boolean strictLockEnabled;
    private static ResourceLocation strictAllowedSoundId;
    private static int tickCounter;
    private static int playbackTicks;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }
        // 暂停由声音引擎处理，保留实例才能在恢复时继续播放。
        if (minecraft.isPaused()) {
            return;
        }
        ResourceLocation dimensionId = minecraft.level.dimension().location();
        if (!dimensionId.equals(currentDimension)) {
            reset();
            currentDimension = dimensionId;
        }
        if (tickCounter++ % CHECK_INTERVAL == 0) {
            EntityExtensionClientState.tickConditions(dimensionId, minecraft.level);
        }

        updatePlaybackCompletion(minecraft);
        Set<String> activeSources = new HashSet<>();
        List<Candidate> candidates = collectCandidates(minecraft, dimensionId, activeSources);
        COMPLETED_TRACKS.keySet().retainAll(activeSources);

        Candidate selected = null;
        for (Candidate candidate : candidates) {
            if (!candidate.settings().loop()
                    && COMPLETED_TRACKS.getOrDefault(candidate.key(), Set.of()).contains(candidate.settings().soundId())) {
                continue;
            }
            if (selected == null || precedes(candidate, selected)) {
                selected = candidate;
            }
        }
        if (selected == null) {
            stopCurrent(minecraft);
            clearStrictLock();
            return;
        }

        Settings settings = selected.settings();
        // 同一来源的参数变化也要生效，但状态快照更新不应反复重启曲目。
        if (currentCandidate != null && currentCandidate.key().equals(selected.key())
                && currentCandidate.settings().equals(settings) && currentMusic != null) {
            currentCandidate = selected;
            return;
        }

        stopCurrent(minecraft);
        currentCandidate = selected;
        currentEvent = minecraft.getSoundManager().getSoundEvent(settings.soundId());
        currentMusic = new SimpleSoundInstance(settings.soundId(), settings.source(), settings.volume(),
                settings.pitch(), SoundInstance.createUnseededRandom(), settings.loop(), 0,
                SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true);
        updateStrictLock(minecraft, settings.strict(), settings.soundId());
        minecraft.getSoundManager().play(currentMusic);
    }

    private static List<Candidate> collectCandidates(Minecraft minecraft, ResourceLocation dimensionId,
                                                     Set<String> activeSources) {
        List<Candidate> candidates = new ArrayList<>();
        CombatMusicExtension entityMusic = EntityExtensionClientState.getActiveMusic(dimensionId);
        EntityType<?> activeType = EntityExtensionClientState.getActiveType(dimensionId);
        boolean override = EntityExtensionClientState.isMusicOverridden(dimensionId);
        String entityKey = override ? "override:" + dimensionId
                : "entity:" + (activeType == null ? dimensionId : BuiltInRegistries.ENTITY_TYPE.getKey(activeType));
        if (entityMusic != null || override || activeType != null) {
            activeSources.add(entityKey);
        }
        Candidate entity = resolve(minecraft, entityKey, override ? 2 : 1, entityMusic);
        if (entity != null) {
            candidates.add(entity);
        }
        for (Map.Entry<UUID, RaidBarState> entry : RaidClientState.getBarStates().entrySet()) {
            RaidBarState state = entry.getValue();
            if (state.getStatus() != RaidStatus.ONGOING) {
                continue;
            }
            // 一次播放记录随参与关系保留，临时关闭音乐或切换波次不能将其清空。
            String raidKey = "raid:" + entry.getKey();
            activeSources.add(raidKey);
            RaidDefinition definition = RaidManager.getDefinition(state.getDefinitionId());
            RaidMusicExtension music = RaidSafeAccess.combatMusicExtension(definition, state);
            Candidate raid = resolve(minecraft, raidKey, 0, music);
            if (raid != null) {
                candidates.add(raid);
            }
        }
        return candidates;
    }

    private static Candidate resolve(Minecraft minecraft, String key, int group, CombatMusicExtension music) {
        if (music == null) {
            return null;
        }
        try {
            if (!music.enabled()) {
                return null;
            }
            ResourceLocation soundId = music.soundEventId();
            if (soundId == null || !BuiltInRegistries.SOUND_EVENT.containsKey(soundId)) {
                return null;
            }
            WeighedSoundEvents soundEvent = minecraft.getSoundManager().getSoundEvent(soundId);
            if (soundEvent == null || soundEvent.getWeight() <= 0) {
                return null;
            }
            SoundSource source = music.soundSource();
            float volume = music.volume();
            float pitch = music.pitch();
            if (source == null || !Float.isFinite(volume) || volume <= 0.0f
                    || !Float.isFinite(pitch) || pitch <= 0.0f) {
                return null;
            }
            int priority = music instanceof RaidMusicExtension raid ? raid.priority() : 0;
            return new Candidate(key, group, priority,
                    new Settings(soundId, source, volume, pitch, music.loop(), music.strictMusicLock()));
        } catch (Throwable t) {
            if (LOGGED_FAILURES.add(music.getClass())) {
                EcaLogger.error("Combat music settings failed; ignoring this request", t);
            }
            return null;
        }
    }

    private static boolean precedes(Candidate candidate, Candidate selected) {
        if (candidate.group() != selected.group()) {
            return candidate.group() > selected.group();
        }
        if (candidate.priority() != selected.priority()) {
            return candidate.priority() > selected.priority();
        }
        if (currentCandidate != null) {
            if (candidate.key().equals(currentCandidate.key())) {
                return true;
            }
            if (selected.key().equals(currentCandidate.key())) {
                return false;
            }
        }
        return candidate.key().compareTo(selected.key()) < 0;
    }

    private static void updatePlaybackCompletion(Minecraft minecraft) {
        if (currentMusic == null || currentCandidate == null) {
            return;
        }
        // 资源重载会清空声音引擎，不应把这种中断记成一次播放已完成。
        if (currentEvent != minecraft.getSoundManager().getSoundEvent(currentCandidate.settings().soundId())) {
            stopCurrent(minecraft);
            return;
        }
        if (++playbackTicks < CHECK_INTERVAL || minecraft.getSoundManager().isActive(currentMusic)) {
            return;
        }
        if (!currentCandidate.settings().loop()) {
            COMPLETED_TRACKS.computeIfAbsent(currentCandidate.key(), key -> new HashSet<>())
                    .add(currentCandidate.settings().soundId());
        }
        stopCurrent(minecraft);
    }

    private static void stopCurrent(Minecraft minecraft) {
        if (currentMusic != null) {
            minecraft.getSoundManager().stop(currentMusic);
        }
        currentMusic = null;
        currentCandidate = null;
        currentEvent = null;
        playbackTicks = 0;
    }

    public static boolean shouldBlockMusic(ResourceLocation soundId, SoundSource source) {
        return strictLockEnabled && source == SoundSource.MUSIC
                && (strictAllowedSoundId == null || !strictAllowedSoundId.equals(soundId));
    }

    private static void updateStrictLock(Minecraft minecraft, boolean strict, ResourceLocation allowedSoundId) {
        boolean enteringLock = strict && !strictLockEnabled;
        strictLockEnabled = strict;
        strictAllowedSoundId = strict ? allowedSoundId : null;
        if (enteringLock) {
            minecraft.getMusicManager().stopPlaying();
        }
    }

    private static void clearStrictLock() {
        strictLockEnabled = false;
        strictAllowedSoundId = null;
    }

    // 世界卸载与断线必须同时清理锁和一次播放记录。
    public static void reset() {
        stopCurrent(Minecraft.getInstance());
        clearStrictLock();
        COMPLETED_TRACKS.clear();
        LOGGED_FAILURES.clear();
        currentDimension = null;
        tickCounter = 0;
    }

    private record Settings(ResourceLocation soundId, SoundSource source, float volume,
                            float pitch, boolean loop, boolean strict) {}

    private record Candidate(String key, int group, int priority, Settings settings) {}

    private CombatMusicManager() {}
}
