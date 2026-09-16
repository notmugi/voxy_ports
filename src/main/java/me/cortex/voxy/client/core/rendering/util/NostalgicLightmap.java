package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;

import java.lang.reflect.Method;
import net.minecraft.client.multiplayer.ClientLevel;

import static org.lwjgl.opengl.GL45C.*;

/** Apply NT's current discrete light step to raw stored skylight, including unloaded LODs. */
public final class NostalgicLightmap {
    private Object roundRobin;
    private Method getEnabled;
    private Method combinedLight;
    private Method tickLighting;
    private static java.lang.ref.WeakReference<ClientLevel> synchronizedLevel = new java.lang.ref.WeakReference<>(null);
    private static long joinRevision;
    private ClientLevel level;
    private long seenJoinRevision = -1;
    private boolean wasEnabled;
    private GlTexture texture;

    /** The first actual server time packet, not the world's temporary default time. */
    public static void onServerTime() {
        var level = Minecraft.getInstance().level;
        if (level != null && synchronizedLevel.get() != level) {
            synchronizedLevel = new java.lang.ref.WeakReference<>(level);
            joinRevision++;
        }
    }

    private int lastFrame = Integer.MIN_VALUE;
    private int currentTexture;

    public NostalgicLightmap() {
        if (!FabricLoader.getInstance().isModLoaded("nostalgic_tweaks")) return;
        try {
            var config = Class.forName("mod.adrenix.nostalgic.tweak.config.CandyTweak");
            this.roundRobin = config.getField("ROUND_ROBIN_RELIGHT").get(null);
            this.getEnabled = this.roundRobin.getClass().getMethod("get");
            this.combinedLight = Class.forName("mod.adrenix.nostalgic.helper.candy.light.LightingHelper")
                    .getMethod("getCombinedLight", int.class, int.class);
            var helper = this.combinedLight.getDeclaringClass();
            this.tickLighting = helper.getMethod("onTick");
        } catch (ReflectiveOperationException e) {
            this.roundRobin = null;
            Logger.error("NT round-robin lightmap compatibility unavailable", e);
        }
    }

    public int getTexture(int frame) {
        if (frame == this.lastFrame) return this.currentTexture;
        this.lastFrame = frame;
        this.currentTexture = LightMapHelper.getLightmapTextureId();
        if (this.roundRobin == null) return this.currentTexture;
        try {
            if (!Boolean.TRUE.equals(this.getEnabled.invoke(this.roundRobin))) {
                this.wasEnabled = false;
                return this.currentTexture;
            }
            var currentLevel = Minecraft.getInstance().level;
            if (currentLevel == null) return this.currentTexture;
            if (this.level != currentLevel || this.seenJoinRevision != joinRevision || !this.wasEnabled) {
                this.level = currentLevel;
                this.seenJoinRevision = joinRevision;
                this.wasEnabled = true;
                // Refresh NT's -1/old-world caches without clearing its relight queues.
                this.tickLighting.invoke(null);
            }
            int[] mapping = new int[256];
            for (int sky = 0; sky < 16; sky++) {
                for (int block = 0; block < 16; block++) {
                    mapping[sky * 16 + block] = Math.clamp((int) this.combinedLight.invoke(null, sky, block), 0, 15);
                }
            }
            // Apply the current NT step immediately, independent of terrain rebuild work.
            var pixels = Minecraft.getInstance().gameRenderer.lightTexture().lightTexture.getPixels();
            if (pixels == null) return this.currentTexture;
            if (this.texture == null) {
                this.texture = new GlTexture().store(GL_RGBA8, 1, 16, 16);
                glTextureParameteri(this.texture.id, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTextureParameteri(this.texture.id, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            }
            try (var stack = MemoryStack.stackPush()) {
                var data = stack.mallocInt(256);
                for (int sky = 0; sky < 16; sky++) {
                    for (int block = 0; block < 16; block++) {
                        int mapped = mapping[sky * 16 + block];
                        data.put(pixels.getPixelRGBA(block, Math.clamp(mapped, 0, 15)));
                    }
                }
                data.flip();
                int unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
                int rowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
                int skipRows = glGetInteger(GL_UNPACK_SKIP_ROWS);
                int skipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
                glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
                glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
                glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
                glTextureSubImage2D(this.texture.id, 0, 0, 0, 16, 16, GL_RGBA, GL_UNSIGNED_BYTE, data);
                glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
                glPixelStorei(GL_UNPACK_SKIP_ROWS, skipRows);
                glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipPixels);
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            }
            this.currentTexture = this.texture.id;
        } catch (ReflectiveOperationException e) {
            this.roundRobin = null;
            Logger.error("NT round-robin lightmap compatibility failed", e);
        }
        return this.currentTexture;
    }

    public void free() {
        if (this.texture != null) this.texture.free();
    }
}
