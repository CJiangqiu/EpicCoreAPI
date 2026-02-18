package net.eca.network;

import net.eca.util.EntityUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server-to-client packet for syncing entity health changes.
 * Ensures modded entities with custom health storage get updated on the client.
 */
public class EntityHealthSyncPacket {

    private final int entityId;
    private final float health;

    public EntityHealthSyncPacket(int entityId, float health) {
        this.entityId = entityId;
        this.health = health;
    }

    public static void encode(EntityHealthSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeFloat(msg.health);
    }

    public static EntityHealthSyncPacket decode(FriendlyByteBuf buf) {
        return new EntityHealthSyncPacket(buf.readInt(), buf.readFloat());
    }

    public static void handle(EntityHealthSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            ClientLevel clientLevel = minecraft.level;
            if (clientLevel == null) return;

            Entity entity = clientLevel.getEntity(msg.entityId);
            if (entity instanceof LivingEntity livingEntity) {
                EntityUtil.setHealthFromSync(livingEntity, msg.health);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
