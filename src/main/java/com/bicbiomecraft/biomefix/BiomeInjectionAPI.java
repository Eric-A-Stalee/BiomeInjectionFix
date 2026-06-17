package com.bicbiomecraft.biomefix;

import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Generic API for injecting biomes into OverworldBiomeBuilder.addBiomes().
 *
 * Forge 1.18.2–1.20.1 BiomeManager.addBiome() is dead code — this API
 * provides a working alternative via Mixin. Any mod can register a callback
 * to inject biomes with proper climate parameters.
 */
public final class BiomeInjectionAPI {

    private static final List<Consumer<Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>>> callbacks = new ArrayList<>();

    public static void register(Consumer<Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>> callback) {
        callbacks.add(callback);
    }

    public static void fireAll(Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> consumer) {
        for (var cb : callbacks) {
            cb.accept(consumer);
        }
    }

    private BiomeInjectionAPI() {}
}
