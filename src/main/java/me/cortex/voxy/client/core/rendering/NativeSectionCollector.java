package me.cortex.voxy.client.core.rendering;

import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.minecraft.core.SectionPos;

import java.util.function.LongConsumer;

final class NativeSectionCollector {
    private NativeSectionCollector() {}

    static void collect(ChunkRenderListIterable lists, boolean mineInAbyss, LongConsumer output) {
        var regions = lists.iterator();
        while (regions.hasNext()) {
            var list = regions.next();
            var region = list.getRegion();
            if (region.getResources() == null) continue;
            var sections = list.sectionsWithGeometryIterator(false);
            if (sections == null) continue;
            while (sections.hasNext()) {
                int index = sections.nextByteAsInt();
                int x = region.getChunkX() + LocalSectionIndex.unpackX(index);
                int y = region.getChunkY() + LocalSectionIndex.unpackY(index);
                int z = region.getChunkZ() + LocalSectionIndex.unpackZ(index);
                if (mineInAbyss) {
                    int sector = (x + 512) >> 10;
                    x -= sector << 10;
                    y += 16 + (256 - 32 - sector * 30);
                }
                output.accept(SectionPos.asLong(x, y, z));
            }
        }
    }
}
