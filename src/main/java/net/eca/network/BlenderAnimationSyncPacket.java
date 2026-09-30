package net.eca.network;

import net.eca.blender.client.animation.BlenderAnimationClientState;
import net.eca.blender.animation.BlenderPlaybackState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record BlenderAnimationSyncPacket(UUID entityId, long revision, BlenderPlaybackState state) {
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
        BlenderPlaybackState state = new BlenderPlaybackState(animation, revision, referenceGameTime,
            elapsedSeconds, speed, loop, paused);
        return new BlenderAnimationSyncPacket(entityId, revision, state);
    }

    public static void handle(BlenderAnimationSyncPacket message, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.apply(message)));
        ctx.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static final class ClientHandler {
        private static void apply(BlenderAnimationSyncPacket message) {
            BlenderAnimationClientState.apply(message.entityId, message.revision, message.state);
        }
    }
}
