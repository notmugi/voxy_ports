#version 460 core
//Use quad shuffling to compute fragment mip
//#extension GL_KHR_shader_subgroup_quad: enable
#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#ifdef USE_NV_BARRY
#extension GL_NV_fragment_shader_barycentric: require
#endif

layout(binding = 0) uniform sampler2D blockModelAtlas;
layout(binding = 2) uniform sampler2D depthTex;
#if !defined(PATCHED_SHADER) && !defined(TRANSLUCENT)
layout(location = 8) uniform int cutoutFadePass; // 0: background, 1: fading cutouts
layout(location = 9) uniform int nativeSectionTableSize;
layout(std430, binding = 6) readonly buffer NativeSections { ivec4 nativeSections[]; };

#endif

//#define DEBUG_RENDER

//TODO: need to fix when merged quads have discardAlpha set to false but they span multiple tiles
// however they are not a full block

layout(location = 0) in flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) in vec2 uv;
#endif
layout(location = 2) in vec3 vPos;

// Screen-door dissolve of LODs approaching the native chunk border, so vanilla
// chunks fade in instead of the LODs popping. Works for opaque and translucent.
float lodBorderBayer(vec2 p) {
    ivec2 i = ivec2(p) & 3;
    const mat4 b = mat4(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
    return (b[i.x][i.y] + 0.5f) / 16.0f;
}

#ifdef DEBUG_RENDER
layout(location = 7) in flat uint quadDebug;
#endif


#ifndef PATCHED_SHADER
layout(location = 0) out vec4 outColour;
#else

//Bind the model buffer and import the model system as we need it
#define MODEL_BUFFER_BINDING 3
#import <voxy:lod/block_model.glsl>

#endif

#import <voxy:lod/gl46/bindings.glsl>
#if !defined(PATCHED_SHADER) && !defined(TRANSLUCENT)
bool leafHasNativeSection(vec3 cameraRelative, uint face) {
    vec3 normal = vec3(uint((face>>1)==2u), uint((face>>1)==0u), uint((face>>1)==1u))
            * (float(face&1u)*2.0-1.0);
    // Move just inside the face so an exact section edge belongs to its own block.
    ivec3 section = baseSectionPos*2 + ivec3(floor((cameraRelative+cameraSubPos-normal*0.001)/16.0));
    uint mask = uint(nativeSectionTableSize-1);
    uint slot = (uint(section.x)*73856093u ^ uint(section.y)*19349663u ^ uint(section.z)*83492791u)&mask;
    for (int i=0;i<nativeSectionTableSize;i++) {
        ivec4 entry = nativeSections[slot];
        if (entry.w == 0) return false;
        if (all(equal(entry.xyz,section))) return true;
        slot = (slot+1u)&mask;
    }
    return false;
}
#endif

vec4 uint2vec4RGBA(uint colour) {
    return vec4((uvec4(colour)>>uvec4(24,16,8,0))&uvec4(0xFF))/255.0;
}

//bool useMipmaps() {
//    return (interData.x&2u)==0u;
//}

uint tintingState() {
    return (interData.x>>2)&3u;
}

bool useDiscard() {
    return (interData.x&1u)==1u;
}

uint getFace() {
    return (interData.x>>4)&7u;
}

#ifdef PATCHED_SHADER
vec2 getLightmap() {
    vec2 light = vec2((interData.y>>4)&0xFu, interData.y&0xFu)/15.0;
    // Shaderpacks expect light levels, not texture-sampling coordinates.
    if ((modelData[interData.x >> 16].flagsA & 2048u) != 0u) return light;
    return clamp(light, vec2(8.0f/256), vec2(248.0f/256));
}
#endif

uint getModelId() {
    return interData.x>>16;
}

vec2 getBaseUV() {
    uint face = getFace();
    uint modelId = interData.x>>16;
    vec2 modelUV = vec2(modelId&0xFFu, (modelId>>8)&0xFFu)*(1.0/(256.0));
    return modelUV + (vec2(face>>1, face&1u) * (1.0/(vec2(3.0, 2.0)*256.0)));
}


#ifdef PATCHED_SHADER
struct VoxyFragmentParameters {
    //TODO: pass in derivative data
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId;//Same as iris's modelId
};

void voxy_emitFragment(VoxyFragmentParameters parameters);
#else

vec4 computeColour(vec2 texturePos, vec4 colour) {
    //Conditional tinting, TODO: FIXME: this is better but still not great, try encode data into the top bit of alpha so its per pixel

    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction == 1) {//partial tint
        vec4 tintTest = textureLod(blockModelAtlas, texturePos, 0);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    if (doTint) {
        colour *= uint2vec4RGBA(interData.z).yzwx;
    }
    return (colour * uint2vec4RGBA(interData.y)) + vec4(0,0,0,float(interData.w&0xFFu)/255);
}

#endif


#if defined(TRANSLUCENT) && !defined(PATCHED_SHADER)
layout(binding = 3) uniform sampler2D iceOpaqueDepth;
layout(binding = 4) uniform sampler2D iceNativeDepth;
layout(location = 8) uniform mat4 iceInverseMVP;
layout(location = 9) uniform mat4 iceNativeInverseMVP;

// AO on the ice surface from nearby opaque terrain, not from ice's own underside.
float iceContactAO() {
    vec2 size = vec2(textureSize(iceOpaqueDepth, 0));
    float occlusion = 0.0;
    const float radius = 1.5;
    for (int i = 0; i < 12; i++) {
        float angle = (float(i)+0.5)*2.39996323;
        float r = radius*sqrt((float(i)+0.5)/12.0);
        vec3 offset = vec3(cos(angle)*r, 0.0, sin(angle)*r);
        vec4 clip = MVP*vec4(vPos + cameraSubPos + offset, 1.0);
        if (clip.w <= 0.0) continue;
        vec2 sampleUV = clip.xy/clip.w*0.5+0.5;
        if (any(lessThan(sampleUV, vec2(0))) || any(greaterThanEqual(sampleUV, vec2(1)))) continue;
        sampleUV = (floor(sampleUV*size)+0.5)/size;
        float d = texture(iceOpaqueDepth, sampleUV).r;
        if (d == 1.0) continue;
        vec4 p;
        if (d == 0.0) {
            d = texture(iceNativeDepth, sampleUV).r;
            if (d == 1.0) continue;
            p = iceNativeInverseMVP*vec4(sampleUV*2.0-1.0, d*2.0-1.0, 1.0);
        } else {
            p = iceInverseMVP*vec4(sampleUV*2.0-1.0, d*2.0-1.0, 1.0);
        }
        vec3 delta = p.xyz/p.w-vPos;
        float distance = length(delta);
        // Bias rejects coplanar terrain; radius rejects unrelated distant geometry.
        if (delta.y <= 0.02 || distance <= 0.02 || distance >= radius) continue;
        occlusion += (delta.y/distance)*(1.0-distance/radius);
    }
    return clamp(1.0-occlusion*(2.0/12.0), 0.5, 1.0);
}
#endif

void main() {
    #ifdef TRANSLUCENT
    #ifdef PATCHED_SHADER
    bool isIce = (modelData[getModelId()].flagsA & 2048u) != 0u;
    #else
    bool isIce = (interData.w & (1u<<16)) != 0u;
    #endif
    if (isIce) {
        uint face = getFace();
        vec3 normal = vec3(uint((face>>1)==2u), uint((face>>1)==0u), uint((face>>1)==1u))
                * (float(face&1u)*2.0-1.0);
        // Native ice culls backfaces. Drawing the underside too makes alpha order-dependent.
        if (dot(normal, vPos) >= 0.0) discard;
    }
    #endif
    if (lodFadeRange.y > 0.0f) {
        float lodFade = smoothstep(lodFadeRange.x, lodFadeRange.y, length(vPos));
        if (lodFade <= lodBorderBayer(gl_FragCoord.xy)) {
            discard;
            return;
        }
    }
    //vec2 uv = vec2(0);
    //Tile is the tile we are in
    vec2 tile;
    #ifdef USE_NV_BARRY
    #ifdef USE_SINGLE_TRI
    if (gl_BaryCoordNV.x>=0.5||gl_BaryCoordNV.y>=0.5) discard;
    vec2 uv = gl_BaryCoordNV.yx*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1)*2;
    #else
    vec2 uv = mix(gl_BaryCoordNV.yx, 1-gl_BaryCoordNV.xz, gl_PrimitiveID&1)*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1);
    #endif
    #endif

    vec2 uvFrac = modf(uv, tile);
    // uv exactly on the far quad edge lands one tile past the end. Fold it back
    // into the last tile instead of punching a hole.
    vec2 lastTile = vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu);
    bvec2 pastEnd = greaterThan(tile, lastTile);
    tile = min(tile, lastTile);
    uvFrac = mix(uvFrac, vec2(1.0f - 1.0f/1024.0f), pastEnd);
    // Clamp only the atlas border. Rescaling the tile moves partial-block alpha edges.
    const vec2 atlasTileScale = 1.0/(vec2(3.0, 2.0)*256.0);
    vec2 texPos = clamp(uvFrac, vec2(1.0/32.0), vec2(31.0/32.0))*atlasTileScale + getBaseUV();
    float mip = clamp(ceil(textureQueryLod(blockModelAtlas, uvFrac*atlasTileScale + getBaseUV()).x), 0.0, 4.0);
    float texelInset = exp2(mip)/32.0;
    vec2 filteredTexPos = clamp(uvFrac, vec2(texelInset), vec2(1.0-texelInset))*atlasTileScale + getBaseUV();
    vec4 colour;
//This is deprecated, TODO: remove the non mip code path
    //if (useMipmaps())
    {
        vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
        vec2 dx = dFdx(uvSmol);//vec2(lDx, dDx);
        vec2 dy = dFdy(uvSmol);//vec2(lDy, dDy);
        colour = textureGrad(blockModelAtlas, filteredTexPos, dx, dy);
    }// else {
    //    colour = textureLod(blockModelAtlas, texPos, 0);
    //}

    //If we are in shaders and are a helper invocation, just exit, as it enables extra performance gains for small sized
    // fragments, we do this here after derivative computation
    //Trying it with all shaders
    //#ifdef PATCHED_SHADER
    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif
    //#endif

    // tile is clamped above; no fragment is ever outside the quad's tiles.

    // Skip duplicate leaves, fluids, glass and ice inside native terrain.
    #ifdef PATCHED_SHADER
    #ifdef TRANSLUCENT
    bool excludeNativeOverlap = (modelData[getModelId()].flagsA & 3584u) != 0u;
    #else
    bool excludeNativeOverlap = (modelData[getModelId()].flagsA & 3840u) != 0u;
    #endif
    #else
    bool excludeNativeOverlap = (interData.x & 128u) != 0u;
    #endif
#if !defined(PATCHED_SHADER) && !defined(TRANSLUCENT)
    if (lodFadeRange.y == 0.0 && nativeSectionTableSize > 0 && (interData.w & (1u<<17)) != 0u) {
        excludeNativeOverlap = leafHasNativeSection(vPos, getFace());
    }
#endif
    bool fadeNativeOverlap = false;
    if (excludeNativeOverlap) {
        float sectionExitDepth = texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r;
        // Include the exit face and undo the outline depth bias.
        float exitTolerance = 0.00025 * gl_FragCoord.w + 2.0 / 16777215.0;
        if (sectionExitDepth > 0.0 && gl_FragCoord.z <= sectionExitDepth + exitTolerance) {
            if (lodFadeRange.y < 0.0f) {
                fadeNativeOverlap = true;
            } else {
                discard;
                return;
            }
        }
    }

#if !defined(PATCHED_SHADER) && !defined(TRANSLUCENT)
    // Fading cutouts must not occlude the background or enter its HiZ buffer.
    if (fadeNativeOverlap != (cutoutFadePass == 1)) discard;
#endif


    //Also, small quad is really fking over the mipping level somehow
    #ifndef TRANSLUCENT
    colour.a = 1.0f;
    if (useDiscard() && (textureLod(blockModelAtlas, texPos, 0).a <= 0.1f)) {
    //if (useDiscard() && (colour.a <= 0.1f)) {
    #else
    if (textureLod(blockModelAtlas, texPos, 0).a == 0.0f) {
    #endif
        //This is stupidly stupidly bad for divergence
        //TODO: FIXME, basicly what this do is sample the exact pixel (no lod) for discarding, this stops mipmapping fucking it over
        #ifndef DEBUG_RENDER
        discard;
        return;
        #endif
    }

    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    #ifndef PATCHED_SHADER
    colour = computeColour(texPos, colour);
    #ifdef TRANSLUCENT
    if (isIce && getFace() == 1u) colour.rgb *= iceContactAO();
    #endif
    #ifndef TRANSLUCENT
    // Bit 7 survives in the raw colour buffer for the fade composite.
    if (fadeNativeOverlap) colour.a += 128.0/255.0;
    #endif
    outColour = colour;

    #ifdef DEBUG_RENDER
    uint hash = quadDebug*1231421+123141;
    hash ^= hash>>16;
    hash = hash*1231421+123141;
    hash ^= hash>>16;
    hash = hash * 1827364925 + 123325621;
    outColour = vec4(float(hash&15u)/15, float((hash>>4)&15u)/15, float((hash>>8)&15u)/15, 0);
    #endif

    #else
    uint modelId = getModelId();
    BlockModel model = modelData[modelId];
    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction==1) {//Partial tint
        vec4 tintTest = texture(blockModelAtlas, texPos, -2);
        if (abs(tintTest.r-tintTest.g) < 0.02f && abs(tintTest.g-tintTest.b) < 0.02f) {
            doTint = true;
        }
    }
    vec4 tint = vec4(1);
    if (doTint) {
        tint = uint2vec4RGBA(interData.z).yzwx;
    }

    uint face = getFace();
    face ^= uint((face&1u)!=uint(gl_FrontFacing!=((face>>1)!=0u)));
    voxy_emitFragment(VoxyFragmentParameters(colour, tile, texPos, face, modelId, getLightmap(), tint, model.customId));

    #endif
}



//#ifdef GL_KHR_shader_subgroup_quad
/*
uint hash = (uint(tile.x)*(1<<16))^uint(tile.y);
uint horiz = subgroupQuadSwapHorizontal(hash);
bool sameTile = horiz==hash;
uint sv = mix(uint(-1), hash, sameTile);
uint vert = subgroupQuadSwapVertical(sv);
sameTile = sameTile&&vert==hash;
mipBias = sameTile?0:-5.0;
*/
/*
vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
float lDx = subgroupQuadSwapHorizontal(uvSmol.x)-uvSmol.x;
float lDy = subgroupQuadSwapVertical(uvSmol.y)-uvSmol.y;
float dDx = subgroupQuadSwapDiagonal(lDx);
float dDy = subgroupQuadSwapDiagonal(lDy);
vec2 dx = vec2(lDx, dDx);
vec2 dy = vec2(lDy, dDy);
colour = textureGrad(blockModelAtlas, filteredTexPos, dx, dy);
*/
//#else
//colour = texture(blockModelAtlas, texPos);
//#endif

