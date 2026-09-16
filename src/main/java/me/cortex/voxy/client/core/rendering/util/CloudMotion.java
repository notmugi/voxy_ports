package me.cortex.voxy.client.core.rendering.util;

/** Tick-based, double-precision cloud phase. Live speed edits never rephase the texture. */
public final class CloudMotion {
    private boolean initialized;
    private long lastTick;
    private float lastPartial;
    private double speed;
    private double offset;

    public double sample(long tick, float partial, int percent, double period) {
        double nextSpeed = 0.03 * Math.clamp(percent, 0, 1000) / 100.0;
        if (!this.initialized) {
            // Match the original phase at default speed, without dividing by zero at stop.
            this.offset = nextSpeed == 0 ? 0 :
                    ((tick % (long) (period / nextSpeed)) + partial) * nextSpeed;
            this.initialized = true;
        } else {
            double elapsed = (double) (tick - this.lastTick) + partial - this.lastPartial;
            // A server clock reset must not run clouds backwards or change their phase.
            if (elapsed > 0) this.offset += elapsed * this.speed;
        }
        this.offset -= Math.floor(this.offset / period) * period;
        this.lastTick = tick;
        this.lastPartial = partial;
        this.speed = nextSpeed;
        return this.offset;
    }
}
