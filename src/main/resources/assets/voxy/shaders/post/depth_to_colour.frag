#version 330 core
layout(binding = 0) uniform sampler2D depthTex;
in vec2 UV;
out vec4 colour;
void main() {
    colour = vec4(texture(depthTex, UV).r);
}
