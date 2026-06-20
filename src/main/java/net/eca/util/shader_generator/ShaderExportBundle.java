package net.eca.util.shader_generator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ShaderExportBundle {

    private final List<File> files;
    private final Map<String, File> filesByPath;

    public ShaderExportBundle(List<File> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Shader export bundle must contain at least one file");
        }

        Map<String, File> indexedFiles = new LinkedHashMap<>();
        for (File file : files) {
            if (file == null) {
                throw new IllegalArgumentException("Shader export bundle must not contain null files");
            }
            if (indexedFiles.putIfAbsent(file.relativePath(), file) != null) {
                throw new IllegalArgumentException("Duplicate generated file path: " + file.relativePath());
            }
        }

        this.files = List.copyOf(files);
        this.filesByPath = Collections.unmodifiableMap(indexedFiles);
    }

    public List<File> files() {
        return files;
    }

    public File file(String relativePath) {
        return filesByPath.get(relativePath);
    }

    public record File(String relativePath, String content) {

        public File {
            if (relativePath == null || relativePath.isBlank()) {
                throw new IllegalArgumentException("Generated file path must not be blank");
            }
            if (relativePath.indexOf('\\') >= 0 || relativePath.startsWith("/") || relativePath.contains("..")) {
                throw new IllegalArgumentException("Generated file path must be a safe relative resource path");
            }
            if (content == null) {
                throw new IllegalArgumentException("Generated file content must not be null");
            }
        }
    }
}
