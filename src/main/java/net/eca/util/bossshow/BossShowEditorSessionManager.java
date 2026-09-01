package net.eca.util.bossshow;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

//服务端编辑会话是模式恢复的唯一依据。
public final class BossShowEditorSessionManager {

    private static final String NBT_ROOT = "eca_bossshow_editor";
    private static final String NBT_PREV_GAMEMODE = "prev_gamemode";
    private static final int HEARTBEAT_TIMEOUT_TICKS = 200;
    private static final Map<UUID, Integer> LAST_HEARTBEAT = new HashMap<>();

    private BossShowEditorSessionManager() {}

    //重复打开编辑器时保留第一次进入前的模式，避免旁观模式覆盖原快照。
    public static void begin(ServerPlayer player) {
        CompoundTag persistent = player.getPersistentData();
        if (!persistent.contains(NBT_ROOT, Tag.TAG_COMPOUND)) {
            CompoundTag root = new CompoundTag();
            root.putString(NBT_PREV_GAMEMODE, player.gameMode.getGameModeForPlayer().getName());
            persistent.put(NBT_ROOT, root);
        }
        if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            player.setGameMode(GameType.SPECTATOR);
        }
        LAST_HEARTBEAT.put(player.getUUID(), player.server.getTickCount());
    }

    public static boolean isActive(ServerPlayer player) {
        return player.getPersistentData().contains(NBT_ROOT, Tag.TAG_COMPOUND);
    }

    //先清除会话标记再切换模式，使重复退出保持幂等。
    public static boolean end(ServerPlayer player) {
        CompoundTag persistent = player.getPersistentData();
        LAST_HEARTBEAT.remove(player.getUUID());
        if (!persistent.contains(NBT_ROOT, Tag.TAG_COMPOUND)) return false;

        CompoundTag root = persistent.getCompound(NBT_ROOT);
        GameType previous = GameType.byName(root.getString(NBT_PREV_GAMEMODE), GameType.SURVIVAL);
        persistent.remove(NBT_ROOT);
        if (player.gameMode.getGameModeForPlayer() != previous) {
            player.setGameMode(previous);
        }
        return true;
    }

    public static void heartbeat(ServerPlayer player) {
        if (isActive(player)) {
            LAST_HEARTBEAT.put(player.getUUID(), player.server.getTickCount());
        }
    }

    //登录时存在标记说明上次会话未正常退出，应优先恢复原模式。
    public static void recoverStaleSession(ServerPlayer player) {
        if (isActive(player)) {
            end(player);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        int now = server.getTickCount();
        Iterator<Map.Entry<UUID, Integer>> iterator = LAST_HEARTBEAT.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            if (now - entry.getValue() <= HEARTBEAT_TIMEOUT_TICKS) continue;
            iterator.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                end(player);
            }
        }
    }

    public static void clear() {
        LAST_HEARTBEAT.clear();
    }
}
