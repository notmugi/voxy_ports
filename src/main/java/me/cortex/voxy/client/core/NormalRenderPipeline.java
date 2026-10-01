package me.cortex.voxy.client.core;

import com.mojang.blaze3d.systems.RenderSystem;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.DistanceFog;
import me.cortex.voxy.client.core.rendering.DistantClouds;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.post.TranslucentBorderFade;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.NostalgicLightmap;

import net.minecraft.client.Minecraft;

import org.joml.Matrix4f;

import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL14.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glTextureParameterf;

public class NormalRenderPipeline extends AbstractRenderPipeline {
    private GlTexture colourTex;
    private int sourceDepthTexture;
    private GlTexture colourSSAOTex;
    private GlTexture iceOpaqueDepth;
    private final GlFramebuffer iceDepthFb = new GlFramebuffer();

    public void bindIceContactAO(Viewport<?> viewport) {
        glBindSampler(3, 0);
        glBindTextureUnit(3, this.fadeRange != null ? this.fb.getDepthTex().id : this.iceOpaqueDepth.id);
        glBindSampler(4, 0);
        glBindTextureUnit(4, this.sourceDepthTexture);
        glUniformMatrix4fv(8, false, new Matrix4f(viewport.MVP).invert().get(FADE_MAT));
        glUniformMatrix4fv(9, false, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView).invert().get(FADE_MAT));
    }

    private void captureIceDepth(Viewport<?> viewport) {
        if (this.iceOpaqueDepth == null || this.iceOpaqueDepth.getWidth() != viewport.width
                || this.iceOpaqueDepth.getHeight() != viewport.height) {
            if (this.iceOpaqueDepth != null) this.iceOpaqueDepth.free();
            this.iceOpaqueDepth = new GlTexture().store(GL_R32F, 1, viewport.width, viewport.height);
            glTextureParameterf(this.iceOpaqueDepth.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.iceOpaqueDepth.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            this.iceDepthFb.bind(GL_COLOR_ATTACHMENT0, this.iceOpaqueDepth).verify();
        }
        // Snapshot before translucent depth writes.
        glBindFramebuffer(GL_FRAMEBUFFER, this.iceDepthFb.id);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_BLEND);
        glColorMask(true, true, true, true);
        glBindSampler(0, 0);
        glBindTextureUnit(0, this.fb.getDepthTex().id);
        this.depthToColour.blit();
        glBindTextureUnit(0, 0);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glEnable(GL_STENCIL_TEST);
        glStencilFunc(GL_EQUAL, 1, 0xFF);
    }

    private final GlFramebuffer fbSSAO = new GlFramebuffer();
    private final DepthFramebuffer fb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);

    private final DepthFramebuffer cutoutFb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);
    private GlTexture cutoutColour, cutoutAO;
    private boolean cutoutFadePass;
    public boolean isCutoutFadePass() {
        return this.cutoutFadePass;
    }

    private final FullscreenBlit finalBlit;
    private final FullscreenBlit fadeBlit = new FullscreenBlit("voxy:post/fade_composite.frag");
    private final DistantClouds distantClouds =
            new DistantClouds();
    private final NostalgicLightmap nostalgicLightmap =
            new NostalgicLightmap();

    public void bindLightmap(Viewport<?> viewport) {
        glBindSampler(1, 0);
        glBindTextureUnit(1, this.nostalgicLightmap.getTexture(viewport.frameId));
        glUniform1i(7, this.nostalgicLightmap.isPrefiltered() ? 1 : 0);
    }

    private final TranslucentBorderFade translucentFade =
            new TranslucentBorderFade();
    private final float[] fadeFogParams = new float[4], fadeFogColour = new float[4];
    public void beginNativeTranslucent() {
        this.translucentFade.beginNative();
    }
    public void endNativeTranslucent() {
        this.translucentFade.endNative();
    }

    private float[] fadeRange;
    private GlTexture vanillaDepthCopy;
    private final GlFramebuffer vanillaDepthFb = new GlFramebuffer();
    private final FullscreenBlit depthToColour = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/depth_to_colour.frag");
    private final Matrix4f fadeInvViewProj = new Matrix4f();

    // {start, end} of the chunk-to-LOD fade in blocks, or null when off.
    public static float[] borderFadeRange() {
        if (!VoxyConfig.CONFIG.borderFade) return null;
        float border = Minecraft.getInstance().options.renderDistance().get() * 16.0f;
        float start = Math.max(8.0f, border - Math.min(32.0f, border * 0.25f));
        return new float[]{start, border};
    }

    private static final float[] FADE_MAT = new float[16];
    private final SSAO ssao = SSAO.createSSAO(SSAO.SSAOMode.AUTO);

    protected NormalRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(nodeManager, nodeCleaner, traversal, frexSupplier);
        this.finalBlit = new FullscreenBlit("voxy:post/blit_texture_depth_cutout.frag",
                a->a.define("EMIT_COLOUR").define("USE_ENV_FOG").define("BORDER_FADE"));
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFB, int srcWidth, int srcHeight) {
        if (this.colourTex == null || this.colourTex.getHeight() != viewport.height || this.colourTex.getWidth() != viewport.width) {
            if (this.colourTex != null) {
                this.colourTex.free();
                this.colourSSAOTex.free();
            }
            this.fb.resize(viewport.width, viewport.height);

            this.colourTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);
            this.colourSSAOTex = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);

            this.fb.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.colourTex).verify();
            this.fbSSAO.bind(GL_DEPTH_STENCIL_ATTACHMENT, this.fb.getDepthTex()).bind(GL_COLOR_ATTACHMENT0, this.colourSSAOTex).verify();

            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.fb.getDepthTex().id, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);
        }

        this.sourceDepthTexture = org.lwjgl.opengl.GL45C.glGetNamedFramebufferAttachmentParameteri(
                sourceFB, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        this.translucentFade.cancel();
        this.fadeRange = borderFadeRange();
        if (this.fadeRange != null) {
            // Do not sample the target depth attachment in finish().
            if (this.vanillaDepthCopy == null || this.vanillaDepthCopy.getWidth() != viewport.width
                    || this.vanillaDepthCopy.getHeight() != viewport.height) {
                if (this.vanillaDepthCopy != null) this.vanillaDepthCopy.free();
                this.vanillaDepthCopy = new GlTexture().store(org.lwjgl.opengl.GL30C.GL_R32F, 1, viewport.width, viewport.height);
                glTextureParameterf(this.vanillaDepthCopy.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTextureParameterf(this.vanillaDepthCopy.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                this.vanillaDepthFb.bind(GL_COLOR_ATTACHMENT0, this.vanillaDepthCopy).verify();
            }
            glBindFramebuffer(GL_FRAMEBUFFER, this.vanillaDepthFb.id);
            org.lwjgl.opengl.GL11C.glDisable(GL_BLEND);
            org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);
            glColorMask(true, true, true, true);
            glBindSampler(0, 0);
            glBindTextureUnit(0, this.sourceDepthTexture);
            this.depthToColour.blit();
            glBindTextureUnit(0, 0);
        }
        this.fadeInvViewProj.set(viewport.vanillaProjection).mul(viewport.modelView).invert();
        this.initDepthStencil(sourceFB, this.fb.framebuffer.id, viewport.width, viewport.height, viewport.width, viewport.height,
                this.fadeInvViewProj, this.fadeRange == null ? 0 : this.fadeRange[0]);

        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport) {
        this.ssao.computeSSAO(viewport, this.colourSSAOTex, this.colourTex, this.fb.getDepthTex(), this.sourceDepthTexture);
        // Fade translucents have private depth, so opaque depth is already immutable.
        if (this.fadeRange == null) this.captureIceDepth(viewport);
        if (this.fadeRange != null) {
            if (this.cutoutFb.resize(viewport.width, viewport.height)) {
                if (this.cutoutColour != null) {
                    this.cutoutColour.free();
                    this.cutoutAO.free();
                }
                this.cutoutColour = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);
                this.cutoutAO = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);
                for (GlTexture texture : new GlTexture[]{this.cutoutColour, this.cutoutAO, this.cutoutFb.getDepthTex()}) {
                    glTextureParameterf(texture.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                    glTextureParameterf(texture.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                }
                this.cutoutFb.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.cutoutColour).verify();
            }
            org.lwjgl.opengl.GL45C.glBlitNamedFramebuffer(this.fb.framebuffer.id, this.cutoutFb.framebuffer.id,
                    0, 0, viewport.width, viewport.height, 0, 0, viewport.width, viewport.height,
                    GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT, GL_NEAREST);
            org.lwjgl.opengl.GL44C.glClearTexImage(this.cutoutColour.id, 0, GL_RGBA, GL_FLOAT, new float[4]);
            this.cutoutFadePass = true;
            try {
                var renderer = (AbstractSectionRenderer)this.sectionRenderer;
                renderer.renderOpaque(viewport);
                // The rebuilt opaque list already includes the temporal commands.
            } finally {
                this.cutoutFadePass = false;
            }
            this.ssao.computeSSAO(viewport, this.cutoutAO, this.cutoutColour, this.cutoutFb.getDepthTex(), this.sourceDepthTexture, true);
            this.translucentFade.prepare(viewport, this.fb.framebuffer.id, this.fadeRange);
        }
        else glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        FullscreenBlit blit = this.fadeRange != null ? this.fadeBlit : this.finalBlit;
        blit.bind();

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        float[] fogColour = RenderSystem.getShaderFogColor();
        var range = DistanceFog.getRange();
        float shape = RenderSystem.getShaderFogShape() == com.mojang.blaze3d.shaders.FogShape.CYLINDER ? 1f : 0f;
        var mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        boolean environmental = camera.getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE
                || (camera.getEntity() instanceof net.minecraft.world.entity.LivingEntity living
                    && (living.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                        || living.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)))
                || (mc.level != null && mc.level.effects().isFoggyAt(
                    net.minecraft.util.Mth.floor(camera.getPosition().x), net.minecraft.util.Mth.floor(camera.getPosition().z)));
        if (range != null && !environmental) {
            fogStart = range[0];
            fogEnd = range[1];
            shape = 2f; // XZ fog
        }
        if (Float.isFinite(fogEnd) && fogEnd - fogStart > 1f && fogEnd < 1.0e8f) {
            float invDelta = 1f / (fogEnd - fogStart);
            glUniform4f(4, invDelta, -fogStart * invDelta, 1f, shape);
            glUniform4f(5, fogColour[0], fogColour[1], fogColour[2], 1f);
            this.fadeFogParams[0] = invDelta;
            this.fadeFogParams[1] = -fogStart*invDelta;
            this.fadeFogParams[2] = 1;
            this.fadeFogParams[3] = shape;
            System.arraycopy(fogColour, 0, this.fadeFogColour, 0, 3);
            this.fadeFogColour[3] = 1;
        } else {
            glUniform4f(4, 0, 0, 0, 0);
            glUniform4f(5, 0, 0, 0, 0);
            java.util.Arrays.fill(this.fadeFogParams, 0);
            java.util.Arrays.fill(this.fadeFogColour, 0);
        }
        glBindSampler(3, 0);
        glBindTextureUnit(3, this.colourSSAOTex.id);

        if (this.fadeRange != null) {
            glBindSampler(4, 0);
            glBindTextureUnit(4, this.vanillaDepthCopy.id);
            glBindSampler(6, 0);
            glBindTextureUnit(6, this.cutoutFb.getDepthTex().id);
            glBindSampler(7, 0);
            glBindTextureUnit(7, this.cutoutColour.id);
            glBindSampler(8, 0);
            glBindTextureUnit(8, this.cutoutAO.id);
            this.fadeInvViewProj.get(FADE_MAT);
            glUniformMatrix4fv(6, false, FADE_MAT);
            glUniform2f(7, this.fadeRange[0], this.fadeRange[1]);
        } else {
            glUniform1i(8, 0);
        }
        glEnable(GL_BLEND);
        glBlendFuncSeparate(this.fadeRange != null ? GL_ONE : GL_SRC_ALPHA,
                GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        var blitTransform = new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView);
        glDepthMask(true);
        // Fade shader performs the old foreground test and retains native depth in the band.
        glDepthFunc(this.fadeRange != null ? GL_ALWAYS : GL_LEQUAL);
        AbstractRenderPipeline.transformBlitDepth(blit, this.fb.getDepthTex().id, sourceFrameBuffer, viewport, blitTransform);
        glDepthFunc(GL_LEQUAL);
        if (this.fadeRange != null) {
            for (int unit : new int[]{4, 6, 7, 8}) glBindTextureUnit(unit, 0);
            this.translucentFade.ready(this.fadeFogParams, this.fadeFogColour);
        }
        glDisable(GL_BLEND);
        // Clouds use LOD depth and the native foreground mask.
        this.distantClouds.render(viewport, this.fb.getDepthTex().id);
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        if (this.cutoutFadePass) this.cutoutFb.bind();
        else this.fb.bind();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        if (this.fadeRange != null) this.translucentFade.bindLod();
        else glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    public void free() {
        if (this.iceOpaqueDepth != null) this.iceOpaqueDepth.free();
        this.iceDepthFb.free();
        this.cutoutFb.free();
        if (this.cutoutColour != null) {
            this.cutoutColour.free();
            this.cutoutAO.free();
        }
        this.translucentFade.free();
        this.finalBlit.delete();
        this.fadeBlit.delete();
        this.distantClouds.free();
        this.ssao.free();
        this.nostalgicLightmap.free();
        this.fb.free();
        this.fbSSAO.free();
        if (this.colourTex != null) {
            this.colourTex.free();
            this.colourSSAOTex.free();
        }
        if (this.vanillaDepthCopy != null) this.vanillaDepthCopy.free();
        this.vanillaDepthFb.free();
        this.depthToColour.delete();
        super.free0();
    }
}
