package me.cortex.voxy.client.core.rendering.post;

import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL45C.*;

/** Separate native and LOD water layers, mixed once with premultiplied alpha. */
public final class TranslucentBorderFade {
    private final DepthFramebuffer lod = new DepthFramebuffer(GL_DEPTH24_STENCIL8);
    private final DepthFramebuffer nativeLayer = new DepthFramebuffer(GL_DEPTH_COMPONENT32F);
    private GlTexture lodColour, nativeColour;
    private final FullscreenBlit depthCopy = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/native_depth_copy.frag");
    private final FullscreenBlit composite = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/translucent_fade.frag");
    private Viewport<?> viewport;
    private int target;
    private boolean pending, capturing;
    private float start, end;
    private final float[] fogParams = new float[4], fogColour = new float[4];

    public void prepare(Viewport<?> viewport, int opaqueFramebuffer, float[] range) {
        this.pending = false;
        this.capturing = false;
        this.viewport = viewport;
        this.start = range[0];
        this.end = range[1];
        if (this.lod.resize(viewport.width, viewport.height)) {
            this.nativeLayer.resize(viewport.width, viewport.height);
            for (int depth : new int[]{this.lod.getDepthTex().id, this.nativeLayer.getDepthTex().id}) {
                glTextureParameteri(depth, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTextureParameteri(depth, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTextureParameteri(depth, GL_TEXTURE_COMPARE_MODE, GL_NONE);
            }
            if (this.lodColour != null) {
                this.lodColour.free();
                this.nativeColour.free();
            }
            this.lodColour = colour(viewport.width, viewport.height);
            this.nativeColour = colour(viewport.width, viewport.height);
            this.lod.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.lodColour).verify();
            this.nativeLayer.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.nativeColour).verify();
        }
        glBlitNamedFramebuffer(opaqueFramebuffer, this.lod.framebuffer.id, 0, 0, viewport.width, viewport.height,
                0, 0, viewport.width, viewport.height, GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT, GL_NEAREST);
        glClearTexImage(this.lodColour.id, 0, GL_RGBA, GL_FLOAT, new float[4]);
        this.lod.bind();
    }

    private static GlTexture colour(int width, int height) {
        GlTexture texture = new GlTexture().store(GL_RGBA8, 1, width, height);
        glTextureParameteri(texture.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(texture.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return texture;
    }

    public void bindLod() {
        this.lod.bind();
    }
    public void ready(float[] params, float[] colour) {
        System.arraycopy(params, 0, this.fogParams, 0, 4);
        System.arraycopy(colour, 0, this.fogColour, 0, 4);
        this.pending = true;
    }
    public void cancel() {
        this.pending = false;
    }

    // Capture before Sodium submits native translucents.
    public void beginNative() {
        if (!this.pending || this.capturing) return;
        this.pending = false;
        this.target = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int depth = glGetNamedFramebufferAttachmentParameteri(this.target, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (depth == 0 || glGetNamedFramebufferAttachmentParameteri(this.target, GL_DEPTH_ATTACHMENT,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_TEXTURE) return;
        try (State ignored = new State()) {
            this.nativeLayer.bind();
            glViewport(0, 0, this.viewport.width, this.viewport.height);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_ALWAYS);
            glDepthMask(true);
            glColorMask(false, false, false, false);
            glBindSampler(0, 0);
            glBindTextureUnit(0, depth);
            this.depthCopy.blit();
            glClearTexImage(this.nativeColour.id, 0, GL_RGBA, GL_FLOAT, new float[4]);
        }
        this.nativeLayer.bind();
        this.capturing = true;
    }

    // Composite before Sodium ends the translucent pass.
    public void endNative() {
        if (!this.capturing) return;
        this.capturing = false;
        try (State ignored = new State()) {
            glBindFramebuffer(GL_FRAMEBUFFER, this.target);
            glViewport(0, 0, this.viewport.width, this.viewport.height);
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_ALWAYS);
            glDepthMask(true);
            glColorMask(true, true, true, true);
            glEnable(GL_BLEND);
            glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            this.composite.bind();
            float[] matrix = new float[16];
            Matrix4f nativeMatrix = new Matrix4f(this.viewport.vanillaProjection).mul(this.viewport.modelView);
            glUniformMatrix4fv(0, false, new Matrix4f(nativeMatrix).invert().get(matrix));
            glUniformMatrix4fv(1, false, new Matrix4f(this.viewport.MVP).invert().get(matrix));
            glUniformMatrix4fv(2, false, nativeMatrix.get(matrix));
            glUniform2f(3, this.start, this.end);
            glUniform4fv(4, this.fogParams);
            glUniform4fv(5, this.fogColour);
            int[] textures = {this.nativeColour.id, this.nativeLayer.getDepthTex().id, this.lodColour.id,
                    this.lod.getDepthTex().id, this.viewport.depthBoundingBuffer.getDepthTex().id};
            for (int i = 0; i < textures.length; i++) {
                glBindSampler(i, 0);
                glBindTextureUnit(i, textures[i]);
            }
            this.composite.blit();
        }
        glBindFramebuffer(GL_FRAMEBUFFER, this.target);
    }

    public void free() {
        this.lod.free();
        this.nativeLayer.free();
        this.depthCopy.delete();
        this.composite.delete();
        if (this.lodColour != null) {
            this.lodColour.free();
            this.nativeColour.free();
        }
    }

    // Raw GL calls must leave Sodium's cached state unchanged.
    private static final class State implements AutoCloseable {
        final int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING), read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        final int program = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        final int depthFunc = glGetInteger(GL_DEPTH_FUNC), active = glGetInteger(GL_ACTIVE_TEXTURE);
        final int src = glGetInteger(GL_BLEND_SRC_RGB), dst = glGetInteger(GL_BLEND_DST_RGB);
        final int srcA = glGetInteger(GL_BLEND_SRC_ALPHA), dstA = glGetInteger(GL_BLEND_DST_ALPHA);
        final int eq = glGetInteger(GL_BLEND_EQUATION_RGB), eqA = glGetInteger(GL_BLEND_EQUATION_ALPHA);
        final boolean depth = glIsEnabled(GL_DEPTH_TEST), stencil = glIsEnabled(GL_STENCIL_TEST);
        final boolean blend = glIsEnabled(GL_BLEND), cull = glIsEnabled(GL_CULL_FACE), mask = glGetBoolean(GL_DEPTH_WRITEMASK);
        final int[] viewport = new int[4], colours = new int[4], textures = new int[5], samplers = new int[5];
        State() {
            glGetIntegerv(GL_VIEWPORT, this.viewport);
            glGetIntegerv(GL_COLOR_WRITEMASK, this.colours);
            for (int i = 0; i < 5; i++) {
                glActiveTexture(GL_TEXTURE0+i);
                this.textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D);
                this.samplers[i] = glGetIntegeri(GL_SAMPLER_BINDING, i);
            }
            glActiveTexture(this.active);
        }
        public void close() {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.draw);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, this.read);
            glUseProgram(this.program);
            glBindVertexArray(this.vao);
            glDepthFunc(this.depthFunc);
            glDepthMask(this.mask);
            set(GL_DEPTH_TEST, this.depth);
            set(GL_STENCIL_TEST, this.stencil);
            set(GL_BLEND, this.blend);
            set(GL_CULL_FACE, this.cull);
            glBlendFuncSeparate(this.src, this.dst, this.srcA, this.dstA);
            glBlendEquationSeparate(this.eq, this.eqA);
            glColorMask(this.colours[0]!=0, this.colours[1]!=0, this.colours[2]!=0, this.colours[3]!=0);
            glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
            for (int i = 0; i < 5; i++) {
                glBindTextureUnit(i, this.textures[i]);
                glBindSampler(i, this.samplers[i]);
            }
            glActiveTexture(this.active);
        }
        private static void set(int flag, boolean enabled) {
            if (enabled) glEnable(flag); else glDisable(flag);
        }
    }
}
