package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.core.rendering.DistantClouds;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Cancel before Sodium's getCloudHeight injection; Sodium Extra's height/renderer hooks never run.
@Mixin(value = LevelRenderer.class, priority = 1100)
public class MixinDistantClouds {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, order = 100)
    private void voxy$replaceClouds(PoseStack poses, Matrix4f view, Matrix4f projection,
                                    float partialTick, double x, double y, double z, CallbackInfo ci) {
        if (DistantClouds.replacesVanilla()) ci.cancel();
    }
}
