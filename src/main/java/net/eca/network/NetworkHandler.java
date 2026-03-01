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
        CHANNEL.messageBuilder(EcaClientRemovePacket.class, id())
                .encoder(EcaClientRemovePacket::encode)
                .decoder(EcaClientRemovePacket::decode)
                .consumerMainThread(EcaClientRemovePacket::handle)
                .add();

        CHANNEL.messageBuilder(LwjglClientRemovePacket.class, id())
                .encoder(LwjglClientRemovePacket::encode)
                .decoder(LwjglClientRemovePacket::decode)
                .consumerMainThread(LwjglClientRemovePacket::handle)
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
