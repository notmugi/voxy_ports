#version 460 core
layout(binding=0) uniform sampler2D depthTex;
layout(binding=3) uniform sampler2D colourTex;
layout(binding=4) uniform sampler2D vanillaDepthTex;
layout(binding=6) uniform sampler2D cutoutDepthTex;
layout(binding=7) uniform sampler2D cutoutRawTex;
layout(binding=8) uniform sampler2D cutoutColourTex;
layout(location=1) uniform mat4 invProjMat;
layout(location=2) uniform mat4 projMat;
layout(location=4) uniform vec4 endParams;
layout(location=5) uniform vec4 fogColour;
layout(location=6) uniform mat4 vanillaInvViewProj;
layout(location=7) uniform vec2 fadeRange;
in vec2 UV;
out vec4 colour;
vec3 position(mat4 inverseMatrix, float d) {
    vec4 p = inverseMatrix*vec4(UV*2.0-1.0,d*2.0-1.0,1.0);
    return p.xyz/p.w;
}
vec3 fog(vec3 rgb, vec3 p) {
    if (fogColour.a <= 0.0) return rgb;
    float distance = endParams.w > 1.5 ? length(p.xz)
        : (endParams.w > 0.5 ? max(length(p.xz),abs(p.y)) : length(p));
    return mix(rgb,fogColour.rgb,clamp(fma(distance,endParams.x,endParams.y),0.0,endParams.z)*fogColour.a);
}
void main() {
    float bd = texture(depthTex,UV).r;
    uint metadata = uint(round(texture(cutoutRawTex,UV).a*255.0));
    bool hasCutout = (metadata & 128u) != 0u;
    bool hasBackground = bd > 0.0 && bd < 1.0;
    if (!hasBackground && !hasCutout) discard;

    float nd = texture(vanillaDepthTex,UV).r;
    float nativeDistance = nd < 1.0 ? length(position(vanillaInvViewProj,nd).xz) : -1.0;
    bool nativeBand = nativeDistance > fadeRange.x;
    float f = nativeBand ? smoothstep(fadeRange.x,fadeRange.y,nativeDistance) : 1.0;
    float c = 0.0;
    float coveredLeafDepth = nd;
    vec4 leaf = vec4(0);
    if (hasCutout) {
        float cd = texture(cutoutDepthTex,UV).r;
        if (cd > 0.0 && cd < 1.0) {
            vec3 cp = position(invProjMat,cd);
            c = min(f,smoothstep(fadeRange.x,fadeRange.y,length(cp.xz)));
            leaf = texture(cutoutColourTex,UV);
            leaf.rgb = fog(leaf.rgb,cp);
            c *= leaf.a;
            if ((metadata & 56u) == 56u) {
                // Rear LOD leaves must not override the native leaf's fade weight.
                if (nd < 1.0) {
                    const mat4 bayer = mat4(0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5);
                    ivec2 pixel = ivec2(gl_FragCoord.xy) & 3;
                    float threshold = (bayer[pixel.x][pixel.y] + 0.5) / 16.0;
                    float inset = (fadeRange.y - fadeRange.x) * 0.125;
                    float nativeLeafWeight = smoothstep(fadeRange.x + inset, fadeRange.y - inset, nativeDistance);
                    if (!nativeBand || nativeLeafWeight <= threshold) discard;
                }
                c = leaf.a;
                f = 1.0;
                vec4 clip = projMat * vec4(cp, 1.0);
                coveredLeafDepth = min(nd, min(clip.z / clip.w, 1.0-2.0/16777215.0)*0.5+0.5);
            }
        }
    }

    float backgroundWeight = 0.0;
    vec3 background = vec3(0);
    gl_FragDepth = coveredLeafDepth;
    if (hasBackground) {
        vec3 bp = position(invProjMat,bd);
        vec4 clip = projMat*vec4(bp,1.0);
        float projected = min(clip.z/clip.w,1.0-2.0/16777215.0)*0.5+0.5;
        vec4 b = texture(colourTex,UV);
        background = fog(b.rgb,bp);
        if (nativeBand) {
            backgroundWeight = max(0.0,f-c)*b.a;
        } else if (projected <= nd) {
            backgroundWeight = (1.0-c)*b.a;
            if (b.a > 0.0) gl_FragDepth = min(coveredLeafDepth, projected);
        }
    }
    // One premultiplied blend: native*(1-F) + world*(F-C) + cutout*C.
    colour = vec4(background*backgroundWeight + leaf.rgb*c,backgroundWeight+c);
    if (colour.a <= 0.0) discard;
}
