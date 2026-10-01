package me.cortex.voxy.client.config;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.function.*;

/** Sodium 0.8 config page. */
public final class VoxyConfigMenu implements ConfigEntryPoint {
    private static Component text(String key) { return Component.translatable(key); }
    private static ResourceLocation id(String key) { return ResourceLocation.parse("voxy:"+key); }
    private static BooleanOptionBuilder bool(ConfigBuilder b, String key, String label, boolean defaultValue, BooleanSupplier get, Consumer<Boolean> set) {
        return b.createBooleanOption(id(key)).setName(text(label)).setTooltip(text(label+".tooltip"))
                .setBinding(set, get::getAsBoolean).setDefaultValue(defaultValue).setStorageHandler(VoxyConfig.CONFIG::save);
    }
    private static IntegerOptionBuilder integer(ConfigBuilder b, String key, String label, int min, int max, int step,
            int defaultValue, IntSupplier get, IntConsumer set, IntFunction<Component> format, boolean live) {
        if (live) LiveOptionRegistry.register("voxy:"+key, min,max,step,get,v->{set.accept(v);VoxyConfig.CONFIG.save();});
        return b.createIntegerOption(id(key)).setName(text(label)).setTooltip(text(label+".tooltip"))
                .setRange(new Range(min,max,step)).setValueFormatter(format::apply).setBinding(set::accept,get::getAsInt)
                .setDefaultValue(defaultValue).setStorageHandler(VoxyConfig.CONFIG::save);
    }
    @Override public void registerConfigLate(ConfigBuilder b) {
        if (!VoxyCommon.isAvailable()) return;
        var c=VoxyConfig.CONFIG;
        var d=new VoxyConfig();
        var mod=b.registerModOptions("voxy","Voxy",VoxyCommon.MOD_VERSION).setIcon(id("icon.png"));
        var page=b.createOptionPage().setName(text("voxy.config.title"));
        var general=b.createOptionGroup();
        general.addOption(bool(b,"enabled","voxy.config.general.enabled",d.enabled,()->c.enabled,v->{
            c.enabled=v;
            if (v && VoxyClientInstance.isInGame) VoxyCommon.createInstance();
            if (!v) {
                var renderer=(IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                if(renderer!=null) renderer.shutdownRenderer();
                VoxyCommon.shutdownInstance();
            }
        }).setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));
        general.addOption(integer(b,"threads","voxy.config.general.serviceThreads",1,Math.max(10,CpuLayout.CORES.length),1,
                d.serviceThreads, ()->c.serviceThreads,v->{c.serviceThreads=v;var instance=VoxyCommon.getInstance();if(instance!=null)instance.updateDedicatedThreads();},v->Component.literal(""+v),false));
        general.addOption(bool(b,"sodium_threads","voxy.config.general.useSodiumBuilder",!d.dontUseSodiumBuilderThreads,()->!c.dontUseSodiumBuilderThreads,v->c.dontUseSodiumBuilderThreads=!v).setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));
        general.addOption(bool(b,"ingest","voxy.config.general.ingest",d.ingestEnabled,()->c.ingestEnabled,v->c.ingestEnabled=v));
        var rendering=b.createOptionGroup();
        rendering.addOption(bool(b,"rendering","voxy.config.general.rendering",d.enableRendering,()->c.enableRendering,v->c.enableRendering=v).setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));
        rendering.addOption(integer(b,"subdivision","voxy.config.general.subDivisionSize",0,100,1,
                (int)(Math.log(d.subDivisionSize/28.0)/Math.log(256.0/28)*100), ()->(int)(Math.log(c.subDivisionSize/28.0)/Math.log(256.0/28)*100),
                v->c.subDivisionSize=(float)(28*Math.pow(256.0/28,v/100.0)),v->Component.literal(""+Math.round(28*Math.pow(256.0/28,v/100.0))),false));
        rendering.addOption(integer(b,"distance","voxy.config.general.renderDistance",2,64,1,d.sectionRenderDistance,()->c.sectionRenderDistance,v->{
            c.sectionRenderDistance=v;var renderer=IGetVoxyRenderSystem.getNullable();if(renderer!=null)renderer.setRenderDistance(v);
        },v->Component.literal(""+(v*32)),false));
        rendering.addOption(bool(b,"vanilla_fog","voxy.config.general.render_fog",d.renderVanillaFog,()->c.renderVanillaFog,v->c.renderVanillaFog=v));
        rendering.addOption(bool(b,"border_fade","voxy.config.general.border_fade",d.borderFade,()->c.borderFade,v->c.borderFade=v));
        rendering.addOption(bool(b,"clouds","voxy.config.general.massive_clouds",d.massiveClouds,()->c.massiveClouds,v->c.massiveClouds=v));
        rendering.addOption(bool(b,"statistics","voxy.config.general.render_statistics",d.renderStatistics,()->RenderStatistics.enabled,v->RenderStatistics.enabled=v).setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD));
        var clouds=b.createOptionGroup();
        clouds.addOption(integer(b,"cloud_height","voxy.config.clouds.height",-64,4096,16,d.cloudHeight,()->c.cloudHeight,v->c.cloudHeight=v,v->Component.literal(v+" blocks"),true));
        clouds.addOption(integer(b,"cloud_cell","voxy.config.clouds.cell_size",48,384,12,d.cloudCellSize,()->c.cloudCellSize,v->c.cloudCellSize=v,v->Component.literal(v+" blocks"),true));
        clouds.addOption(integer(b,"cloud_thickness","voxy.config.clouds.thickness",4,256,4,d.cloudThickness,()->c.cloudThickness,v->c.cloudThickness=v,v->Component.literal(v+" blocks"),true));
        clouds.addOption(integer(b,"cloud_speed","voxy.config.clouds.speed",0,1000,10,d.cloudSpeed,()->c.cloudSpeed,v->c.cloudSpeed=v,v->Component.literal(v+"%"),true));
        clouds.addOption(integer(b,"cloud_fade_start","voxy.config.clouds.fade_start",0,98,1,d.cloudFadeStart,()->c.cloudFadeStart,c::setCloudFadeStart,v->Component.literal(v+"%"),true));
        clouds.addOption(integer(b,"cloud_fade_end","voxy.config.clouds.fade_end",1,99,1,d.cloudFadeEnd,()->c.cloudFadeEnd,c::setCloudFadeEnd,v->Component.literal(v+"%"),true));
        page.addOptionGroup(general);
        page.addOptionGroup(rendering);
        page.addOptionGroup(clouds);
        me.cortex.voxy.client.fog.SodiumFogOptions.append(b,page);
        mod.addPage(page);
    }
}
