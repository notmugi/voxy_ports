package me.cortex.voxy.client.core.rendering.util;

import java.util.function.IntBinaryOperator;

/** MC 1.21.1 light texture sampling. */
public final class LightmapSampling {
    private LightmapSampling() {}

    // Match Sodium texture sampling after NT maps the light.
    public static int sample(IntBinaryOperator pixels, int block, int sky) {
        int x = Math.clamp(block, 0, 15);
        int y = Math.clamp(sky, 0, 15);
        int x0 = Math.max(0, x - 1);
        int y0 = Math.max(0, y - 1);
        int a = pixels.applyAsInt(x0, y0);
        int b = pixels.applyAsInt(x, y0);
        int c = pixels.applyAsInt(x0, y);
        int d = pixels.applyAsInt(x, y);
        int result = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int sum = ((a >>> shift) & 255) + ((b >>> shift) & 255)
                    + ((c >>> shift) & 255) + ((d >>> shift) & 255);
            result |= ((sum + 2) / 4) << shift;
        }
        return result;
    }
}
