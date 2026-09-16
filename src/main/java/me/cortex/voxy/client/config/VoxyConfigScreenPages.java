package me.cortex.voxy.client.config;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.gui.options.*;
import net.caffeinemc.mods.sodium.client.gui.options.control.SliderControl;
import net.caffeinemc.mods.sodium.client.gui.options.control.TickBoxControl;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

public abstract class VoxyConfigScreenPages {
    private VoxyConfigScreenPages(){}

    public static OptionPage voxyOptionPage = null;

    public static OptionPage page() {
        List<OptionGroup> groups = new ArrayList<>();
        VoxyConfig storage = VoxyConfig.CONFIG;

        //General
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.enabled"))
                        .setTooltip(Component.translatable("voxy.config.general.enabled.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, v)->{
                            s.enabled = v;
                            if (v) {
                                if (VoxyClientInstance.isInGame) {
                                    VoxyCommon.createInstance();
                                    var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                    if (vrsh != null && s.enableRendering) {
                                        vrsh.createRenderer();
                                    }
                                }
                            } else {
                                var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                if (vrsh != null) {
                                    vrsh.shutdownRenderer();
                                }
                                VoxyCommon.shutdownInstance();
                            }
                        }, s -> s.enabled)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                ).build()
        );

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.serviceThreads"))
                        .setTooltip(Component.translatable("voxy.config.general.serviceThreads.tooltip"))
                        .setControl(opt->new SliderControl(opt, 1,
                                CpuLayout.CORES.length, //Just do core size as max
                                //Runtime.getRuntime().availableProcessors(),//Note: this is threads not cores, the default value is half the core count, is fine as this should technically be the limit but CpuLayout.CORES.length is more realistic
                                1, v->Component.literal(Integer.toString(v))))
                        .setBinding((s, v)->{
                            s.serviceThreads = v;
                            var instance = VoxyCommon.getInstance();
                            if (instance != null) {
                                instance.updateDedicatedThreads();
                            }
                        }, s -> s.serviceThreads)
                        .setImpact(OptionImpact.HIGH)
                        .build()
                ).add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.useSodiumBuilder"))
                        .setTooltip(Component.translatable("voxy.config.general.useSodiumBuilder.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.VARIES)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setBinding((s,v)->{
                            s.dontUseSodiumBuilderThreads = !v;
                            var instance = VoxyCommon.getInstance();
                            if (instance != null) {
                                instance.updateDedicatedThreads();
                            }
                        }, s->!s.dontUseSodiumBuilderThreads)
                        .build()
                ).add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.ingest"))
                        .setTooltip(Component.translatable("voxy.config.general.ingest.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, v) -> s.ingestEnabled = v, s -> s.ingestEnabled)
                        .setImpact(OptionImpact.MEDIUM)
                        .build()
                ).build()
        );

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.rendering"))
                        .setTooltip(Component.translatable("voxy.config.general.rendering.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, v)->{
                            s.enableRendering = v;
                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                            if (vrsh != null) {
                                if (v) {
                                    vrsh.createRenderer();
                                } else {
                                    vrsh.shutdownRenderer();
                                }
                            }
                        }, s -> s.enableRendering)
                        .setImpact(OptionImpact.HIGH)
                        .build()
                ).add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.subDivisionSize"))
                        .setTooltip(Component.translatable("voxy.config.general.subDivisionSize.tooltip"))
                        .setControl(opt->new SliderControl(opt, 0, SUBDIV_IN_MAX, 1, v->Component.literal(Integer.toString(Math.round(ln2subDiv(v))))))
                        .setBinding((s, v) -> s.subDivisionSize = ln2subDiv(v), s -> subDiv2ln(s.subDivisionSize))
                        .setImpact(OptionImpact.HIGH)
                        .build()
                ).add(OptionImpl.createBuilder(int.class, storage)
                        .setName(Component.translatable("voxy.config.general.renderDistance"))
                        .setTooltip(Component.translatable("voxy.config.general.renderDistance.tooltip"))
                        .setControl(opt->new SliderControl(opt, 2, 64, 1, v->Component.literal(Integer.toString(v * 32))))//Every unit is equal to 32 vanilla chunks
                        .setBinding((s, v)-> {
                            s.sectionRenderDistance = v;
                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                            if (vrsh != null) {
                                var vrs = vrsh.getVoxyRenderSystem();
                                if (vrs != null) {
                                    vrs.setRenderDistance(v);
                                }
                            }
                        }, s -> s.sectionRenderDistance)
                        .setImpact(OptionImpact.LOW)
                        .build()
                ).add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.render_fog"))
                        .setTooltip(Component.translatable("voxy.config.general.render_fog.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, v)-> s.renderVanillaFog = v, s -> s.renderVanillaFog)
                        .build()
                ).add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.massive_clouds"))
                        .setTooltip(Component.translatable("voxy.config.general.massive_clouds.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, v) -> s.massiveClouds = v, s -> s.massiveClouds)
                        .setImpact(OptionImpact.MEDIUM)
                        .build()
                ).add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable("voxy.config.general.render_statistics"))
                        .setTooltip(Component.translatable("voxy.config.general.render_statistics.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((s, v)-> RenderStatistics.enabled = v, s -> RenderStatistics.enabled)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                ).build()
        );
        groups.addAll(cloudGroups());
        // The fog addon is optional. Build its controls into this page directly so
        // constructor-mixin ordering cannot create duplicate tabs or stale page identities.
        try {
            var fogGroups = (List<?>) Class.forName("dev.not_tim.voxyfog.SodiumFogOptions")
                    .getMethod("groups").invoke(null);
            for (Object group : fogGroups) {
                groups.add(OptionGroup.class.cast(group));
            }
        } catch (ClassNotFoundException ignored) {
            // VoxyFog is not installed.
        } catch (ReflectiveOperationException | ClassCastException e) {
            me.cortex.voxy.common.Logger.error("Failed to add VoxyFog controls to Voxy options", e);
        }
        return new OptionPage(Component.translatable("voxy.config.title"), ImmutableList.copyOf(groups));
    }

    /** Cloud controls share the main Voxy page. Sliders save immediately. */
    private static List<OptionGroup> cloudGroups() {
        VoxyConfig s = VoxyConfig.CONFIG;
        return ImmutableList.of(
                OptionGroup.createBuilder()
                        .add(new LiveCloudSlider("height", -64, 4096, 16, false,
                                () -> s.cloudHeight, v -> s.cloudHeight = v, s))
                        .add(new LiveCloudSlider("cell_size", 48, 384, 12, false,
                                () -> s.cloudCellSize, v -> s.cloudCellSize = v, s))
                        .add(new LiveCloudSlider("thickness", 4, 256, 4, false,
                                () -> s.cloudThickness, v -> s.cloudThickness = v, s))
                        .add(new LiveCloudSlider("speed", 0, 1000, 10, true,
                                () -> s.cloudSpeed, v -> s.cloudSpeed = v, s))
                        .build(),
                OptionGroup.createBuilder()
                        .add(new LiveCloudSlider("fade_start", 0, 98, 1, true,
                                () -> s.cloudFadeStart, s::setCloudFadeStart, s))
                        .add(new LiveCloudSlider("fade_end", 1, 99, 1, true,
                                () -> s.cloudFadeEnd, s::setCloudFadeEnd, s))
                        .build());
    }

    private static final int SUBDIV_IN_MAX = 100;
    private static final double SUBDIV_MIN = 28;
    private static final double SUBDIV_MAX = 256;
    private static final double SUBDIV_CONST = Math.log(SUBDIV_MAX/SUBDIV_MIN)/Math.log(2);


    //In range is 0->200
    //Out range is 28->256
    private static float ln2subDiv(int in) {
        return (float) (SUBDIV_MIN*Math.pow(2, SUBDIV_CONST*((double)in/SUBDIV_IN_MAX)));
    }

    //In range is ... any?
    //Out range is 0->200
    private static int subDiv2ln(float in) {
        return (int) (((Math.log(((double)in)/SUBDIV_MIN)/Math.log(2))/SUBDIV_CONST)*SUBDIV_IN_MAX);
    }

}
