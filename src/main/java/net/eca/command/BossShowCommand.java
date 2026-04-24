package net.eca.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.eca.network.BossShowOpenEditorHomePacket;
import net.eca.network.NetworkHandler;
import net.eca.util.bossshow.BossShowDefinition;
import net.eca.util.bossshow.BossShowHistory;
import net.eca.util.bossshow.BossShowManager;
import net.eca.util.bossshow.BossShowPlaybackTracker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

//BossShow 演出系统命令
public class BossShowCommand {

    private static final SuggestionProvider<CommandSourceStack> ID_SUGGESTIONS = BossShowCommand::suggestIds;

    //存储进入编辑器前 gamemode 的 NBT 键
    private static final String NBT_ROOT = "eca_bossshow_editor";
    private static final String NBT_PREV_GAMEMODE = "prev_gamemode";

    //编辑器范围扫描半径（格）
    private static final double EDITOR_SCAN_RADIUS = 64.0;

    public static LiteralArgumentBuilder<CommandSourceStack> registerSubCommand() {
        return Commands.literal("bossShow")
            .then(Commands.literal("play")
                .then(Commands.argument("viewer", EntityArgument.player())
                    .then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                            .suggests(ID_SUGGESTIONS)
                            .executes(BossShowCommand::play)))))
            .then(Commands.literal("stop")
                .then(Commands.argument("viewer", EntityArgument.player())
                    .executes(BossShowCommand::stop)))
            .then(Commands.literal("reload")
                .executes(BossShowCommand::reload))
            .then(Commands.literal("list")
                .executes(BossShowCommand::list))
            .then(Commands.literal("clearHistory")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(BossShowCommand::clearHistory)))
            .then(Commands.literal("edit")
                .executes(BossShowCommand::edit))
            .then(Commands.literal("exit")
                .executes(BossShowCommand::exitEditor));
    }

    private static CompletableFuture<Suggestions> suggestIds(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        for (ResourceLocation id : BossShowManager.getAllDefinitions().keySet()) {
            builder.suggest(id.toString());
        }
        return builder.buildFuture();
    }

    private static int play(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer viewer = EntityArgument.getPlayer(ctx, "viewer");
            Entity target = EntityArgument.getEntity(ctx, "target");
            String idStr = StringArgumentType.getString(ctx, "id");

            if (!(target instanceof LivingEntity living)) {
                source.sendFailure(Component.literal("§cTarget must be a LivingEntity"));
                return 0;
            }

            ResourceLocation id = ResourceLocation.tryParse(idStr.trim());
            if (id == null) {
                source.sendFailure(Component.literal("§cInvalid cutscene id: " + idStr));
                return 0;
            }

            BossShowDefinition def = BossShowManager.get(id);
            if (def == null) {
                source.sendFailure(Component.literal("§cNo BossShow definition for id: " + id));
                return 0;
            }

            boolean ok = BossShowPlaybackTracker.start(viewer, living, def, true);
            if (ok) {
                source.sendSuccess(() -> Component.literal("§aStarted BossShow " + id + " for " + viewer.getName().getString()), true);
                return 1;
            } else {
                source.sendFailure(Component.literal("§cFailed to start (viewer may already have an active session)"));
                return 0;
            }
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError: " + e.getMessage()));
            return 0;
        }
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer viewer = EntityArgument.getPlayer(ctx, "viewer");
            BossShowPlaybackTracker.stop(viewer, false);
            source.sendSuccess(() -> Component.literal("§aStopped BossShow for " + viewer.getName().getString()), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError: " + e.getMessage()));
            return 0;
        }
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        BossShowManager.reload();
        int count = BossShowManager.definitionCount();
        source.sendSuccess(() -> Component.literal("§aReloaded " + count + " BossShow definition(s)"), true);
        return count;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Map<ResourceLocation, BossShowDefinition> all = BossShowManager.getAllDefinitions();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§eNo BossShow definitions loaded"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§aLoaded BossShows (" + all.size() + "):"), false);
        for (Map.Entry<ResourceLocation, BossShowDefinition> e : all.entrySet()) {
            BossShowDefinition def = e.getValue();
            String line = "§7 - §f" + e.getKey() + " §7(" + def.samples().size() + " samples, "
                + def.markers().size() + " markers, " + def.totalDurationTicks() + "t, trigger="
                + def.trigger().type() + ")";
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    private static int edit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();

            //64 格内必须存在至少一个 LivingEntity（不含玩家自身）
            AABB box = AABB.ofSize(player.position(), EDITOR_SCAN_RADIUS * 2, EDITOR_SCAN_RADIUS * 2, EDITOR_SCAN_RADIUS * 2);
            List<LivingEntity> nearby = player.serverLevel().getEntitiesOfClass(
                LivingEntity.class, box, e -> e != null && e != player && e.isAlive());
            if (nearby.isEmpty()) {
                source.sendFailure(Component.literal("§cNo LivingEntity within " + (int) EDITOR_SCAN_RADIUS + " blocks. Editor needs at least one nearby entity."));
                return 0;
            }

            //保存当前 gamemode 到 NBT，强制 SPECTATOR
            GameType prev = player.gameMode.getGameModeForPlayer();
            CompoundTag persistent = player.getPersistentData();
            CompoundTag root = persistent.getCompound(NBT_ROOT);
            root.putString(NBT_PREV_GAMEMODE, prev.getName());
            persistent.put(NBT_ROOT, root);
            if (prev != GameType.SPECTATOR) {
                player.setGameMode(GameType.SPECTATOR);
            }

            //发包打开 Home 界面，携带当前所有定义
            NetworkHandler.sendToPlayer(new BossShowOpenEditorHomePacket(BossShowManager.getAllDefinitions().values()), player);
            source.sendSuccess(() -> Component.literal("§aOpened BossShow editor"), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError: " + e.getMessage()));
            return 0;
        }
    }

    private static int exitEditor(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            boolean restored = restorePreviousGameMode(player);
            if (restored) {
                source.sendSuccess(() -> Component.literal("§aExited BossShow editor"), false);
                return 1;
            } else {
                source.sendFailure(Component.literal("§cNot in BossShow editor"));
                return 0;
            }
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError: " + e.getMessage()));
            return 0;
        }
    }

    //供 BossShowExitEditorPacket 复用：从 NBT 还原 gamemode 并清理 root
    public static boolean restorePreviousGameMode(ServerPlayer player) {
        CompoundTag persistent = player.getPersistentData();
        if (!persistent.contains(NBT_ROOT)) return false;
        CompoundTag root = persistent.getCompound(NBT_ROOT);
        String prevName = root.getString(NBT_PREV_GAMEMODE);
        GameType prev = GameType.byName(prevName, GameType.SURVIVAL);
        if (player.gameMode.getGameModeForPlayer() != prev) {
            player.setGameMode(prev);
        }
        persistent.remove(NBT_ROOT);
        return true;
    }

    private static int clearHistory(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
            BossShowHistory.clearPlayerHistory(player);
            source.sendSuccess(() -> Component.literal("§aCleared BossShow history for " + player.getName().getString()), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError: " + e.getMessage()));
            return 0;
        }
    }
}
