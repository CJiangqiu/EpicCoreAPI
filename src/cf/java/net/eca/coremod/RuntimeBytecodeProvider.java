package net.eca.coremod;

/** Supplies optional transformed bytes when a runtime capture channel is available. */
public final class RuntimeBytecodeProvider {
    private RuntimeBytecodeProvider() {}

    public static byte[] get(Class<?> clazz) { return null; }

    public static byte[] getAnalysis(Class<?> clazz) { return null; }

    public static byte[] getAnalysisOrRuntime(Class<?> clazz) { return null; }

    public static int fingerprint(Class<?> clazz) { return 0; }
}
