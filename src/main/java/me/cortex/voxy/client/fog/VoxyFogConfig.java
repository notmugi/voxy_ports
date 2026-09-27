package me.cortex.voxy.client.fog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class VoxyFogConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("voxy-renderdistance-fog.json");

    public static final boolean DEFAULT_ENABLED = true;
    public static final float DEFAULT_FOG_MIN = 0.1f;
    public static final float DEFAULT_FOG_MAX = 0.9f;

    private static State state = new State();

    private VoxyFogConfig() {
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            State loaded = GSON.fromJson(reader, State.class);
            if (loaded != null) {
                state = loaded;
            }
        } catch (IOException | com.google.gson.JsonParseException ignored) {
        }
        state.fogMin = clamp(state.fogMin, DEFAULT_FOG_MIN);
        state.fogMax = clamp(state.fogMax, DEFAULT_FOG_MAX);
        if (state.fogMin > state.fogMax) {
            float swap = state.fogMin;
            state.fogMin = state.fogMax;
            state.fogMax = swap;
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(state, writer);
            }
        } catch (IOException ignored) {
        }
    }

    public static boolean isEnabled() {
        return state.enabled;
    }

    public static void setEnabled(boolean enabled) {
        state.enabled = enabled;
        save();
    }

    private static float clamp(float value, float fallback) {
        return Float.isFinite(value) ? Math.clamp(value, 0.0f, 1.0f) : fallback;
    }

    public static float getFogMin() { return state.fogMin; }
    public static float getFogMax() { return state.fogMax; }

    public static void setFogMin(double value) {
        state.fogMin = clamp((float) value, DEFAULT_FOG_MIN);
        state.fogMax = Math.max(state.fogMax, state.fogMin);
        save();
    }

    public static void setFogMax(double value) {
        state.fogMax = clamp((float) value, DEFAULT_FOG_MAX);
        state.fogMin = Math.min(state.fogMin, state.fogMax);
        save();
    }

    private static final class State {
        private boolean enabled = DEFAULT_ENABLED;
        private float fogMin = DEFAULT_FOG_MIN;
        private float fogMax = DEFAULT_FOG_MAX;
    }
}
