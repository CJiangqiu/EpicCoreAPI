package net.eca.network;

import net.eca.util.EcaLogger;
import net.eca.util.reflect.LwjglUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * LWJGL-based client entity removal packet.
 * Uses LWJGL low-level memory channel to bypass bytecode interception.
 */
public class LwjglClientRemovePacket {

    private final int entityId;

    public LwjglClientRemovePacket(int entityId) {
        this.entityId = entityId;
    }

    public static void encode(LwjglClientRemovePacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
    }

    public static LwjglClientRemovePacket decode(FriendlyByteBuf buf) {
        return new LwjglClientRemovePacket(buf.readInt());
    }

    public static void handle(LwjglClientRemovePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel clientLevel = minecraft.level;
            if (clientLevel != null) {
                Entity entity = clientLevel.getEntity(msg.entityId);
                if (entity != null) {
                    entity.onClientRemoval();
                    LwjglUtil.lwjglClientRemove(clientLevel, entity);
                } else {
                    EcaLogger.info("[LwjglClientRemovePacket] Entity not found (ID: {})", msg.entityId);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
