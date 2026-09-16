#version 450 core
// A block-exact extrusion of Minecraft's cloud texture, traced in camera-relative space.
layout(binding = 0) uniform sampler2D cloudTexture;
layout(binding = 1) uniform sampler2D lodDepth;
layout(location = 0) uniform mat4 inverseMVP;
layout(location = 1) uniform mat4 sourceMVP;
// camera XZ modulo texture period (in cells), bottom relative to camera, thickness
layout(location = 2) uniform vec4 cloudOrigin;
// cell width, fade start, fade end, unused
layout(location = 3) uniform vec4 cloudRange;
layout(location = 4) uniform vec3 cloudColour;
in vec2 UV;
layout(location = 0) out vec4 colour;

vec3 unproject(vec2 uv, float depth) {
    vec4 p = inverseMVP * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

vec4 cellColour(ivec2 cell) {
    ivec2 size = textureSize(cloudTexture, 0);
    return texelFetch(cloudTexture, ((cell % size) + size) % size, 0);
}

void main() {
    // Use a mid-depth ray; reconstructing the far plane needlessly loses precision.
    vec3 ray = normalize(unproject(UV, 0.5));
    float horizontal = length(ray.xz);
    float endT = cloudRange.z / max(horizontal, 1e-8);
    float startT = 0.0;
    float shade = 0.85;
    if (abs(ray.y) < 1e-8) {
        if (cloudOrigin.z > 0.0 || cloudOrigin.z + cloudOrigin.w < 0.0) discard;
    } else {
        float a = cloudOrigin.z / ray.y;
        float b = (cloudOrigin.z + cloudOrigin.w) / ray.y;
        startT = max(0.0, min(a, b));
        endT = min(endT, max(a, b));
        shade = ray.y > 0.0 ? 0.72 : 1.0;
    }
    if (endT <= startT) discard;

    float sceneDepth = texture(lodDepth, UV).r;
    if (sceneDepth > 0.0 && sceneDepth < 1.0) {
        endT = min(endT, length(unproject(UV, sceneDepth)) - 0.05);
    }
    if (endT <= startT) discard;

    vec2 origin = cloudOrigin.xy;
    vec2 direction = ray.xz / cloudRange.x;
    ivec2 cell = ivec2(floor(origin + direction * (startT + 0.001)));
    ivec2 stepCell = ivec2(sign(direction));
    vec2 nextT = vec2(1e30);
    vec2 deltaT = vec2(1e30);
    for (int axis = 0; axis < 2; axis++) {
        if (abs(direction[axis]) > 1e-12) {
            float boundary = float(cell[axis] + (stepCell[axis] > 0 ? 1 : 0));
            nextT[axis] = (boundary - origin[axis]) / direction[axis];
            deltaT[axis] = abs(1.0 / direction[axis]);
        }
    }
    float t = startT;
    // Max RD 32768, fade end <=99%, cells >=48: <960 crossed cells along any ray.
    for (int i = 0; i < 1024 && t < endT; i++) {
        vec4 texel = cellColour(cell);
        if (texel.a > 0.1) {
            vec3 hit = ray * max(t, 0.001);
            float fade = 1.0 - smoothstep(cloudRange.y, cloudRange.z, length(hit.xz));
            if (fade <= 0.0) discard;
            vec4 clip = sourceMVP * vec4(hit, 1.0);
            // Same source-depth convention as Voxy's terrain composite, beyond vanilla's far plane.
            float ndcDepth = min(1.0 - 2.0 / 16777215.0, clip.z / clip.w);
            gl_FragDepth = gl_DepthRange.near + gl_DepthRange.diff * (ndcDepth * 0.5 + 0.5);
            colour = vec4(texel.rgb * cloudColour * shade, 0.8 * texel.a * fade);
            return;
        }
        int axis = nextT.x < nextT.y ? 0 : 1;
        t = nextT[axis];
        nextT[axis] += deltaT[axis];
        cell[axis] += stepCell[axis];
        shade = axis == 0 ? 0.85 : 0.9;
    }
    discard;
}
