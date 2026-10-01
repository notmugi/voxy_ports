#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#define LIGHTING_SAMPLER_BINDING 1

#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

layout(location = 0) out flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) out vec2 uv;
#endif
layout(location = 2) out vec3 vPos;

#ifdef DEBUG_RENDER
layout(location = 7) out flat uint quadDebug;
#endif

#if !defined(PATCHED_SHADER) && !defined(TRANSLUCENT)
layout(location = 8) uniform int cutoutFadePass;
#endif

vec2 taaShift();

// TODO: add a mechanism so that some quads can ignore backface culling
// this would help alot with stuff like crops as they would look kinda weird i think,
// same with flowers etc
void main() {
    taaOffset = taaShift();

#if !defined(PATCHED_SHADER) && !defined(TRANSLUCENT)
    if (cutoutFadePass == 1) {
        uint modelId = extractStateId(quadData[uint(gl_VertexID)>>2]);
        if ((modelData[modelId].flagsA & 3840u) == 0u) {
            // Reject whole non-cutout quads before lighting, rasterization and atlas sampling.
            gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            return;
        }
    }
#endif
    QuadData quad;
    uvec2 pos = positionBuffer[gl_BaseInstance];
    setupQuad(quad, quadData[uint(gl_VertexID)>>2], pos, (gl_VertexID&3) == 1);

    uint cornerId = gl_VertexID&3;
    // Camera-relative position for the border fade, matches getQuadCornerPos.
    vec2 fadeCornerMask = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;
    vPos = (quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin*fadeCornerMask, 0))) - cameraSubPos;
    gl_Position = getQuadCornerPos(quad, cornerId);

    #ifndef USE_NV_BARRY
    uv = getCornerUV(quad, cornerId);
    #endif

    // Note: other data is automatically discarded as it is undefiend and has not been generated
    interData = quad.attributeData;

    #ifdef DEBUG_RENDER
    // quadDebug = uint(extractDetail(pos));
    quadDebug = uint(gl_VertexID)>>2;
    #endif
}

#ifndef TAA_PATCH
vec2 taaShift() {return vec2(0.0);}
#endif
