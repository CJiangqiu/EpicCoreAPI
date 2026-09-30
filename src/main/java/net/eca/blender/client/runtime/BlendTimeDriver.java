package net.eca.blender.client.runtime;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.eca.blender.client.runtime.BlendFile.require;

/** A closed arithmetic grammar, never a script interpreter. */
record BlendTimeDriver(float divisor) {
    private static final Pattern EXPRESSION = Pattern.compile(
        "\\s*frame\\s*(?:/\\s*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?))?\\s*");

    static BlendTimeDriver read(BlendFile file, BlendFile.View curve) throws IOException {
        BlendFile.View driver = curve.ref("driver");
        require(driver != null && driver.integer("type") == 1 && driver.list("variables").isEmpty(),
            "Time drivers require a scripted expression without variables");
        require(curve.integer("array_index") == 0
            && curve.list("modifiers").isEmpty() && (curve.integer("flag") & (16 | 1024)) == 0,
            "Time drivers require an enabled scalar channel without modifiers");
        validateMapping(file, curve);
        Matcher expression = EXPRESSION.matcher(driver.text("expression"));
        require(expression.matches(), "Unsupported time driver expression; expected frame or frame / constant");
        float divisor = expression.group(1) == null ? 1 : Float.parseFloat(expression.group(1));
        require(Float.isFinite(divisor) && divisor != 0 && Float.isFinite(1 / divisor), "Invalid time driver divisor");
        return new BlendTimeDriver(divisor);
    }

    private static void validateMapping(BlendFile file, BlendFile.View curve) throws IOException {
        int count = curve.integer("totvert");
        if (count == 0) return;
        // Default driver curves serialize an identity mapping with two Bezier keys.
        require(count == 2 && curve.integer("extend") == 1 && curve.ptr("bezt") != 0
            && curve.ptr("fpt") == 0, "Time driver FCurve must be an identity mapping");
        float previous = Float.NEGATIVE_INFINITY;
        for (BlendFile.View key : file.array(curve.ptr("bezt"), count)) {
            float[] v = key.floats("vec", 9);
            int interpolation = key.integer("ipo");
            require((interpolation == 1 || interpolation == 2) && Float.isFinite(v[3])
                && v[3] == v[4] && v[3] > previous, "Non-identity time driver keys");
            if (interpolation == 2) {
                require(Float.isFinite(v[0]) && Float.isFinite(v[6]) && v[0] == v[1] && v[6] == v[7]
                    && v[0] < v[3] && v[6] > v[3], "Non-identity time driver handles");
            }
            previous = v[3];
        }
    }

    float evaluate(float frame) throws IOException {
        float result = frame / divisor;
        require(Float.isFinite(result), "Non-finite time driver result");
        return result;
    }
}
