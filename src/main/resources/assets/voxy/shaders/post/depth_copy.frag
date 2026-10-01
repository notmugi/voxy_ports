#version 330 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform vec2 scaleFactor;
// Discard empty pixels; native foreground writes depth/stencil zero.
layout(location = 2) uniform mat4 fadeInvViewProj;
layout(location = 3) uniform float fadeStart;

in vec2 UV;
void main() {
    float depth = texture(depthTex, UV*scaleFactor).r;
    if (depth == 1.0) discard;
    if (fadeStart > 0.0) {
        vec4 p = fadeInvViewProj * vec4(UV*2.0-1.0, depth*2.0-1.0, 1.0);
        if (length(p.xz/p.w) > fadeStart) discard;
    }
    gl_FragDepth = 0.0;
}
