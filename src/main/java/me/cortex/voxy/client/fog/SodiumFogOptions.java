package me.cortex.voxy.client.fog;

import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Fog options on the Voxy page. */
public final class SodiumFogOptions {
    private SodiumFogOptions() {}
    public static void append(ConfigBuilder b, OptionPageBuilder page) {
        var group=b.createOptionGroup();
        String key="voxy_renderdistance_fog.option.enabled";
        group.addOption(b.createBooleanOption(ResourceLocation.parse("voxy_renderdistance_fog:enabled"))
                .setName(Component.translatable(key)).setTooltip(Component.translatable(key+".tooltip"))
                .setBinding(VoxyFogConfig::setEnabled,VoxyFogConfig::isEnabled)
                .setDefaultValue(VoxyFogConfig.DEFAULT_ENABLED).setStorageHandler(VoxyFogConfig::save));
        for (boolean min : new boolean[]{true,false}) {
            String suffix=min?"fog_min":"fog_max",id="voxy_renderdistance_fog:"+suffix;
            IntSupplier get=()->Math.round((min?VoxyFogConfig.getFogMin():VoxyFogConfig.getFogMax())*100);
            IntConsumer set=v->{if(min)VoxyFogConfig.setFogMin(Math.clamp(v,0,100)/100.0f);else VoxyFogConfig.setFogMax(Math.clamp(v,0,100)/100.0f);};
            me.cortex.voxy.client.config.LiveOptionRegistry.register(id,0,100,1,get,set);
            String label="voxy_renderdistance_fog.option."+suffix;
            group.addOption(b.createIntegerOption(ResourceLocation.parse(id)).setName(Component.translatable(label))
                    .setTooltip(Component.translatable(label+".tooltip")).setRange(new Range(0,100,1))
                    .setValueFormatter(v->Component.literal(String.format(Locale.ROOT,"%.2f",v/100.0)))
                    .setBinding(set::accept,get::getAsInt).setDefaultValue(Math.round((min?VoxyFogConfig.DEFAULT_FOG_MIN:VoxyFogConfig.DEFAULT_FOG_MAX)*100)).setStorageHandler(VoxyFogConfig::save));
        }
        page.addOptionGroup(group);
    }
}
