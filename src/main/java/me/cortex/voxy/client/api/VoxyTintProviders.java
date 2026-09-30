package me.cortex.voxy.client.api;

import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

public final class VoxyTintProviders {
    public static final int KIND_GRASS = 0;
    public static final int KIND_FOLIAGE = 1;
    public static final int KIND_OTHER = 2;

    public interface TintProvider {
        int keyCount();
        int key(int blockX, int blockZ);
        int tint(int kind, int key);
    }

    private static final TintProvider PROVIDER = load();

    private static TintProvider load() {
        try {
            var entrypoints = FabricLoader.getInstance().getEntrypoints("voxy:tint_provider", Object.class);
            if (entrypoints.isEmpty()) return null;
            Object entrypoint = entrypoints.get(0);
            Method keyCount = entrypoint.getClass().getMethod("keyCount");
            Method key = entrypoint.getClass().getMethod("key", int.class, int.class);
            Method tint = entrypoint.getClass().getMethod("tint", int.class, int.class);
            int count = (int) keyCount.invoke(null);
            if (count <= 0 || count > 512) {
                Logger.error("Tint provider " + entrypoint.getClass().getName() + " has invalid key count " + count);
                return null;
            }
            Logger.info("Using tint provider " + entrypoint.getClass().getName() + " with " + count + " keys");
            return new TintProvider() {
                @Override public int keyCount() { return count; }
                @Override public int key(int blockX, int blockZ) {
                    try { return (int) key.invoke(null, blockX, blockZ); } catch (Throwable t) { throw new RuntimeException(t); }
                }
                @Override public int tint(int kind, int keyIndex) {
                    try { return (int) tint.invoke(null, kind, keyIndex); } catch (Throwable t) { throw new RuntimeException(t); }
                }
            };
        } catch (Throwable t) {
            Logger.error("Failed to load voxy tint provider", t);
            return null;
        }
    }

    public static @Nullable TintProvider get() {
        return PROVIDER;
    }

    private VoxyTintProviders() {}
}
