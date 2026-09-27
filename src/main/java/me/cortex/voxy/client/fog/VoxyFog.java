package me.cortex.voxy.client.fog;

import me.cortex.voxy.client.core.rendering.DistanceFog;

/** Fog config and LOD bridge. */
public final class VoxyFog {
    private VoxyFog() {}

    public static void init() {
        VoxyFogConfig.load();
        DistanceFog.setProvider(() -> {
            var range = VoxyFogTuning.getActiveRange();
            return range == null ? null : new float[]{range.start(), range.end()};
        });
    }
}
