package net.eca.pro;

import net.eca.coremod.RuntimeExtensionBridge;
import net.minecraftforge.fml.loading.ImmediateWindowProvider;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

// This service is loaded before transformation services, closing the unprotected startup window.
public final class EcaProImmediateWindowProvider implements ImmediateWindowProvider {
    static {
        RuntimeExtensionBridge.prepareEarly();
        RuntimeExtensionBridge.afterAgentReady();
    }

    @Override
    public String name() {
        return "eca_pro_bootstrap";
    }

    @Override
    public Runnable initialize(String[] arguments) {
        return () -> {
        };
    }

    @Override
    public void updateFramebufferSize(IntConsumer width, IntConsumer height) {
    }

    @Override
    public long setupMinecraftWindow(IntSupplier width, IntSupplier height, Supplier<String> title,
                                     LongSupplier monitor) {
        return 0L;
    }

    @Override
    public boolean positionWindow(Optional<Object> monitor, IntConsumer widthSetter, IntConsumer heightSetter,
                                  IntConsumer xSetter, IntConsumer ySetter) {
        return false;
    }

    @Override
    public <T> Supplier<T> loadingOverlay(Supplier<?> minecraft, Supplier<?> resourceInitializer,
                                          Consumer<Optional<Throwable>> exceptionHandler, boolean fade) {
        return () -> null;
    }

    @Override
    public void updateModuleReads(ModuleLayer layer) {
    }

    @Override
    public void periodicTick() {
    }

    @Override
    public String getGLVersion() {
        return "3.2";
    }
}
