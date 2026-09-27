package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.config.LiveOptionRegistry;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only our live sliders skip staging. */
@Mixin(value = StatefulOption.class, remap = false)
public abstract class MixinLiveOption {
    @Inject(method = "modifyValue", at = @At("HEAD"), cancellable = true)
    private void voxy$liveValue(Object value, CallbackInfo ci) {
        if (LiveOptionRegistry.update(((AccessorOptionId)this).voxy$getId(), value)) ci.cancel();
    }
    @Inject(method = {"getValidatedValue", "getAppliedValue"}, at = @At("HEAD"), cancellable = true)
    private void voxy$readLive(CallbackInfoReturnable<Object> cir) {
        Integer value = LiveOptionRegistry.get(((AccessorOptionId)this).voxy$getId());
        if (value != null) cir.setReturnValue(value);
    }
    @Inject(method = "hasChanged", at = @At("HEAD"), cancellable = true)
    private void voxy$alreadySaved(CallbackInfoReturnable<Boolean> cir) {
        if (LiveOptionRegistry.get(((AccessorOptionId)this).voxy$getId()) != null) cir.setReturnValue(false);
    }
}
