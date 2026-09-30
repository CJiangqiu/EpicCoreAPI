package net.eca.blender.client.runtime;

import java.io.IOException;
import java.util.Map;

interface BlendGeometryModifier {
    boolean dynamic();

    BlendGeometry evaluate(BlendGeometry input, float seconds, float fps, float startFrame,
                           Map<String, Float> parameters) throws IOException;
}
