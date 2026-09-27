package me.cortex.voxy.client.mixin.sodium;

import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Option.class, remap = false)
public interface AccessorOptionId {
    @Accessor("id") ResourceLocation voxy$getId();
}
