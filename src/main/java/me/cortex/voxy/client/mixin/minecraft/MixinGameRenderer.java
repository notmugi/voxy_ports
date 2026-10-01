package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {
    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void voxy$keepNativeTerrain(CallbackInfoReturnable<Float> cir) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !VoxyConfig.CONFIG.enabled || !VoxyConfig.CONFIG.enableRendering
                || IrisUtil.irisShaderPackEnabled() || IGetVoxyRenderSystem.getNullable() == null) return;
        double y = mc.gameRenderer.getMainCamera().getPosition().y;
        double vertical = Math.max(Math.abs(y - mc.level.getMinBuildHeight()),
                Math.abs(y - mc.level.getMaxBuildHeight()));
        // NT keeps whole chunk columns visible, even far below the camera.
        cir.setReturnValue((float) Math.max(cir.getReturnValueF(), vertical + cir.getReturnValueF()));
    }
}
