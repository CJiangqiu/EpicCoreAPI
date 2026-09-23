package net.eca.coremod;

/** Keeps optional extension hooks inert in the basic build. */
public final class RuntimeExtensionBridge {
    public static final String EARLY_DISPLAY_TARGET = "net/minecraftforge/fml/earlydisplay/DisplayWindow";

    private RuntimeExtensionBridge() {}

    public static boolean hasEarlyDisplayTransformer() { return false; }

    public static byte[] transformEarlyDisplay(byte[] classBytes) { return null; }

    public static void refreshInterception() {}

    public static void onLoadComplete() {}
}
