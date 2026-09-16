package me.cortex.voxy.client.config;

import net.caffeinemc.mods.sodium.client.gui.options.Option;
import net.caffeinemc.mods.sodium.client.gui.options.OptionFlag;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpact;
import net.caffeinemc.mods.sodium.client.gui.options.control.Control;
import net.caffeinemc.mods.sodium.client.gui.options.control.SliderControl;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** No staged copy: paired fade controls reflect one another immediately, like VoxyFog. */
final class LiveCloudSlider implements Option<Integer> {
    private final String key;
    private final int min, max, step;
    private final IntSupplier getter;
    private final IntConsumer setter;
    private final VoxyConfig storage;
    private final Control<Integer> control;

    LiveCloudSlider(String key, int min, int max, int step, boolean percent,
                    IntSupplier getter, IntConsumer setter, VoxyConfig storage) {
        this.key = "voxy.config.clouds." + key;
        this.min = min;
        this.max = max;
        this.step = step;
        this.getter = getter;
        this.setter = setter;
        this.storage = storage;
        this.control = new SliderControl(this, min, max, step,
                v -> Component.literal(v + (percent ? "%" : " blocks")));
    }

    public Component getName() { return Component.translatable(this.key); }
    public Component getTooltip() { return Component.translatable(this.key + ".tooltip"); }
    public OptionImpact getImpact() { return null; }
    public Control<Integer> getControl() { return this.control; }
    public Integer getValue() { return this.getter.getAsInt(); }
    public void setValue(Integer value) {
        int clamped = Math.clamp(value, this.min, this.max);
        int snapped = this.min + Math.round((clamped - this.min) / (float) this.step) * this.step;
        this.setter.accept(Math.clamp(snapped, this.min, this.max));
        this.storage.save();
    }
    public void reset() {} // Already applied and persisted, not affected by Undo.
    public OptionStorage<?> getStorage() { return this.storage; }
    public boolean isAvailable() { return true; }
    public boolean hasChanged() { return false; }
    public void applyChanges() {}
    public Collection<OptionFlag> getFlags() { return List.of(); }
}
