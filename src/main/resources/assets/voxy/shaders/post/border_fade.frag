#version 460 core

// Legacy vanilla-image fade. Alpha is the vanilla weight.

layout(binding = 0) uniform sampler2D vanillaColor;
layout(binding = 1) uniform sampler2D vanillaDepth;

layout(location = 0) uniform mat4 invViewProj;
layout(location = 4) uniform vec2 fadeRange;

in vec2 UV;
layout(location = 0) out vec4 fragColor;

void main() {
    float vanillaWeight = 0.0f;
    float depth = texture(vanillaDepth, UV).r;
    if (depth < 1.0f) {
        vec4 viewPos = invViewProj * vec4(UV * 2.0f - 1.0f, depth * 2.0f - 1.0f, 1.0f);
        float dist = length(viewPos.xyz / viewPos.w);
        vanillaWeight = 1.0f - smoothstep(fadeRange.x, fadeRange.y, dist);
    }
    fragColor = vec4(texture(vanillaColor, UV).rgb, vanillaWeight);
}
