#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform mat4 invProjMat;
layout(location = 2) uniform mat4 projMat;

#ifdef EMIT_COLOUR
layout(binding = 3) uniform sampler2D colourTex;
#ifdef USE_ENV_FOG
layout(location = 4) uniform vec4 endParams;
layout(location = 5) uniform vec4 fogColour;
#endif
#endif

#ifdef BORDER_FADE
layout(binding = 4) uniform sampler2D vanillaDepthTex;
layout(binding = 5) uniform sampler2D rawColourTex;
layout(binding = 6) uniform sampler2D cutoutDepthTex;
layout(binding = 7) uniform sampler2D cutoutRawTex;
layout(location = 6) uniform mat4 vanillaInvViewProj;
layout(location = 7) uniform vec2 fadeRange;
// 1: background, 2: native band, 3: separate fading cutouts.
layout(location = 8) uniform int fadePass;
#endif

out vec4 colour;
in vec2 UV;

vec3 rev3d(vec3 clip) {
    vec4 view = invProjMat * vec4(clip*2.0f-1.0f,1.0f);
    return view.xyz/view.w;
}
float projDepth(vec3 pos) {
    vec4 view = projMat * vec4(pos, 1);
    return view.z/view.w;
}

void main() {
    float depth = texture(depthTex, UV.xy).r;
    if (depth == 0.0f || depth == 1.0) {
        discard;
    }

    vec3 point = rev3d(vec3(UV.xy, depth));
    depth = projDepth(point);
    depth = min(1.0f-(2.0f/((1<<24)-1)), depth);
    depth = depth * 0.5f + 0.5f;
    depth = gl_DepthRange.diff * depth + gl_DepthRange.near;
    gl_FragDepth = depth;

    #ifdef BORDER_FADE
    float fadeW = 1.0;
    if (fadePass != 0) {
        float vDepth = texture(vanillaDepthTex, UV.xy).r;
        float vDist = -1.0;
        if (vDepth < 1.0) {
            vec4 vp = vanillaInvViewProj * vec4(UV.xy*2.0-1.0, vDepth*2.0-1.0, 1.0);
            vDist = length(vp.xz/vp.w);
        }
        bool nativeSurface = vDist > fadeRange.x;
        uint metadata = uint(round(texture(rawColourTex, UV).a*255.0));
        bool overlap = (metadata & 128u) != 0u;
        bool overlay = nativeSurface || overlap;
        if (fadePass == 3 && !overlap) discard;
        if (overlay == (fadePass == 1)) discard;
        if (fadePass >= 2) {
            fadeW = nativeSurface ? smoothstep(fadeRange.x, fadeRange.y, vDist) : 1.0;
            // Leaf holes see distant ground/sky: fade by the leaf, not that background.
            if (overlap) fadeW = min(fadeW, smoothstep(fadeRange.x, fadeRange.y, length(point.xz)));
            if (fadePass == 2 && nativeSurface) {
                uint cutoutMeta = uint(round(texture(cutoutRawTex, UV).a*255.0));
                float cutoutDepth = texture(cutoutDepthTex, UV).r;
                if ((cutoutMeta & 128u) != 0u && cutoutDepth > 0.0 && cutoutDepth < 1.0) {
                    float cutoutWeight = min(fadeW, smoothstep(fadeRange.x, fadeRange.y,
                            length(rev3d(vec3(UV, cutoutDepth)).xz)));
                    // Reserve the cutout's share of the single native-to-LOD blend.
                    // After pass 3: native*(1-fadeW) + background*(fadeW-cutoutWeight)
                    //               + cutout*cutoutWeight. No double fade to background.
                    if (cutoutWeight >= 1.0) discard;
                    fadeW = (fadeW-cutoutWeight)/(1.0-cutoutWeight);
                }
            }
        }
    }
    #endif

    #ifdef EMIT_COLOUR
    colour = texture(colourTex, UV.xy);
    if (colour.a == 0.0) {
        discard;
    }
    #ifdef USE_ENV_FOG
    if (fogColour.a>0.0){
        float distance = endParams.w > 1.5 ? length(point.xz)
            : (endParams.w > 0.5 ? max(length(point.xz), abs(point.y)) : length(point));
        float fogLerp = clamp(fma(distance, endParams.x, endParams.y), 0.0, endParams.z);
        colour.rgb = mix(colour.rgb, fogColour.rgb, fogLerp*fogColour.a);
    }
    #endif
    #ifdef BORDER_FADE
    colour.a *= fadeW;
    #endif
    #else
    colour = vec4(0);
    #endif

}
