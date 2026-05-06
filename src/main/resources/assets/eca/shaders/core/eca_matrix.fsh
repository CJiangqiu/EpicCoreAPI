#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 ScreenSize;
uniform float Time;

in vec2 texCoord;

out vec4 fragColor;

float hash(float n) {
    return fract(sin(n) * 43758.5453123);
}

float hash2(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float digit0(vec2 p) {
    float d = abs(length(p) - 0.3);
    return smoothstep(0.1, 0.02, d);
}

float digit1(vec2 p) {
    float line = smoothstep(0.1, 0.02, abs(p.x));
    line *= step(-0.4, p.y) * step(p.y, 0.4);
    return line;
}

float linearize(float depth) {
    float near = 0.05;
    float far = 1024.0;
    float z = near * far / (far - depth * (far - near));
    return min(z, 256.0);
}

float luminance(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 texel = 1.0 / ScreenSize;
    vec2 fragCoord = texCoord * ScreenSize;

    float d_c = linearize(texture(Sampler0, texCoord).r);
    float d_t = linearize(texture(Sampler0, texCoord + vec2(0.0, texel.y)).r);
    float d_b = linearize(texture(Sampler0, texCoord + vec2(0.0, -texel.y)).r);
    float d_l = linearize(texture(Sampler0, texCoord + vec2(-texel.x, 0.0)).r);
    float d_r = linearize(texture(Sampler0, texCoord + vec2(texel.x, 0.0)).r);
    float depthEdge = abs(d_t + d_b + d_l + d_r - 4.0 * d_c);

    float c_tl = luminance(texture(Sampler1, texCoord + vec2(-texel.x, texel.y)).rgb);
    float c_t  = luminance(texture(Sampler1, texCoord + vec2(0.0, texel.y)).rgb);
    float c_tr = luminance(texture(Sampler1, texCoord + vec2(texel.x, texel.y)).rgb);
    float c_l  = luminance(texture(Sampler1, texCoord + vec2(-texel.x, 0.0)).rgb);
    float c_r  = luminance(texture(Sampler1, texCoord + vec2(texel.x, 0.0)).rgb);
    float c_bl = luminance(texture(Sampler1, texCoord + vec2(-texel.x, -texel.y)).rgb);
    float c_b  = luminance(texture(Sampler1, texCoord + vec2(0.0, -texel.y)).rgb);
    float c_br = luminance(texture(Sampler1, texCoord + vec2(texel.x, -texel.y)).rgb);

    float c_gx = -c_tl - 2.0 * c_l - c_bl + c_tr + 2.0 * c_r + c_br;
    float c_gy = -c_tl - 2.0 * c_t - c_tr + c_bl + 2.0 * c_b + c_br;
    float colorEdge = sqrt(c_gx * c_gx + c_gy * c_gy);

    float depthStrength = smoothstep(0.5, 2.0, depthEdge);
    float colorStrength = smoothstep(0.15, 0.5, colorEdge);
    float edge = max(depthStrength, colorStrength);

    float t = Time * 2000.0;

    float cellW = 12.0;
    float cellH = 20.0;
    vec2 cellId = floor(fragCoord / vec2(cellW, cellH));
    vec2 cellUv = fract(fragCoord / vec2(cellW, cellH)) - 0.5;

    float colSeed = hash(cellId.x * 0.123);
    float fallSpeed = 0.8 + colSeed * 1.2;
    float startOffset = hash(cellId.x * 0.456) * 50.0;
    float tailLen = 12.0 + colSeed * 8.0;
    float totalRows = ScreenSize.y / cellH + tailLen;

    float streamY = mod(t * fallSpeed * 0.03 + startOffset, totalRows);
    float distFromHead = streamY - cellId.y;

    float rnd = hash2(cellId + floor(t * 0.02));
    bool isOne = rnd > 0.5;

    float digit;
    if (isOne) {
        digit = digit1(cellUv);
    } else {
        digit = digit0(cellUv);
    }

    float brightness = 0.0;
    if (distFromHead > 0.0 && distFromHead < tailLen) {
        brightness = 1.0 - distFromHead / tailLen;
    }

    vec3 green = vec3(0.0, 0.9, 0.3);
    vec3 black = vec3(0.0, 0.01, 0.0);
    vec3 rainColor = black + green * digit * brightness;

    vec3 edgeColor = vec3(0.0, edge, 0.0);
    fragColor = vec4(max(rainColor, edgeColor), 1.0);
}
