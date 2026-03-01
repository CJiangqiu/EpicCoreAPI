package net.eca.client.music;

import net.eca.EcaMod;
import net.eca.util.entity_extension.CombatMusicExtension;
import net.eca.util.entity_extension.EntityExtensionClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CombatMusicManager {

    private static final int CHECK_INTERVAL = 20;

    private static SoundInstance currentMusic;
    private static ResourceLocation currentSoundId;
    private static boolean strictLockEnabled;
    private static ResourceLocation strictAllowedSoundId;
    private static int tickCounter;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.isPaused()) {
            clearStrictLock();
            stopCurrent(minecraft);
            return;
        }

        tickCounter++;
        if (tickCounter < CHECK_INTERVAL) {
            return;
        }
        tickCounter = 0;

        ResourceLocation dimensionId = minecraft.level.dimension().location();
        CombatMusicExtension musicExtension = EntityExtensionClientState.getActiveMusic(dimensionId);
        if (musicExtension == null || !musicExtension.enabled()) {
            clearStrictLock();
            stopCurrent(minecraft);
            return;
        }

        ResourceLocation soundId = musicExtension.soundEventId();
        if (soundId == null) {
            clearStrictLock();
            stopCurrent(minecraft);
            return;
        }

        updateStrictLock(minecraft, musicExtension.strictMusicLock(), soundId);

        SoundEvent soundEvent = BuiltInRegistries.SOUND_EVENT.getOptional(soundId).orElse(null);
        if (soundEvent == null) {
            stopCurrent(minecraft);
            return;
        }

        if (currentMusic != null && currentSoundId != null && currentSoundId.equals(soundId)) {
            if (minecraft.getSoundManager().isActive(currentMusic)) {
                return;
            }
        }

        stopCurrent(minecraft);
        currentMusic = new SimpleSoundInstance(
            soundEvent.getLocation(),
            musicExtension.soundSource(),
            musicExtension.volume(),
            musicExtension.pitch(),
            SoundInstance.createUnseededRandom(),
            musicExtension.loop(),
            0,
            SoundInstance.Attenuation.NONE,
            player.getX(),
            player.getY(),
            player.getZ(),
            false
        );
        minecraft.getSoundManager().play(currentMusic);
        currentSoundId = soundId;
    }

    private static void stopCurrent(Minecraft minecraft) {
        if (minecraft == null) {
            return;
        }
        if (currentMusic != null) {
            minecraft.getSoundManager().stop(currentMusic);
            currentMusic = null;
        }
        currentSoundId = null;
    }

    public static boolean shouldBlockMusic(ResourceLocation soundId, SoundSource source) {
        if (!strictLockEnabled) {
            return false;
        }
        if (source != SoundSource.MUSIC) {
            return false;
        }
        return strictAllowedSoundId == null || !strictAllowedSoundId.equals(soundId);
    }

    private static void updateStrictLock(Minecraft minecraft, boolean strict, ResourceLocation allowedSoundId) {
        strictLockEnabled = strict;
        strictAllowedSoundId = strict ? allowedSoundId : null;
        if (strict && minecraft != null) {
            minecraft.getMusicManager().stopPlaying();
        }
    }

    private static void clearStrictLock() {
        strictLockEnabled = false;
        strictAllowedSoundId = null;
    }

    private CombatMusicManager() {
    }
}
