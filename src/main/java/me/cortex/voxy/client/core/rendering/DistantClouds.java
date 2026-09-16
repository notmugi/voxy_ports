package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.core.rendering.util.CloudMotion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL45C.*;

/** Independent no-shader cloud pass. TextureManager owns the resource-pack texture and its reloads. */
public final class DistantClouds {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    private FullscreenBlit pass;
    private int sampler;
    private final CloudMotion motion = new CloudMotion();

    public static boolean replacesVanilla() {
        var mc = Minecraft.getInstance();
        return VoxyConfig.CONFIG.massiveClouds && VoxyConfig.CONFIG.isRenderingEnabled()
                && !IrisUtil.irisShaderPackEnabled() && mc.level != null
                && mc.level.effects().skyType() == DimensionSpecialEffects.SkyType.NORMAL
                && ((IGetVoxyRenderSystem) mc.levelRenderer).getVoxyRenderSystem() != null;
    }

    public void render(Viewport<?> viewport, int lodDepthTexture) {
        if (!replacesVanilla()) return;
        var mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() != FogType.NONE
                || camera.getEntity() instanceof LivingEntity living
                    && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))
                || mc.level.effects().isFoggyAt(Mth.floor(viewport.cameraX), Mth.floor(viewport.cameraZ))) return;

        if (this.pass == null) {
            this.pass = new FullscreenBlit("voxy:post/distant_clouds.frag");
            this.sampler = glCreateSamplers();
            glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glSamplerParameteri(this.sampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
            glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        // Texture loading can bind texture units; resolve it before establishing our own state.
        int texture = mc.getTextureManager().getTexture(TEXTURE).getId();
        int width = glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_WIDTH);
        int height = glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_HEIGHT);
        if (width <= 0 || height <= 0) return;
        var config = VoxyConfig.CONFIG;
        float cellSize = config.cloudCellSize;
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(true);
        double period = width * (double) cellSize;
        double drift = this.motion.sample(mc.level.getGameTime(), partial, config.cloudSpeed, period);
        float originX = (float) positiveModulo((viewport.cameraX + drift) / cellSize, width);
        float originZ = (float) positiveModulo(viewport.cameraZ / cellSize + 0.33, height);
        float distance = Math.clamp(config.sectionRenderDistance, 2, 64) * 512f;
        var colour = mc.level.getCloudColor(partial);

        this.pass.bind();
        try (var stack = MemoryStack.stackPush()) {
            var matrix = stack.mallocFloat(16);
            glUniformMatrix4fv(0, false, new Matrix4f(viewport.MVP).invert().get(matrix));
            glUniformMatrix4fv(1, false, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView).get(matrix));
        }
        glUniform4f(2, originX, originZ, (float) (config.cloudHeight - viewport.cameraY), config.cloudThickness);
        glUniform4f(3, cellSize, distance * (config.cloudFadeStart / 100f), distance * (config.cloudFadeEnd / 100f), 0);
        glUniform3f(4, (float) colour.x, (float) colour.y, (float) colour.z);
        glBindTextureUnit(0, texture);
        glBindTextureUnit(1, lodDepthTexture);
        glBindSampler(0, this.sampler);
        glBindSampler(1, this.sampler);
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int depthFunc = glGetInteger(GL_DEPTH_FUNC);
        int srcRgb = glGetInteger(GL_BLEND_SRC_RGB), dstRgb = glGetInteger(GL_BLEND_DST_RGB);
        int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        try {
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(false);
            glDisable(GL_CULL_FACE);
            glEnable(GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            this.pass.blit();
        } finally {
            glDepthMask(depthMask);
            glDepthFunc(depthFunc);
            setEnabled(GL_DEPTH_TEST, depthTest);
            setEnabled(GL_BLEND, blend);
            setEnabled(GL_CULL_FACE, cull);
            glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            glBindSampler(0, 0);
            glBindSampler(1, 0);
            glBindTextureUnit(0, 0);
            glBindTextureUnit(1, 0);
        }
    }

    private static void setEnabled(int state, boolean enabled) {
        if (enabled) glEnable(state); else glDisable(state);
    }

    private static double positiveModulo(double value, double period) {
        return value - Math.floor(value / period) * period;
    }

    public void free() {
        if (this.pass != null) {
            this.pass.delete();
            glDeleteSamplers(this.sampler);
            this.pass = null;
        }
    }
}
