package net.eca.util.shader_generator;

public enum ShaderExportMode {
    PORTABLE(false, false),
    ECA_ENHANCED(true, true),
    PORTABLE_WITH_ECA_HINTS(true, false);

    private final boolean includesEcaUniforms;
    private final boolean requiresEca;

    ShaderExportMode(boolean includesEcaUniforms, boolean requiresEca) {
        this.includesEcaUniforms = includesEcaUniforms;
        this.requiresEca = requiresEca;
    }

    public boolean includesEcaUniforms() {
        return includesEcaUniforms;
    }

    public boolean requiresEca() {
        return requiresEca;
    }
}
