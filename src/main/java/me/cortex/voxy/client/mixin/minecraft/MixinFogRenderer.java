package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.sugar.Local;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.fog.VoxyFogTuning;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.RenderSystem;

@Mixin(value = FogRenderer.class,remap = true)
public class MixinFogRenderer {
    @Inject(
            method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
            at = @At("TAIL"),
            cancellable = true
    )
    private static void voxy$overrideFog(
            Camera camera,
            FogMode fogMode,
            float viewDistance,
            boolean thickFog,
            float tickDelta,
            CallbackInfo ci
    ) {
        if (fogMode != FogMode.FOG_TERRAIN || thickFog
                || camera.getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE
                || (camera.getEntity() instanceof net.minecraft.world.entity.LivingEntity living
                    && (living.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                        || living.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)))) return;
        var vrs = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
        boolean voxyActive = vrs != null && vrs.getVoxyRenderSystem() != null;
        // Use the same fog range for terrain and LODs.
        if (voxyActive) {
            var range = VoxyFogTuning.getActiveRange();
            if (range != null) {
                RenderSystem.setShaderFogStart(range.start());
                RenderSystem.setShaderFogEnd(range.end());
                RenderSystem.setShaderFogShape(com.mojang.blaze3d.shaders.FogShape.CYLINDER);
                return;
            }
        }
        // Enabling vanilla fog must preserve setupFog's actual values (water/status/dimension
        // fog included), not overwrite its end unconditionally with the render distance.
        if (!VoxyConfig.CONFIG.renderVanillaFog && voxyActive) {
            RenderSystem.setShaderFogStart(999999999);
            RenderSystem.setShaderFogEnd(999999999);
        }
    }
}
