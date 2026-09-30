vec3 blend_to_linear(vec3 c) {
    return mix(c / 12.92, pow(max((c + 0.055) / 1.055, vec3(0.0)), vec3(2.4)), step(vec3(0.04045), c));
}

vec3 blend_to_srgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}

vec3 blend_normalize(vec3 v) { return dot(v, v) > 0.0 ? normalize(v) : vec3(0.0); }

vec3 blend_surface_light(vec3 base, float metallic, float roughness, vec3 n, vec3 v, vec3 l) {
    vec3 h = blend_normalize(v + l);
    float nl = max(dot(n, l), 0.0), nv = max(dot(n, v), 0.0001);
    float nh = max(dot(n, h), 0.0), vh = max(dot(v, h), 0.0);
    float a = roughness * roughness, a2 = a * a;
    float d = nh * nh * (a2 - 1.0) + 1.0;
    float distribution = a2 / max(3.14159265 * d * d, 0.0000001);
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    float visibility = nv / (nv * (1.0 - k) + k) * nl / max(nl * (1.0 - k) + k, 0.0001);
    vec3 f0 = mix(vec3(0.04), base, metallic);
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - vh, 5.0);
    vec3 diffuse = (1.0 - fresnel) * (1.0 - metallic) * base / 3.14159265;
    vec3 specular = distribution * visibility * fresnel / max(4.0 * nl * nv, 0.0001);
    return (diffuse + specular) * nl * 0.94247780;
}

vec3 blend_surface(vec3 base, float metallic, float roughness, vec3 normal, vec3 view,
                   vec3 light0, vec3 light1, vec3 lightMap) {
    base = max(base, vec3(0.0));
    metallic = clamp(metallic, 0.0, 1.0);
    roughness = clamp(roughness, 0.06, 1.0);
    vec3 n = blend_normalize(normal), v = blend_normalize(view);
    vec3 ambient = 0.4 * base * (1.0 - metallic) + 0.08 * mix(vec3(0.04), base, metallic);
    vec3 direct = blend_surface_light(base, metallic, roughness, n, v, blend_normalize(light0))
                + blend_surface_light(base, metallic, roughness, n, v, blend_normalize(light1));
    return (ambient + direct) * blend_to_linear(lightMap);
}

vec3 blend_div(vec3 a, vec3 b) {
    return vec3(b.x == 0.0 ? 0.0 : a.x / b.x, b.y == 0.0 ? 0.0 : a.y / b.y, b.z == 0.0 ? 0.0 : a.z / b.z);
}

float blend_math(int op, float a, float b, float c) {
    if (op == 0) return a + b;
    if (op == 1) return a - b;
    if (op == 2) return a * b;
    if (op == 3) return b == 0.0 ? 0.0 : a / b;
    if (op == 4) return sin(a);
    if (op == 5) return cos(a);
    if (op == 6) return tan(a);
    if (op == 7) return asin(clamp(a, -1.0, 1.0));
    if (op == 8) return acos(clamp(a, -1.0, 1.0));
    if (op == 9) return atan(a);
    if (op == 10) {
        if (a < 0.0 && b != floor(b)) return 0.0;
        return pow(abs(a), b) * (a < 0.0 && mod(abs(b), 2.0) == 1.0 ? -1.0 : 1.0);
    }
    if (op == 11) return a > 0.0 && b > 0.0 && b != 1.0 ? log(a) / log(b) : 0.0;
    if (op == 12) return min(a, b);
    if (op == 13) return max(a, b);
    if (op == 14) return floor(a + 0.5);
    if (op == 15) return float(a < b);
    if (op == 16) return float(a > b);
    if (op == 17) return b == 0.0 ? 0.0 : a - trunc(a / b) * b;
    if (op == 18) return abs(a);
    if (op == 19) return a == 0.0 && b == 0.0 ? 0.0 : atan(a, b);
    if (op == 20) return floor(a);
    if (op == 21) return ceil(a);
    if (op == 22) return fract(a);
    if (op == 23) return sqrt(max(a, 0.0));
    if (op == 24) return a > 0.0 ? inversesqrt(a) : 0.0;
    if (op == 25) return sign(a);
    if (op == 26) return exp(a);
    if (op == 27) return radians(a);
    if (op == 28) return degrees(a);
    if (op == 32) return trunc(a);
    if (op == 33) return b == 0.0 ? 0.0 : floor(a / b) * b;
    if (op == 35) return float(abs(a - b) <= max(c, 0.00001));
    if (op == 36) return a * b + c;
    if (op == 40) return b == 0.0 ? 0.0 : mod(a, b);
    return 0.0;
}

vec3 blend_rotate(vec3 p, vec3 r) {
    vec3 s = sin(r), c = cos(r);
    p.yz = mat2(c.x, s.x, -s.x, c.x) * p.yz;
    p.xz = mat2(c.y, -s.y, s.y, c.y) * p.xz;
    p.xy = mat2(c.z, s.z, -s.z, c.z) * p.xy;
    return p;
}

float blend_gradient(vec3 p, int kind) {
    float value = p.x;
    if (kind == 1) value = max(p.x, 0.0) * max(p.x, 0.0);
    if (kind == 2) value = smoothstep(0.0, 1.0, p.x);
    if (kind == 3) value = (p.x + p.y) * 0.5;
    if (kind == 4) value = (p.x == 0.0 && p.y == 0.0 ? 0.0 : atan(p.y, p.x)) / 6.28318530718 + 0.5;
    if (kind >= 5) { value = max(0.999999 - length(p), 0.0); if (kind == 5) value *= value; }
    return clamp(value, 0.0, 1.0);
}

ivec2 blend_pixel(ivec2 p, ivec2 size, int extension) {
    return extension == 0 ? ((p % size) + size) % size : clamp(p, ivec2(0), size - 1);
}

vec4 blend_texel(sampler2D image, ivec2 p, ivec2 size, int extension) {
    if (extension == 2 && (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, size)))) return vec4(0.0);
    return texelFetch(image, blend_pixel(p, size, extension), 0);
}

vec4 blend_image(sampler2D image, vec2 uv, int interpolation, int extension) {
    ivec2 size = textureSize(image, 0);
    uv.y = 1.0 - uv.y;
    if (interpolation == 1) return blend_texel(image, ivec2(floor(uv * vec2(size))), size, extension);
    vec2 p = uv * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(p));
    vec2 f = fract(p);
    return mix(mix(blend_texel(image, base, size, extension), blend_texel(image, base + ivec2(1, 0), size, extension), f.x),
        mix(blend_texel(image, base + ivec2(0, 1), size, extension), blend_texel(image, base + ivec2(1, 1), size, extension), f.x), f.y);
}

// Lookup3 finalization keeps procedural noise seeds stable across CPU and GPU representations.
uint blend_rot(uint x, uint bits) { return (x << bits) | (x >> (32u - bits)); }
uint blend_hash(uvec3 values, uint dimensions) {
    uvec3 state = values + uvec3(0xdeadbeefu + dimensions * 4u + 13u);
    state.z = (state.z ^ state.y) - blend_rot(state.y, 14u);
    state.x = (state.x ^ state.z) - blend_rot(state.z, 11u);
    state.y = (state.y ^ state.x) - blend_rot(state.x, 25u);
    state.z = (state.z ^ state.y) - blend_rot(state.y, 16u);
    state.x = (state.x ^ state.z) - blend_rot(state.z, 4u);
    state.y = (state.y ^ state.x) - blend_rot(state.x, 14u);
    return (state.z ^ state.y) - blend_rot(state.y, 24u);
}

float blend_grad(uint hash, vec3 delta) {
    uint h = hash & 15u;
    float a = h < 8u ? delta.x : delta.y;
    float b = h < 4u ? delta.y : (h == 12u || h == 14u ? delta.x : delta.z);
    return ((h & 1u) == 0u ? a : -a) + ((h & 2u) == 0u ? b : -b);
}

float blend_perlin(vec3 point) {
    vec3 correction = step(vec3(1000000.0), abs(point)) * 0.5;
    point = point - trunc(point / 100000.0) * 100000.0 + correction;
    ivec3 cell = ivec3(floor(point));
    vec3 f = fract(point), fade = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float result = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 corner = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 weight = mix(vec3(1.0) - fade, fade, vec3(corner));
        result += blend_grad(blend_hash(uvec3(cell + corner), 3u), f - vec3(corner)) * weight.x * weight.y * weight.z;
    }
    return result * 0.982;
}

uint blend_seed_bits(int seed) {
    if (seed == 0) return 0u;
    if (seed == 1) return 0x3f800000u;
    if (seed == 2) return 0x40000000u;
    if (seed == 3) return 0x40400000u;
    if (seed == 5) return 0x40a00000u;
    return 0x40800000u;
}

vec3 blend_noise_offset(float seed) {
    vec3 offset;
    for (int i = 0; i < 3; i++) {
        offset[i] = 100.0 + 100.0 * float(blend_hash(uvec3(blend_seed_bits(int(seed)), blend_seed_bits(i), 0u), 2u)) / 4294967295.0;
    }
    return offset;
}

float blend_fbm(vec3 point, float detail, float roughness, float lacunarity, bool normalized) {
    float sum = 0.0, amplitude = 1.0, total = 0.0, frequency = 1.0;
    detail = clamp(detail, 0.0, 15.0);
    roughness = max(roughness, 0.0);
    for (int octave = 0; octave <= int(detail); octave++) {
        sum += amplitude * blend_perlin(point * frequency);
        total += amplitude;
        amplitude *= roughness;
        frequency *= lacunarity;
    }
    float extra = sum + amplitude * blend_perlin(point * frequency);
    if (normalized) return mix(0.5 + 0.5 * sum / total, 0.5 + 0.5 * extra / (total + amplitude), fract(detail));
    return mix(sum, extra, fract(detail));
}

vec4 blend_noise(vec3 point, float detail, float roughness, float lacunarity, float distortion, bool normalized) {
    if (distortion != 0.0) {
        point += distortion * vec3(blend_perlin(point + blend_noise_offset(0.0)),
            blend_perlin(point + blend_noise_offset(1.0)), blend_perlin(point + blend_noise_offset(2.0)));
    }
    return vec4(blend_fbm(point, detail, roughness, lacunarity, normalized),
        blend_fbm(point + blend_noise_offset(3.0), detail, roughness, lacunarity, normalized),
        blend_fbm(point + blend_noise_offset(4.0), detail, roughness, lacunarity, normalized), 1.0);
}

uint blend_hash4(uvec4 value) {
    uvec3 s = value.xyz + uvec3(0xdeadbeefu + 29u);
    s.x = (s.x - s.z) ^ blend_rot(s.z, 4u); s.z += s.y;
    s.y = (s.y - s.x) ^ blend_rot(s.x, 6u); s.x += s.z;
    s.z = (s.z - s.y) ^ blend_rot(s.y, 8u); s.y += s.x;
    s.x = (s.x - s.z) ^ blend_rot(s.z, 16u); s.z += s.y;
    s.y = (s.y - s.x) ^ blend_rot(s.x, 19u); s.x += s.z;
    s.z = (s.z - s.y) ^ blend_rot(s.y, 4u); s.y += s.x;
    s.x += value.w;
    s.z = (s.z ^ s.y) - blend_rot(s.y, 14u);
    s.x = (s.x ^ s.z) - blend_rot(s.z, 11u);
    s.y = (s.y ^ s.x) - blend_rot(s.x, 25u);
    s.z = (s.z ^ s.y) - blend_rot(s.y, 16u);
    s.x = (s.x ^ s.z) - blend_rot(s.z, 4u);
    s.y = (s.y ^ s.x) - blend_rot(s.x, 14u);
    return (s.z ^ s.y) - blend_rot(s.y, 24u);
}

float blend_perlin(vec4 point) {
    vec4 correction = step(vec4(1000000.0), abs(point)) * 0.5;
    point = point - trunc(point / 100000.0) * 100000.0 + correction;
    ivec4 cell = ivec4(floor(point));
    vec4 f = fract(point), fade = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float result = 0.0;
    for (int i = 0; i < 16; i++) {
        ivec4 corner = ivec4(i & 1, (i >> 1) & 1, (i >> 2) & 1, (i >> 3) & 1);
        vec4 d = f - vec4(corner), weight = mix(vec4(1.0) - fade, fade, vec4(corner));
        uint h = blend_hash4(uvec4(cell + corner)) & 31u;
        float a = h < 24u ? d.x : d.y;
        float b = h < 16u ? d.y : d.z;
        float c = h < 8u ? d.z : d.w;
        float gradient = ((h & 1u) == 0u ? a : -a) + ((h & 2u) == 0u ? b : -b) + ((h & 4u) == 0u ? c : -c);
        result += gradient * weight.x * weight.y * weight.z * weight.w;
    }
    return result * 0.8344;
}

vec4 blend_noise_offset4(int seed) {
    vec4 offset;
    for (int i = 0; i < 4; i++) {
        offset[i] = 100.0 + 100.0 * float(blend_hash(uvec3(blend_seed_bits(seed), blend_seed_bits(i), 0u), 2u)) / 4294967295.0;
    }
    return offset;
}

float blend_fbm(vec4 point, float detail, float roughness, float lacunarity, bool normalized) {
    float sum = 0.0, amplitude = 1.0, total = 0.0, frequency = 1.0;
    detail = clamp(detail, 0.0, 15.0);
    roughness = max(roughness, 0.0);
    for (int octave = 0; octave <= int(detail); octave++) {
        sum += amplitude * blend_perlin(point * frequency);
        total += amplitude;
        amplitude *= roughness;
        frequency *= lacunarity;
    }
    float extra = sum + amplitude * blend_perlin(point * frequency);
    if (normalized) return mix(0.5 + 0.5 * sum / total, 0.5 + 0.5 * extra / (total + amplitude), fract(detail));
    return mix(sum, extra, fract(detail));
}

vec4 blend_noise(vec4 point, float detail, float roughness, float lacunarity, float distortion, bool normalized) {
    if (distortion != 0.0) {
        point += distortion * vec4(blend_perlin(point + blend_noise_offset4(0)), blend_perlin(point + blend_noise_offset4(1)),
            blend_perlin(point + blend_noise_offset4(2)), blend_perlin(point + blend_noise_offset4(3)));
    }
    return vec4(blend_fbm(point, detail, roughness, lacunarity, normalized),
        blend_fbm(point + blend_noise_offset4(4), detail, roughness, lacunarity, normalized),
        blend_fbm(point + blend_noise_offset4(5), detail, roughness, lacunarity, normalized), 1.0);
}
