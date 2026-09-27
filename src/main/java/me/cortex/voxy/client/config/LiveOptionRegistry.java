package me.cortex.voxy.client.config;

import net.minecraft.resources.ResourceLocation;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Whitelisted sliders apply immediately. */
public final class LiveOptionRegistry {
    private record Slider(int min, int max, int step, IntSupplier get, IntConsumer set) {
        void update(int value) {
            int clamped = Math.clamp(value, min, max);
            int snapped = min + Math.round((clamped - min) / (float) step) * step;
            set.accept(Math.clamp(snapped, min, max));
        }
    }
    private static final Map<ResourceLocation, Slider> SLIDERS = new HashMap<>();
    private LiveOptionRegistry() {}
    public static void register(String id, int min, int max, int step, IntSupplier get, IntConsumer set) {
        if (step <= 0 || max < min) throw new IllegalArgumentException("Invalid live slider range");
        SLIDERS.put(ResourceLocation.parse(id), new Slider(min, max, step, get, set));
    }
    public static Integer get(ResourceLocation id) {
        var slider = SLIDERS.get(id);
        return slider == null ? null : slider.get.getAsInt();
    }
    public static boolean update(ResourceLocation id, Object value) {
        var slider = SLIDERS.get(id);
        if (slider == null || !(value instanceof Integer integer)) return false;
        slider.update(integer);
        return true;
    }
}
