#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform mat4 InvViewProjMat;
uniform vec3 CameraPos;
uniform vec2 ScreenSize;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

const float PI = 3.14159265359;

float luminance(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// 世界空间方向 → 等距圆柱 UV（天空用）
vec2 equirectUV(vec3 dir) {
    float u = atan(dir.z, dir.x) * (1.0 / (2.0 * PI)) + 0.5;
    float v = asin(clamp(dir.y, -1.0, 1.0)) * (1.0 / PI) + 0.5;
    return vec2(u, v);
}

// 从贴图颜色还原：暗区压成纯黑，星点增亮，紫味交给星云负责
vec3 enhanceCosmos(vec3 tex) {
    float lum = luminance(tex);
    vec3 boosted = tex * 3.0;
    return mix(vec3(0.0), boosted, smoothstep(0.015, 0.12, lum));
}

// Dave Hoskins hash：无轴向相关，避免 fract(x*y) 那种网格状方块伪影
float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// 4 octave FBM，按振幅总和归一化到 [0,1]（均值约 0.5），使阈值直观可调
float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    float total = 0.0;
    for (int i = 0; i < 4; i++) {
        v += amp * valueNoise(p);
        total += amp;
        p *= 2.0;
        amp *= 0.5;
    }
    return v / total;
}

/* 紫色星云薄雾：FBM 密度取均值以上段并乘低强度，呈薄雾而非浓云；
   坐标随 Time 缓慢漂移，云团缓慢翻腾。色相由第二层 FBM 在深紫与品红间插值 */
vec3 nebula(vec2 p, float t) {
    vec2 drift = vec2(t * 0.008, t * 0.005);
    float density = smoothstep(0.45, 0.8, fbm(p + drift));
    float hue = fbm(p * 0.7 - drift * 0.5);
    vec3 col = mix(vec3(0.35, 0.1, 0.5), vec3(0.6, 0.2, 0.7), smoothstep(0.3, 0.7, hue));
    return col * density * 0.35;
}

// Dave Hoskins hash13：vec3 → float
float hash31(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float valueNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    float n000 = hash31(i);
    float n100 = hash31(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash31(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash31(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash31(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash31(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash31(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash31(i + vec3(1.0, 1.0, 1.0));
    float nx00 = mix(n000, n100, u.x);
    float nx10 = mix(n010, n110, u.x);
    float nx01 = mix(n001, n101, u.x);
    float nx11 = mix(n011, n111, u.x);
    return mix(mix(nx00, nx10, u.y), mix(nx01, nx11, u.y), u.z);
}

// 4 octave 3D FBM，归一化到 [0,1]
float fbm3(vec3 p) {
    float v = 0.0;
    float amp = 0.5;
    float total = 0.0;
    for (int i = 0; i < 4; i++) {
        v += amp * valueNoise3(p);
        total += amp;
        p *= 2.0;
        amp *= 0.5;
    }
    return v / total;
}

/* 球面星云：直接用 3D 方向向量采样 3D 噪声，球面上连续无接缝，
   消除等距圆柱投影在 ±π 经线处把星云切开错位的问题 */
vec3 nebulaSphere(vec3 dir, float t) {
    vec3 drift = vec3(t * 0.006, 0.0, t * 0.004);
    float density = smoothstep(0.45, 0.8, fbm3(dir * 2.5 + drift));
    float hue = fbm3(dir * 1.8 - drift * 0.5);
    vec3 col = mix(vec3(0.35, 0.1, 0.5), vec3(0.6, 0.2, 0.7), smoothstep(0.3, 0.7, hue));
    return col * density * 0.35;
}

/* 程序化网格星：每格至多一颗，位置/亮度/相位各自哈希；
   sin(Time*1.8+相位) 周期约 3.5 秒，整颗缓慢明灭，避免逐像素抖动 */
vec3 twinkleStars(vec2 uv, float t) {
    vec2 cell = floor(uv);
    vec2 local = fract(uv);
    float present = step(0.80, hash21(cell + 7.3));
    vec2 starPos = vec2(hash21(cell + 1.7), hash21(cell + 4.9));
    float star = smoothstep(0.13, 0.0, length(local - starPos)) * present;
    float phase = hash21(cell + 3.1);
    float twinkle = 0.5 + 0.5 * sin(t * 1.8 + phase * 6.2831);
    float bright = 0.7 + 0.3 * hash21(cell + 11.7);
    return vec3(star * twinkle * bright);
}

/* 三平面投影（triplanar）：用世界坐标直接 UV 平铺贴图，
   比 normalize 方向采样分辨率均匀，消除近处极度放大问题 */
vec3 triplanar(vec3 worldPos, vec3 nrm, float scale) {
    vec3 blend = abs(nrm);
    blend = pow(blend, vec3(6.0));
    blend /= blend.x + blend.y + blend.z + 0.0001;

    vec3 colX = enhanceCosmos(texture(Sampler2, worldPos.yz * scale).rgb);
    vec3 colY = enhanceCosmos(texture(Sampler2, worldPos.xz * scale).rgb);
    vec3 colZ = enhanceCosmos(texture(Sampler2, worldPos.xy * scale).rgb);
    return colX * blend.x + colY * blend.y + colZ * blend.z;
}

void main() {
    float depth = texture(Sampler0, texCoord).r;
    vec3 scene  = texture(Sampler1, texCoord).rgb;

    // 实体遮罩：该像素深度比地形快照更近，说明是实体，保留原色不应用滤镜
    float terrainDepth = texture(Sampler3, texCoord).r;
    if (depth < terrainDepth - 0.0001) {
        fragColor = vec4(scene, 1.0);
        return;
    }

    // 从深度反算相机相对世界坐标
    vec3 ndc     = vec3(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0);
    vec4 clip    = InvViewProjMat * vec4(ndc, 1.0);
    vec3 worldRel = clip.xyz / clip.w;
    vec3 worldPos = worldRel + CameraPos;

    if (depth >= 0.9999) {
        // 天空/云朵：视线方向查全景图打底，叠球面星云薄雾与缓慢闪烁星
        vec3 dir = normalize(worldRel);
        vec2 skyUV = equirectUV(dir);
        vec3 sky = enhanceCosmos(texture(Sampler2, skyUV).rgb);
        sky += nebulaSphere(dir, Time);
        sky += twinkleStars(skyUV * 60.0, Time);
        fragColor = vec4(sky, 1.0);
        return;
    }

    // 几何/实体表面：triplanar worldPos 平铺，法线由深度梯度估算
    vec3 dpdx = dFdx(worldPos);
    vec3 dpdy = dFdy(worldPos);
    vec3 crossP = cross(dpdx, dpdy);
    float crossLen = length(crossP);
    // 深度不连续处（边缘）crossLen 过大说明法线无效，回退到朝上
    vec3 nrm = (crossLen > 0.001 && crossLen < 50.0)
        ? crossP / crossLen
        : vec3(0.0, 1.0, 0.0);

    // 每 40 格重复一次贴图（scale = 1/40 = 0.025）
    vec3 cosmic = triplanar(worldPos, nrm, 0.025);
    cosmic += nebula(worldPos.xz * 0.03, Time);

    // 用原始明暗调制，保留形状立体感
    float lum   = luminance(scene);
    float shade = 0.5 + 0.5 * lum;
    fragColor = vec4(cosmic * shade, 1.0);
}
