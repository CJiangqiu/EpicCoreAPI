#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 ScreenSize;
uniform float Time;
uniform float ChromaticStrength;
uniform float ChromaticAngle;
uniform float ChromaticPulseAmount;
uniform float ChromaticPulseSpeed;
uniform float WaveAmplitude;
uniform float WaveFrequency;
uniform float WaveSpeed;
uniform float HeatStrength;
uniform float HeatDensity;
uniform float HeatSpeed;
uniform float HeatVerticalBias;
uniform float BrightnessBase;
uniform float BrightnessPulseAmount;
uniform float BrightnessPulseSpeed;
uniform float HueStrength;
uniform float HueSpeed;
uniform float ScanlineStrength;
uniform float ScanlineDensity;
uniform float ScanlineSpeed;
uniform float VignetteStrength;
uniform float VignetteRadius;
uniform float VignetteSoftness;

in vec2 texCoord;
out vec4 fragColor;

float noise(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

vec3 hueRotate(vec3 color, float angle) {
    const mat3 toYiq = mat3(0.299, 0.587, 0.114, 0.596, -0.274, -0.322, 0.211, -0.523, 0.312);
    const mat3 toRgb = mat3(1.0, 0.956, 0.621, 1.0, -0.272, -0.647, 1.0, -1.106, 1.703);
    vec3 yiq = toYiq * color;
    float hue = atan(yiq.z, yiq.y) + angle;
    float chroma = length(yiq.yz);
    return clamp(toRgb * vec3(yiq.x, chroma * cos(hue), chroma * sin(hue)), 0.0, 1.0);
}

void main() {
    vec2 uv = texCoord;
    uv += vec2(sin(uv.y * WaveFrequency + Time * WaveSpeed),
        cos(uv.x * WaveFrequency * 0.83 + Time * WaveSpeed * 1.17)) * WaveAmplitude;
    float heatMask = smoothstep(HeatVerticalBias, 1.0, uv.y);
    vec2 heatNoise = vec2(noise(uv * HeatDensity + vec2(Time * HeatSpeed, 0.0)),
        noise(uv.yx * HeatDensity * 0.83 + vec2(2.7, Time * HeatSpeed * 0.71))) - 0.5;
    uv = clamp(uv + heatNoise * HeatStrength * heatMask, vec2(0.001), vec2(0.999));

    vec4 center = texture(Sampler1, uv);
    float angle = radians(ChromaticAngle);
    float pulse = 1.0 + sin(Time * ChromaticPulseSpeed) * ChromaticPulseAmount;
    vec2 offset = vec2(cos(angle), sin(angle)) * ChromaticStrength * pulse;
    vec4 positive = texture(Sampler1, clamp(uv + offset, vec2(0.001), vec2(0.999)));
    vec4 negative = texture(Sampler1, clamp(uv - offset, vec2(0.001), vec2(0.999)));
    vec3 color = vec3(positive.r, center.g, negative.b);
    color *= max(0.0, BrightnessBase + sin(Time * BrightnessPulseSpeed) * BrightnessPulseAmount);
    color = mix(color, hueRotate(color, Time * HueSpeed), HueStrength);
    float scanline = sin((uv.y + Time * ScanlineSpeed) * ScanlineDensity * 6.2831853) * 0.5 + 0.5;
    color *= 1.0 - scanline * ScanlineStrength;
    float vignette = smoothstep(VignetteRadius, VignetteRadius + max(0.001, VignetteSoftness),
        length(uv - vec2(0.5)) * 1.41421356);
    color *= 1.0 - vignette * VignetteStrength;
    fragColor = vec4(color, center.a);
}
