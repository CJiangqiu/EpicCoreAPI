package net.eca.network;

import net.eca.client.ClientEntityUtil;
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
        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHandlerRef::open));
        context.setPacketHandled(true);
    }

    // 客户端引用委托给 @OnlyIn(Dist.CLIENT) 的 ClientEntityUtil
    private static final class ClientHandlerRef {
        static void open() {
            ClientEntityUtil.openShaderGeneratorScreen();
        }
    }
}
