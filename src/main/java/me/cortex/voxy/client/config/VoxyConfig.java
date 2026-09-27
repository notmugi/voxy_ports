package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public int sectionRenderDistance = 4; // 128 chunks
    public int serviceThreads = 2;
    public float subDivisionSize = 63.497753f;
    public boolean renderVanillaFog = false;
    public boolean massiveClouds = true;
    public int cloudHeight = 400;
    public int cloudCellSize = 72;
    public int cloudThickness = 36;
    public int cloudSpeed = 500; // Percent of the original 0.03 blocks/tick.
    public int cloudFadeStart = 10;
    public int cloudFadeEnd = 90;
    public boolean renderStatistics = false;
    public boolean dontUseSodiumBuilderThreads = false;

    private static VoxyConfig loadOrCreate() {
        if (VoxyCommon.isAvailable()) {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, VoxyConfig.class);
                    if (conf != null) {
                        conf.save();
                        return conf;
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (IOException e) {
                    Logger.error("Could not parse config", e);
                }
            }
            Logger.info("Config doesnt exist, creating new");
            var config = new VoxyConfig();
            config.save();
            return config;
        } else {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }
    }

    /** Also validates hand-edited files. A >=1% fade band avoids undefined smoothstep. */
    public void sanitizeCloudSettings() {
        cloudHeight = Math.clamp(cloudHeight, -64, 4096);
        // At max RD and 99% fade end, 48-block cells cross <960 cells per ray.
        cloudCellSize = Math.clamp(cloudCellSize, 48, 384);
        cloudThickness = Math.clamp(cloudThickness, 4, 256);
        cloudSpeed = Math.clamp(cloudSpeed, 0, 1000);
        cloudFadeEnd = Math.clamp(cloudFadeEnd, 1, 99);
        cloudFadeStart = Math.clamp(cloudFadeStart, 0, cloudFadeEnd - 1);
    }

    public void setCloudFadeStart(int value) {
        cloudFadeStart = Math.clamp(value, 0, 98);
        cloudFadeEnd = Math.max(cloudFadeEnd, cloudFadeStart + 1);
    }

    public void setCloudFadeEnd(int value) {
        cloudFadeEnd = Math.clamp(value, 1, 99);
        cloudFadeStart = Math.min(cloudFadeStart, cloudFadeEnd - 1);
    }

    public void save() {
        sanitizeCloudSettings();
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            return;
        }

        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("voxy-config.json");
    }

    public VoxyConfig getData() {
        return this;
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
