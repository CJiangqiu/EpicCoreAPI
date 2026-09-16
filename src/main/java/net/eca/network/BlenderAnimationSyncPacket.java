package net.eca.network;

import net.eca.client.render.blender.BlenderAnimationClientState;
import net.eca.util.entity_extension.BlenderAnimationState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BlenderAnimationSyncPacket(UUID entityId, long revision, BlenderAnimationState state) {
    public static void encode(BlenderAnimationSyncPacket message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.entityId);
        buffer.writeLong(message.revision);
        buffer.writeBoolean(message.state != null);
        if (message.state == null) {
            return;
        }
        buffer.writeUtf(message.state.animation(), 256);
        buffer.writeLong(message.state.referenceGameTime());
        buffer.writeFloat(message.state.elapsedSeconds());
        buffer.writeFloat(message.state.speed());
        buffer.writeBoolean(message.state.loop());
        buffer.writeBoolean(message.state.paused());
    }

    public static BlenderAnimationSyncPacket decode(FriendlyByteBuf buffer) {
        UUID entityId = buffer.readUUID();
        long revision = buffer.readLong();
        if (!buffer.readBoolean()) {
            return new BlenderAnimationSyncPacket(entityId, revision, null);
        }
        String animation = buffer.readUtf(256);
        long referenceGameTime = buffer.readLong();
        float elapsedSeconds = buffer.readFloat();
        float speed = buffer.readFloat();
        boolean loop = buffer.readBoolean();
        boolean paused = buffer.readBoolean();
        BlenderAnimationState state = new BlenderAnimationState(animation, revision, referenceGameTime,
            elapsedSeconds, speed, loop, paused);
        return new BlenderAnimationSyncPacket(entityId, revision, state);
    }

    public static void handle(BlenderAnimationSyncPacket message, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> BlenderAnimationClientState.apply(message.entityId, message.revision, message.state));
        ctx.setPacketHandled(true);
    }
}
