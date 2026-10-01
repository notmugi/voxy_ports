package me.cortex.voxy.client.core.rendering.post;

import me.cortex.voxy.client.core.gl.GlTexture;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL43C.glCopyImageSubData;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL41C.glProgramUniform2f;
import static org.lwjgl.opengl.GL41C.glProgramUniformMatrix4fv;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glGetFramebufferAttachmentParameteri;
import static org.lwjgl.opengl.GL45C.glTextureParameteri;

// Cross-fades the pre-LOD vanilla image with the composited image across the
// vanilla render border. Distance is reconstructed per pixel from vanilla depth.
public class BorderFade {
    private final FullscreenBlit blit = new FullscreenBlit("voxy:post/fullscreen2.vert", "voxy:post/border_fade.frag");
    private GlTexture colorCopy;
    private int width = -1;
    private int height = -1;
    private int depthTex = -1;

    //Copies the current framebuffer's colour; remembers its depth for apply().
    public boolean capture(int fb, int width, int height) {
        this.depthTex = -1;
        if (glGetFramebufferAttachmentParameteri(fb, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_TEXTURE) {
            return false;
        }
        if (glGetFramebufferAttachmentParameteri(fb, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL_TEXTURE) {
            return false;
        }
        int colorTex = glGetFramebufferAttachmentParameteri(fb, GL_COLOR_ATTACHMENT0, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int depthTex = glGetFramebufferAttachmentParameteri(fb, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (colorTex == 0 || depthTex == 0) {
            return false;
        }
        if (this.width != width || this.height != height) {
            if (this.colorCopy != null) {
                this.colorCopy.free();
            }
            this.colorCopy = new GlTexture(GL_TEXTURE_2D).store(GL_RGBA8, 1, width, height);
            glTextureParameteri(this.colorCopy.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameteri(this.colorCopy.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            this.width = width;
            this.height = height;
        }
        glCopyImageSubData(colorTex, GL_TEXTURE_2D, 0, 0, 0, 0,
                this.colorCopy.id, GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
        this.depthTex = depthTex;
        return true;
    }

    //Draws the vanilla image back over the framebuffer with the fade weight.
    //Depth test and writes are off, so sampling the attached depth is legal.
    public void apply(Matrix4f vanillaProjection, Matrix4f modelView, float fadeStart, float fadeEnd) {
        if (this.depthTex < 0) {
            return;
        }
        var invViewProj = new Matrix4f(vanillaProjection).mul(modelView).invert();
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
        this.blit.bind();
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        try (var stack = MemoryStack.stackPush()) {
            var mat = stack.mallocFloat(16);
            invViewProj.get(mat);
            glProgramUniformMatrix4fv(program, 0, false, mat);
        }
        glProgramUniform2f(program, 4, fadeStart, fadeEnd);
        glBindTextureUnit(0, this.colorCopy.id);
        glBindTextureUnit(1, this.depthTex);
        this.blit.blit();
        glBindTextureUnit(0, 0);
        glBindTextureUnit(1, 0);
        glDisable(GL_BLEND);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        this.depthTex = -1;
    }

    public void free() {
        this.blit.delete();
        if (this.colorCopy != null) {
            this.colorCopy.free();
        }
    }
}
