package net.eca.network;

import net.eca.EcaMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Network handler for ECA mod.
 * Manages network communication between server and client.
 */
@SuppressWarnings("removal")
public class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(EcaMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    private static int id() {
        return packetId++;
    }

    /**
     * Register all network packets.
     * Called during mod initialization.
     */
    public static void register() {
        CHANNEL.messageBuilder(ClientRemovePacket.class, id())
                .encoder(ClientRemovePacket::encode)
                .decoder(ClientRemovePacket::decode)
                .consumerMainThread(ClientRemovePacket::handle)
                .add();

        CHANNEL.messageBuilder(EntityExtensionActiveTypePacket.class, id())
                .encoder(EntityExtensionActiveTypePacket::encode)
                .decoder(EntityExtensionActiveTypePacket::decode)
                .consumerMainThread(EntityExtensionActiveTypePacket::handle)
                .add();

        CHANNEL.messageBuilder(EntityExtensionBossEventTypePacket.class, id())
                .encoder(EntityExtensionBossEventTypePacket::encode)
                .decoder(EntityExtensionBossEventTypePacket::decode)
                .consumerMainThread(EntityExtensionBossEventTypePacket::handle)
                .add();

        CHANNEL.messageBuilder(EntityHealthSyncPacket.class, id())
                .encoder(EntityHealthSyncPacket::encode)
                .decoder(EntityHealthSyncPacket::decode)
                .consumerMainThread(EntityHealthSyncPacket::handle)
                .add();

        CHANNEL.messageBuilder(EntityExtensionOverridePacket.class, id())
                .encoder(EntityExtensionOverridePacket::encode)
                .decoder(EntityExtensionOverridePacket::decode)
                .consumerMainThread(EntityExtensionOverridePacket::handle)
                .add();

        CHANNEL.messageBuilder(EntityContainerCheckRequestPacket.class, id())
                .encoder(EntityContainerCheckRequestPacket::encode)
                .decoder(EntityContainerCheckRequestPacket::decode)
                .consumerMainThread(EntityContainerCheckRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(EntityContainerCheckResponsePacket.class, id())
                .encoder(EntityContainerCheckResponsePacket::encode)
                .decoder(EntityContainerCheckResponsePacket::decode)
                .consumerNetworkThread(EntityContainerCheckResponsePacket::handle)
                .add();

        CHANNEL.messageBuilder(BossShowStartPacket.class, id())
                .encoder(BossShowStartPacket::encode)
                .decoder(BossShowStartPacket::decode)
                .consumerMainThread(BossShowStartPacket::handle)
                .add();

        CHANNEL.messageBuilder(BossShowStopPacket.class, id())
                .encoder(BossShowStopPacket::encode)
                .decoder(BossShowStopPacket::decode)
                .consumerMainThread(BossShowStopPacket::handle)
                .add();

        CHANNEL.messageBuilder(BossShowSkipPacket.class, id())
                .encoder(BossShowSkipPacket::encode)
                .decoder(BossShowSkipPacket::decode)
                .consumerMainThread(BossShowSkipPacket::handle)
                .add();

        CHANNEL.messageBuilder(BossShowOpenEditorHomePacket.class, id())
                .encoder(BossShowOpenEditorHomePacket::encode)
                .decoder(BossShowOpenEditorHomePacket::decode)
                .consumerMainThread(BossShowOpenEditorHomePacket::handle)
                .add();

        CHANNEL.messageBuilder(BossShowExitEditorPacket.class, id())
                .encoder(BossShowExitEditorPacket::encode)
                .decoder(BossShowExitEditorPacket::decode)
                .consumerMainThread(BossShowExitEditorPacket::handle)
                .add();

        CHANNEL.messageBuilder(BossShowSaveEditorPacket.class, id())
                .encoder(BossShowSaveEditorPacket::encode)
                .decoder(BossShowSaveEditorPacket::decode)
                .consumerMainThread(BossShowSaveEditorPacket::handle)
                .add();

        CHANNEL.messageBuilder(BossShowDeleteEditorPacket.class, id())
                .encoder(BossShowDeleteEditorPacket::encode)
                .decoder(BossShowDeleteEditorPacket::decode)
                .consumerMainThread(BossShowDeleteEditorPacket::handle)
                .add();

        CHANNEL.messageBuilder(BossShowPlaySelectionPacket.class, id())
                .encoder(BossShowPlaySelectionPacket::encode)
                .decoder(BossShowPlaySelectionPacket::decode)
                .consumerMainThread(BossShowPlaySelectionPacket::handle)
                .add();

        CHANNEL.messageBuilder(BossShowSubtitlePacket.class, id())
                .encoder(BossShowSubtitlePacket::encode)
                .decoder(BossShowSubtitlePacket::decode)
                .consumerMainThread(BossShowSubtitlePacket::handle)
                .add();
    }

    /**
     * Send a message to the server.
     * @param message the message to send
     */
    public static <MSG> void sendToServer(MSG message) {
        CHANNEL.sendToServer(message);
    }

    /**
     * Send a message to a specific player.
     * @param message the message to send
     * @param player the target player
     */
    public static <MSG> void sendToPlayer(MSG message, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /**
     * Send a message to all clients tracking the given entity.
     * @param message the message to send
     * @param entity the entity being tracked
     */
    public static <MSG> void sendToTrackingClients(MSG message, Entity entity) {
        if (entity.level() instanceof ServerLevel) {
            CHANNEL.send(
                    PacketDistributor.TRACKING_ENTITY.with(() -> entity),
                    message
            );
        }
    }

    /**
     * Send a message to all players in a specific dimension.
     * @param message the message to send
     * @param level the server level (dimension)
     */
    public static <MSG> void sendToDimension(MSG message, ServerLevel level) {
        CHANNEL.send(
                PacketDistributor.DIMENSION.with(() -> level.dimension()),
                message
        );
    }
}
