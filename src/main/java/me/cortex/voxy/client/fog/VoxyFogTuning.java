package me.cortex.voxy.client.fog;

public final class VoxyFogTuning {
    private VoxyFogTuning() {}

    public static FogRange compute(float voxyDistanceBlocks, float vanillaDistanceBlocks) {
        if (!Float.isFinite(voxyDistanceBlocks) || voxyDistanceBlocks <= 0) return null;
        float start = voxyDistanceBlocks * VoxyFogConfig.getFogMin();
        float end = voxyDistanceBlocks * VoxyFogConfig.getFogMax();
        // Keep a small band for equal endpoints.
        if (end - start < 2.0f) {
            end = Math.max(end, Math.min(2.0f, voxyDistanceBlocks));
            start = Math.max(0, end - 2.0f);
        }
        return new FogRange(start, end);
    }

    public static FogRange getActiveRange() {
        var state = VoxyCompat.getState();
        if (!state.isActive()) return null;
        return compute(state.renderDistanceBlocks(), net.minecraft.client.Minecraft.getInstance().gameRenderer.getRenderDistance());
    }

    public record FogRange(float start, float end) {
    }
}
