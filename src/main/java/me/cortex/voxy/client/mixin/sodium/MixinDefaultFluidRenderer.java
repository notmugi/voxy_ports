package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = DefaultFluidRenderer.class, remap = false)
public abstract class MixinDefaultFluidRenderer {
    @Unique
    private FluidState voxy$fluidState;

    @Inject(method = "render", at = @At("HEAD"))
    private void voxy$captureFluid(LevelSlice level, BlockState blockState, FluidState fluidState, BlockPos blockPos, BlockPos offset, TranslucentGeometryCollector collector, ChunkModelBuilder meshBuilder, Material material, ColorProvider<FluidState> colorProvider, TextureAtlasSprite[] sprites, CallbackInfo ci) {
        this.voxy$fluidState = fluidState;
    }

    //Cull the side wall of a fluid at the edge of loaded chunks when the LOD data has the same fluid
    // continuing past it, otherwise the wall z-fights with the LOD fluid's side face at the boundary
    @Inject(method = "isSideExposed", at = @At("HEAD"), cancellable = true)
    private void voxy$cullLodContinuation(BlockAndTintGetter world, int x, int y, int z, Direction dir, float height, CallbackInfoReturnable<Boolean> cir) {
        if (dir.getStepY() != 0 || this.voxy$fluidState == null) return;
        var rs = IGetVoxyRenderSystem.getNullable();
        if (rs == null) return;
        var engine = rs.getEngine();
        if (engine == null) return;
        int nx = x + dir.getStepX();
        int nz = z + dir.getStepZ();
        var section = engine.acquireIfExists(0, nx >> 5, y >> 5, nz >> 5);
        if (section == null) return;
        long raw;
        try {
            raw = section._unsafeGetRawDataArray()[WorldSection.getIndex(nx & 31, y & 31, nz & 31)];
        } finally {
            section.release();
        }
        int blockId = Mapper.getBlockId(raw);
        if (blockId == 0) return;
        if (engine.getMapper().getBlockStateFromBlockId(blockId).getFluidState().getType() == this.voxy$fluidState.getType()) {
            cir.setReturnValue(false);
        }
    }
}
