package me.cortex.voxy.client.fog;

import me.cortex.voxy.client.config.VoxyConfig;

public final class VoxyCompat {
    private VoxyCompat() {}

    public static State getState() {
        var config = VoxyConfig.CONFIG;
        return new State(true, config.enabled, config.enableRendering, config.renderVanillaFog, config.sectionRenderDistance);
    }

    public static final class State {
        final boolean present;
        final boolean enabled;
        final boolean renderingEnabled;
        final boolean environmentalFogEnabled;
        final float sectionRenderDistance;

        State(boolean present, boolean enabled, boolean renderingEnabled, boolean environmentalFogEnabled, float sectionRenderDistance) {
            this.present = present;
            this.enabled = enabled;
            this.renderingEnabled = renderingEnabled;
            this.environmentalFogEnabled = environmentalFogEnabled;
            this.sectionRenderDistance = sectionRenderDistance;
        }

        static State unavailable() {
            return new State(false, false, false, false, 0.0f);
        }

        public boolean isActive() {
            return VoxyFogConfig.isEnabled() && this.present && this.enabled && this.renderingEnabled;
        }

        public float renderDistanceBlocks() {
            return this.sectionRenderDistance * 32.0f * 16.0f;
        }
    }
}
