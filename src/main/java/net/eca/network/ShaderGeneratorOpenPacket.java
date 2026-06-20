package net.eca.network;

import net.eca.client.gui.ShaderGeneratorScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class ShaderGeneratorOpenPacket {

    public static void encode(ShaderGeneratorOpenPacket message, FriendlyByteBuf buffer) {
    }

    public static ShaderGeneratorOpenPacket decode(FriendlyByteBuf buffer) {
        return new ShaderGeneratorOpenPacket();
    }

    public static void handle(
        ShaderGeneratorOpenPacket message,
        Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
            Dist.CLIENT,
            () -> ClientHandler::open
        ));
        context.setPacketHandled(true);
    }

    private static final class ClientHandler {
        private static void open() {
            ShaderGeneratorScreen.open();
        }
    }
}
