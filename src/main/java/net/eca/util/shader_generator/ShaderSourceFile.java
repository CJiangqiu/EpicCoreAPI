package net.eca.util.shader_generator;

public enum ShaderSourceFile {
    FRAGMENT("fragment", ".fsh"),
    BLOCK_VERTEX("block_vertex", "_block.vsh"),
    BLOCK_JSON("block_json", "_block.json"),
    ENTITY_VERTEX("entity_vertex", "_entity.vsh"),
    ENTITY_JSON("entity_json", "_entity.json");

    private final String serializedName;
    private final String fileSuffix;

    ShaderSourceFile(String serializedName, String fileSuffix) {
        this.serializedName = serializedName;
        this.fileSuffix = fileSuffix;
    }

    public String serializedName() {
        return serializedName;
    }

    public String fileSuffix() {
        return fileSuffix;
    }

    public static ShaderSourceFile fromSerializedName(String value) {
        for (ShaderSourceFile file : values()) {
            if (file.serializedName.equals(value)) return file;
        }
        return null;
    }
}
