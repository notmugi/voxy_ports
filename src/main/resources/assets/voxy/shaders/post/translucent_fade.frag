#version 460 core
layout(binding=0) uniform sampler2D nativeColour;
layout(binding=1) uniform sampler2D nativeDepth;
layout(binding=2) uniform sampler2D lodColour;
layout(binding=3) uniform sampler2D lodDepth;
layout(binding=4) uniform sampler2D sectionDepth;
layout(location=0) uniform mat4 nativeInverse;
layout(location=1) uniform mat4 lodInverse;
layout(location=2) uniform mat4 nativeProjection;
layout(location=3) uniform vec2 fadeRange;
layout(location=4) uniform vec4 fogParams;
layout(location=5) uniform vec4 fogColour;
in vec2 UV;
out vec4 colour;
vec3 position(mat4 inverseMatrix, float depth) {
    vec4 p = inverseMatrix * vec4(UV*2.0-1.0, depth*2.0-1.0, 1.0);
    return p.xyz/p.w;
}
void main() {
    // Both layers are premultiplied; mix them, never stack them.
    vec4 n = texture(nativeColour, UV);
    vec4 l = texture(lodColour, UV);
    float nd = texture(nativeDepth, UV).r;
    float ld = texture(lodDepth, UV).r;
    if (ld == 0.0 || ld == 1.0) l = vec4(0);
    float weight = 1.0;
    float projected = nd;
    if (l.a > 0.0) {
        vec3 p = position(lodInverse, ld);
        vec4 clip = nativeProjection * vec4(p, 1);
        // Match the opaque LOD blit's far-depth clamp. Native far is much shorter.
        float ndcDepth = min(clip.z/clip.w, 1.0-2.0/16777215.0);
        projected = ndcDepth*0.5+0.5;
        float exitDepth = texture(sectionDepth, UV).r;
        bool overlap = exitDepth > 0.0 && ld <= exitDepth + 0.00025/clip.w + 2.0/16777215.0;
        if (overlap) weight = smoothstep(fadeRange.x, fadeRange.y, length(p.xz));
        // Opaque foreground still occludes LOD water. Matching native water does not.
        if (n.a == 0.0 && nd < 1.0 && projected > nd + 2.0/16777215.0) l = vec4(0);
        if (n.a > 0.0) {
            float nativeWeight = smoothstep(fadeRange.x, fadeRange.y, length(position(nativeInverse, nd).xz));
            weight = min(weight, nativeWeight);
        }
        float distance = fogParams.w > 1.5 ? length(p.xz)
            : (fogParams.w > 0.5 ? max(length(p.xz), abs(p.y)) : length(p));
        float fog = clamp(fma(distance, fogParams.x, fogParams.y), 0.0, fogParams.z)*fogColour.a;
        l.rgb = mix(l.rgb, fogColour.rgb*l.a, fog);
    }
    if (l.a == 0.0) colour = n; // Missing LOD data must not erase native water.
    else if (n.a > 0.0) colour = mix(n, l, weight);
    else colour = l*weight;
    if (colour.a == 0.0) discard;
    // Keep native depth in the overlap; write LOD depth only for exposed LODs.
    gl_FragDepth = n.a > 0.0 ? nd : min(nd, projected);
}
