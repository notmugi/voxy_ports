package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import com.mojang.blaze3d.systems.RenderSystem;
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
    private final GlFramebuffer fbSSAO = new GlFramebuffer();
    private final DepthFramebuffer fb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);

    private final FullscreenBlit finalBlit;
    private final me.cortex.voxy.client.core.rendering.DistantClouds distantClouds =
            new me.cortex.voxy.client.core.rendering.DistantClouds();
    private final me.cortex.voxy.client.core.rendering.util.NostalgicLightmap nostalgicLightmap =
            new me.cortex.voxy.client.core.rendering.util.NostalgicLightmap();

    public void bindLightmap(Viewport<?> viewport) {
        glBindSampler(1, 0);
        glBindTextureUnit(1, this.nostalgicLightmap.getTexture(viewport.frameId));
    }

    private final SSAO ssao = SSAO.createSSAO(SSAO.SSAOMode.AUTO);

    protected NormalRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier) {
        super(nodeManager, nodeCleaner, traversal, frexSupplier);
        this.finalBlit = new FullscreenBlit("voxy:post/blit_texture_depth_cutout.frag",
                a->a.define("EMIT_COLOUR").define("USE_ENV_FOG"));
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
        this.initDepthStencil(sourceFB, this.fb.framebuffer.id, viewport.width, viewport.height, viewport.width, viewport.height);

        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport) {
        this.ssao.computeSSAO(viewport, this.colourSSAOTex, this.colourTex, this.fb.getDepthTex(), this.sourceDepthTexture);
        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        this.finalBlit.bind();

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        float[] fogColour = RenderSystem.getShaderFogColor();
        var range = me.cortex.voxy.client.core.rendering.DistanceFog.getRange();
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
            shape = 2f; // RenderDistanceTracker tracks an XZ circle, not a sphere.
        }
        if (Float.isFinite(fogEnd) && fogEnd - fogStart > 1f && fogEnd < 1.0e8f) {
            float invDelta = 1f / (fogEnd - fogStart);
            glUniform4f(4, invDelta, -fogStart * invDelta, 1f, shape);
            glUniform4f(5, fogColour[0], fogColour[1], fogColour[2], 1f);
        } else {
            glUniform4f(4, 0, 0, 0, 0);
            glUniform4f(5, 0, 0, 0, 0);
        }
        glBindSampler(3, 0);
        glBindTextureUnit(3, this.colourSSAOTex.id);

        //Do alpha blending

        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        AbstractRenderPipeline.transformBlitDepth(this.finalBlit, this.fb.getDepthTex().id, sourceFrameBuffer, viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
        glDisable(GL_BLEND);
        // Composite independently of vanilla's Clouds option/call site, before its translucent terrain.
        // Ray hits are clipped against Voxy depth; hardware depth also tests the vanilla foreground.
        this.distantClouds.render(viewport, this.fb.getDepthTex().id);
        //glBlitNamedFramebuffer(this.fbSSAO.id, sourceFrameBuffer, 0,0, viewport.width, viewport.height, 0,0, viewport.width, viewport.height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    public void free() {
        this.finalBlit.delete();
        this.distantClouds.free();
        this.ssao.free();
        this.nostalgicLightmap.free();
        this.fb.free();
        this.fbSSAO.free();
        if (this.colourTex != null) {
            this.colourTex.free();
            this.colourSSAOTex.free();
        }
        super.free0();
    }
}
