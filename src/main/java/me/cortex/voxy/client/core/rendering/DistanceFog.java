package me.cortex.voxy.client.core.rendering;

import java.util.function.Supplier;

/** Optional addon bridge. Distance fog is separate from vanilla/environmental fog state. */
public final class DistanceFog {
    private static Supplier<float[]> provider;

    private DistanceFog() {}

    /** Provider returns {start, end} in blocks, or null when disabled. Render-thread only. */
    public static void setProvider(Supplier<float[]> provider) {
        DistanceFog.provider = provider;
    }

    public static float[] getRange() {
        float[] range = provider == null ? null : provider.get();
        if (range == null || range.length != 2 || !Float.isFinite(range[0]) ||
                !Float.isFinite(range[1]) || range[0] < 0 || range[1] <= range[0]) return null;
        return range;
    }
}
