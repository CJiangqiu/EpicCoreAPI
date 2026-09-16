package net.eca.pro.ingot;

import forgevm.forge.FvmCallback;
import forgevm.forge.FvmIngot;
import net.eca.pro.EcaProFallbackController;

public final class TransformerMonitorIngot extends FvmIngot {
    public TransformerMonitorIngot() {
        super("net.minecraft.server.MinecraftServer",
                "m_5705_(Ljava/util/function/BooleanSupplier;),tickServer(Ljava/util/function/BooleanSupplier;)",
                HEAD);
    }

    @Override
    public boolean includeSubclasses() {
        return true;
    }

    public static void onServerTick(FvmCallback callback) {
        EcaProFallbackController.pulse();
    }
}
