package net.eca.network;

import net.eca.util.bossshow.BossShowDefinition;
import net.eca.util.bossshow.BossShowManager;
import net.eca.util.bossshow.BossShowPlaybackTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

//C→S：玩家在编辑器 Home 点击 Play 后，对选中的实体请求播放某个 cutscene
public class BossShowPlaySelectionPacket {

    //和 /eca bossShow edit 保持一致的 64 格扫描半径
    private static final double SCAN_RADIUS = 64.0;

    private final ResourceLocation defId;
    private final UUID targetUuid;

    public BossShowPlaySelectionPacket(ResourceLocation defId, UUID targetUuid) {
        this.defId = defId;
        this.targetUuid = targetUuid;
    }

    public static void encode(BossShowPlaySelectionPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.defId);
        buf.writeUUID(msg.targetUuid);
    }

    public static BossShowPlaySelectionPacket decode(FriendlyByteBuf buf) {
        return new BossShowPlaySelectionPacket(buf.readResourceLocation(), buf.readUUID());
    }

    public static void handle(BossShowPlaySelectionPacket msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;

            BossShowDefinition def = BossShowManager.get(msg.defId);
            if (def == null) {
                player.sendSystemMessage(Component.literal("§cNo BossShow definition for id: " + msg.defId));
                return;
            }

            //在玩家所在维度按 64 格半径查找目标实体
            ServerLevel level = player.serverLevel();
            AABB box = AABB.ofSize(player.position(), SCAN_RADIUS * 2, SCAN_RADIUS * 2, SCAN_RADIUS * 2);
            List<LivingEntity> nearby = level.getEntitiesOfClass(
                LivingEntity.class, box,
                e -> e != null && e.isAlive() && e.getUUID().equals(msg.targetUuid));

            if (nearby.isEmpty()) {
                player.sendSystemMessage(Component.literal("§cTarget entity not found within " + (int) SCAN_RADIUS + " blocks"));
                return;
            }

            LivingEntity target = nearby.get(0);
            boolean ok = BossShowPlaybackTracker.start(player, target, def, true);
            if (!ok) {
                player.sendSystemMessage(Component.literal("§cFailed to start BossShow (already playing or empty definition)"));
            }
        });
        ctx.setPacketHandled(true);
    }
}
