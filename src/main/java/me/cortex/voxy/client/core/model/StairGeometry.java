package me.cortex.voxy.client.core.model;

import java.util.function.LongConsumer;

/** Half-block stair surfaces using the existing quad format and projected textures. */
public final class StairGeometry {
    private StairGeometry() {}

    /** Retile a merged face at half-block resolution; merge coplanar patches. */
    public static void expandQuad(long quad, int occupancy, LongConsumer output) {
        int face = (int) (quad & 7);
        int axis = face >> 1; // Y, Z, X
        int axisBit = axis == 0 ? 1 : axis == 1 ? 2 : 0;
        int uBit = axis == 2 ? 1 : 0;
        int vBit = axis == 1 ? 1 : 2;
        int uShift = axis == 2 ? 16 : 21;
        int vShift = axis == 1 ? 16 : 11;
        int width = ((int) ((quad >>> 3) & 15) + 1) * 2;
        int height = ((int) ((quad >>> 7) & 15) + 1) * 2;
        long base = quad & ~((255L << 3) | (15L << 42));
        for (int inset = 0; inset < 2; inset++) {
            int mask = 0;
            for (int cell = 0; cell < 8; cell++) {
                if ((occupancy & (1 << cell)) == 0) continue;
                boolean boundary = ((cell >> axisBit) & 1) == (face & 1);
                if (boundary != (inset == 0)) continue;
                if (!boundary && (occupancy & (1 << (cell ^ (1 << axisBit)))) != 0) continue;
                mask |= 1 << (((cell >> uBit) & 1) | (((cell >> vBit) & 1) << 1));
            }
            if (mask == 0) continue;
            // At most 32x32 half-cells. Greedy rectangles retain exact geometry
            // without multiplying long runs of stairs into per-octant draw calls.
            int[] rows = new int[height];
            for (int v = 0; v < height; v++) for (int u = 0; u < width; u++) {
                if ((mask & (1 << ((u & 1) | ((v & 1) << 1)))) != 0) rows[v] |= 1 << u;
            }
            for (int v = 0; v < height; v++) while (rows[v] != 0) {
                int u = Integer.numberOfTrailingZeros(rows[v]);
                int w = 1;
                while (w < 16 && u + w < width && (rows[v] & (1 << (u + w))) != 0) w++;
                int bits = ((1 << w) - 1) << u;
                int h = 1;
                while (h < 16 && v + h < height && (rows[v + h] & bits) == bits) h++;
                for (int j = 0; j < h; j++) rows[v + j] &= ~bits;
                int patch = 8 | (u & 1) | ((v & 1) << 1) | (inset << 2);
                long position = base + ((long) (u >> 1) << uShift) + ((long) (v >> 1) << vShift);
                output.accept(position | ((long) patch << 42) | ((long) (w - 1) << 3) | ((long) (h - 1) << 7));
            }
        }
    }
}
