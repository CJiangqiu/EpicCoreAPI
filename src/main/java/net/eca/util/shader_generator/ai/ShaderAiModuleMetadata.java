package net.eca.util.shader_generator.ai;

import net.eca.util.shader_generator.ShaderModuleDefinition;

public final class ShaderAiModuleMetadata {

    public static String moduleDescription(String moduleId) {
        return switch (moduleId) {
            case "basic_circle" -> "A filled circular shape for simple masks and solid accents.";
            case "basic_ring" -> "A circular outline whose thickness is relative to its size.";
            case "basic_line" -> "A centered straight line controlled by relative length and thickness.";
            case "basic_rectangle" -> "A filled, axis-aligned rectangular shape.";
            case "basic_polygon" -> "A filled regular polygon with a selectable side count.";
            case "basic_ellipse" -> "A filled ellipse with independent width and height scaling.";
            case "basic_star" -> "A filled regular star with selectable points and inner radius.";
            case "cross_star" -> "A luminous four-ray sparkle suitable for sparse bright highlights.";
            case "dot_star" -> "A soft circular star suitable for dense background star fields.";
            case "image_element" -> "A project PNG rendered as an element; assign its image separately.";
            case "spiral" -> "A rotating-looking spiral galaxy mask with arms and a luminous core.";
            case "elliptical_galaxy" -> "A smooth elliptical galaxy glow with configurable flattening.";
            case "supernova" -> "A bright stellar burst combining a core, radial rays, and a halo.";
            case "energy_ring" -> "An animated ring that expands outward and repeats.";
            case "meteor" -> "A directional meteor head with a narrow luminous trail.";
            case "nebula_haze" -> "An FBM cloud field; higher density thresholds make the visible cloud sparser, not more opaque.";
            case "black_hole" -> "An opaque dark body that occludes a tilted rotating accretion disk and photon ring. Place it on a dedicated NORMAL layer so the event horizon can hide lower layers; ADD cannot create darkness.";
            case "lightning" -> "A segmented jagged lightning bolt with optional branch energy.";
            case "aurora" -> "A flowing curtain-like luminous band.";
            case "fireflies" -> "Small wandering points with animated glow.";
            case "water_bubbles" -> "Rising outlined bubbles with horizontal sway.";
            case "toxic_bubbles" -> "Rising irregular glowing bubbles for toxic or magical fluids.";
            case "rain_streaks" -> "Falling narrow rain lines with adjustable length and width.";
            case "snowfall" -> "Falling soft flakes affected by horizontal wind.";
            case "falling_leaves" -> "Fluttering leaf-like fragments moving downward.";
            case "magma_debris" -> "Drifting polygonal fragments with emissive strength.";
            case "dust_haze" -> "A moving broad noise field; its density multiplies brightness.";
            case "digital_rain" -> "Falling narrow digital trails resembling cascading glyphs.";
            case "rune" -> "One Elder Futhark rune selected by its integer index.";
            case "planet_symbol" -> "One astronomical or alchemical planet symbol selected by index.";
            default -> "A reusable ECA visual shader module.";
        };
    }

    public static Guidance parameterGuidance(
        String moduleId,
        ShaderModuleDefinition.Parameter parameter
    ) {
        String key = parameter.key();
        float recommendedMinimum = parameter.minimum();
        float recommendedMaximum = parameter.maximum();
        String description = parameterDescription(moduleId, key);

        if ("size".equals(key)) {
            float[] range = sizeRange(moduleId, parameter);
            recommendedMinimum = range[0];
            recommendedMaximum = range[1];
        } else if ("count".equals(key)) {
            float[] range = countRange(moduleId, parameter);
            recommendedMinimum = range[0];
            recommendedMaximum = range[1];
        } else if ("nebula_haze".equals(moduleId) && "density".equals(key)) {
            recommendedMinimum = 0.4F;
            recommendedMaximum = 0.58F;
        } else if ("nebula_haze".equals(moduleId) && "inner_radius".equals(key)) {
            recommendedMinimum = 0.0F;
            recommendedMaximum = 0.3F;
        } else if ("nebula_haze".equals(moduleId) && "outer_radius".equals(key)) {
            recommendedMinimum = 1.0F;
            recommendedMaximum = 2.0F;
        } else if (key.startsWith("color_")) {
            recommendedMinimum = "color_a".equals(key) ? 0.35F : 0.0F;
            recommendedMaximum = 1.0F;
        } else if ("center_x".equals(key) || "center_y".equals(key)) {
            recommendedMinimum = 0.15F;
            recommendedMaximum = 0.85F;
        } else if ("spread_x".equals(key) || "spread_y".equals(key)) {
            recommendedMinimum = 0.0F;
            recommendedMaximum = 0.5F;
        } else if ("start_alpha".equals(key) || "end_alpha".equals(key)) {
            recommendedMinimum = 0.35F;
            recommendedMaximum = 1.0F;
        }
        return new Guidance(
            description,
            parameter.clamp(recommendedMinimum),
            parameter.clamp(recommendedMaximum)
        );
    }

    private static String parameterDescription(String moduleId, String key) {
        if ("nebula_haze".equals(moduleId)) {
            return switch (key) {
                case "density" -> "FBM visibility threshold; increasing it makes the cloud sparser and can make the nebula disappear.";
                case "swirl_strength" -> "Amount of angular bright-dark modulation; zero disables the swirl modulation.";
                case "swirl_freq" -> "Number of angular brightness waves around each cloud instance.";
                case "inner_radius" -> "Inner radial fade start; larger values create a wider transparent center.";
                case "outer_radius" -> "Outer radial extent relative to element size before the cloud fades out.";
                default -> commonParameterDescription(key);
            };
        }
        if ("dust_haze".equals(moduleId) && "density".equals(key)) {
            return "Brightness multiplier for the generated dust cloud; unlike nebula_haze, increasing this value strengthens the result.";
        }
        return commonParameterDescription(key);
    }

    private static String commonParameterDescription(String key) {
        return switch (key) {
            case "color_r" -> "Red output channel intensity.";
            case "color_g" -> "Green output channel intensity.";
            case "color_b" -> "Blue output channel intensity.";
            case "color_a" -> "Per-element opacity before layer blending.";
            case "size" -> "Relative extent of each generated instance in normalized preview space; the useful scale depends on the module.";
            case "count" -> "Number of generated instances; high counts increase shader cost and overlap.";
            case "center_x", "center_y" -> "Normalized instance-group center coordinate: 0 is one edge and 1 is the opposite edge.";
            case "spread_x", "spread_y" -> "Maximum random offset from the group center on this axis.";
            case "rotation" -> "Clockwise orientation in degrees.";
            case "seed" -> "Deterministic variation seed; changing it rearranges instances without changing their style.";
            case "duration" -> "Visible phase duration in seconds; zero keeps the effect continuously active.";
            case "repeat_interval" -> "Invisible pause after each visible phase; zero removes the pause.";
            case "start_alpha", "end_alpha" -> "Opacity at the beginning or end of the visible animation phase.";
            case "thickness" -> "Relative stroke or band thickness; increasing it makes the shape broader.";
            case "length" -> "Relative longitudinal length.";
            case "width", "height" -> "Relative shape scale on this axis.";
            case "sides" -> "Integer side count of the regular polygon.";
            case "points" -> "Integer point count of the regular star.";
            case "inner_ratio" -> "Inner-to-outer radius ratio; larger values make star points shallower.";
            case "arm_count" -> "Integer number of spiral arms.";
            case "twist" -> "Spiral winding amount; increasing it curls the arms more strongly.";
            case "sharpness" -> "Contrast of the generated structure; larger values produce narrower, harder features.";
            case "core_brightness" -> "Brightness contribution of the central core.";
            case "axis_ratio" -> "Minor-to-major axis ratio; smaller values make the galaxy flatter.";
            case "falloff" -> "Radial fade strength; larger values make the glow more compact.";
            case "ray_count" -> "Integer number of radial burst rays.";
            case "ray_width" -> "Angular width of each radial ray.";
            case "halo_strength" -> "Strength of the broad glow surrounding the core.";
            case "ring_speed" -> "Rate at which the animated ring expands.";
            case "ring_thickness" -> "Width of the ring outline relative to its scale.";
            case "max_radius" -> "Maximum expansion radius before the ring animation repeats.";
            case "angle" -> "Travel or orientation angle in degrees.";
            case "trail_length" -> "Length of the trailing segment behind its head.";
            case "trail_width" -> "Width of the trailing segment.";
            case "head_size" -> "Radius of the bright leading head.";
            case "disk_r", "disk_g", "disk_b" -> "Accretion-disk color channel intensity.";
            case "photon_r", "photon_g", "photon_b" -> "Photon-ring color channel intensity.";
            case "disk_tilt" -> "Vertical flattening or viewing tilt of the accretion disk.";
            case "disk_thickness" -> "Thickness of the visible accretion band.";
            case "disk_rotation_speed" -> "Angular animation speed of the accretion structure.";
            case "edge_softness" -> "Softness of the dark-body boundary.";
            case "segments" -> "Integer number of line segments used to form the bolt.";
            case "jitter" -> "Random displacement of successive bolt segments.";
            case "branch_strength" -> "Intensity and reach of secondary lightning branches.";
            case "frequency" -> "Number of repeating waves across the effect.";
            case "flow_speed" -> "Animation speed of the flowing noise or band.";
            case "wander" -> "Amount of animated positional wandering.";
            case "glow_strength" -> "Brightness multiplier for the luminous contribution.";
            case "rise_speed" -> "Upward animation speed.";
            case "sway_amount" -> "Horizontal side-to-side movement amplitude.";
            case "fall_speed" -> "Downward animation speed.";
            case "streak_length" -> "Relative length of each falling streak.";
            case "streak_width" -> "Relative width of each falling streak.";
            case "wind_amount" -> "Horizontal drift applied while falling.";
            case "flake_softness" -> "Edge softness of each snowflake; larger values produce blurrier flakes.";
            case "flutter" -> "Side-to-side oscillation strength while falling.";
            case "leaf_width" -> "Relative width of each falling leaf fragment.";
            case "drift_speed" -> "Animation speed of drifting fragments.";
            case "fragment_sides" -> "Integer polygon side count of each fragment.";
            case "density" -> "Module-specific coverage or intensity control; read the module description before changing it.";
            case "haze_softness" -> "Transition softness of the noise threshold.";
            case "glyph_width" -> "Relative width of each digital-rain trail.";
            case "rune_index" -> "Integer Elder Futhark rune selector from 0 through 23.";
            case "symbol_index" -> "Integer astronomical symbol selector.";
            default -> "Module parameter; begin with its default and adjust incrementally while checking the preview.";
        };
    }

    private static float[] sizeRange(
        String moduleId,
        ShaderModuleDefinition.Parameter parameter
    ) {
        if ("dot_star".equals(moduleId)) return bounded(parameter, 0.02F, 0.06F);
        if ("cross_star".equals(moduleId)) return bounded(parameter, 0.05F, 0.12F);
        if ("nebula_haze".equals(moduleId)) return bounded(parameter, 0.25F, 0.8F);
        if ("image_element".equals(moduleId)) return bounded(parameter, 0.2F, 1.0F);
        return bounded(
            parameter,
            Math.max(parameter.minimum(), parameter.defaultValue() * 0.5F),
            Math.min(parameter.maximum(), parameter.defaultValue() * 2.0F)
        );
    }

    private static float[] countRange(
        String moduleId,
        ShaderModuleDefinition.Parameter parameter
    ) {
        if ("nebula_haze".equals(moduleId)) return bounded(parameter, 1.0F, 3.0F);
        if ("dot_star".equals(moduleId)) return bounded(parameter, 8.0F, 32.0F);
        if ("cross_star".equals(moduleId)) return bounded(parameter, 2.0F, 10.0F);
        if ("fireflies".equals(moduleId) || "snowfall".equals(moduleId)
            || "rain_streaks".equals(moduleId) || "digital_rain".equals(moduleId)) {
            return bounded(parameter, 6.0F, 24.0F);
        }
        return bounded(parameter, 1.0F, Math.min(8.0F, parameter.maximum()));
    }

    private static float[] bounded(
        ShaderModuleDefinition.Parameter parameter,
        float minimum,
        float maximum
    ) {
        return new float[]{parameter.clamp(minimum), parameter.clamp(maximum)};
    }

    public record Guidance(
        String description,
        float recommendedMinimum,
        float recommendedMaximum
    ) {}

    private ShaderAiModuleMetadata() {}
}
