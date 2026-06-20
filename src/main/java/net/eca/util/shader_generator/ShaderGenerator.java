package net.eca.util.shader_generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class ShaderGenerator {

    private final StandardShaderSourceAssembler sourceAssembler;
    private final StandardShaderJsonGenerator jsonGenerator;

    public ShaderGenerator(StandardShaderSourceAssembler sourceAssembler, StandardShaderJsonGenerator jsonGenerator) {
        if (sourceAssembler == null) {
            throw new IllegalArgumentException("Shader source assembler must not be null");
        }
        if (jsonGenerator == null) {
            throw new IllegalArgumentException("Shader JSON generator must not be null");
        }
        this.sourceAssembler = sourceAssembler;
        this.jsonGenerator = jsonGenerator;
    }

    public static ShaderGenerator standard() {
        return new ShaderGenerator(
            new StandardShaderSourceAssembler(),
            new StandardShaderJsonGenerator()
        );
    }

    public ShaderExportBundle generate(Request request) {
        if (request == null) {
            throw new IllegalArgumentException("Shader generation request must not be null");
        }

        ShaderProject project = request.project();
        String coreDirectory = "assets/" + project.namespace() + "/shaders/core/";
        String fragmentProgram = project.resourceId();
        List<ShaderExportBundle.File> files = new ArrayList<>();

        files.add(new ShaderExportBundle.File(
            coreDirectory + project.path() + ".fsh",
            sourceAssembler.assembleFragment(project, request.exportMode())
        ));

        for (ShaderTargetProfile targetProfile : ShaderTargetProfile.values()) {
            if (!request.targetProfiles().contains(targetProfile)) {
                continue;
            }

            String targetPath = project.path() + "_" + targetProfile.resourceSuffix();
            String vertexProgram = project.namespace() + ":" + targetPath;
            files.add(new ShaderExportBundle.File(
                coreDirectory + targetPath + ".vsh",
                sourceAssembler.assembleVertex(project, request.exportMode(), targetProfile)
            ));
            files.add(new ShaderExportBundle.File(
                coreDirectory + targetPath + ".json",
                jsonGenerator.generate(
                    project,
                    request.exportMode(),
                    targetProfile,
                    vertexProgram,
                    fragmentProgram
                )
            ));
        }

        return new ShaderExportBundle(files);
    }

    public record Request(
        ShaderProject project,
        ShaderExportMode exportMode,
        Set<ShaderTargetProfile> targetProfiles
    ) {

        public Request {
            if (project == null) {
                throw new IllegalArgumentException("Shader project must not be null");
            }
            if (exportMode == null) {
                throw new IllegalArgumentException("Shader export mode must not be null");
            }
            if (targetProfiles == null || targetProfiles.isEmpty()) {
                throw new IllegalArgumentException("At least one shader target profile is required");
            }
            targetProfiles = Collections.unmodifiableSet(EnumSet.copyOf(targetProfiles));
        }
    }
}
