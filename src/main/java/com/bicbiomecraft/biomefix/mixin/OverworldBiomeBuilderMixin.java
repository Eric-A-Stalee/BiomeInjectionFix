package com.bicbiomecraft.biomefix.mixin;

import com.bicbiomecraft.biomefix.BiomeInjectionAPI;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(OverworldBiomeBuilder.class)
public class OverworldBiomeBuilderMixin {

    @Inject(method = {"addBiomes", "m_187175_"}, at = @At("TAIL"))
    protected void biomeinjectionfix$fireCallbacks(
            Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> consumer,
            CallbackInfo ci) {
        BiomeInjectionAPI.fireAll(consumer);
    }
}
