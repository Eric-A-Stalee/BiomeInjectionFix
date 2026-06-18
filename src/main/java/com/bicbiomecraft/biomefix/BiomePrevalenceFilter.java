package com.bicbiomecraft.biomefix;

import com.bicbiomecraft.biomefix.mixin.MultiNoiseBiomeSourceAccessor;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class BiomePrevalenceFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger("BiomeInjectionFix");

    private BiomePrevalenceFilter() {}

    public static InstanceState initForSource(MultiNoiseBiomeSource source, Climate.Sampler sampler) {
        List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> moddedEntries = BiomeInjectionAPI.getModdedEntries();
        if (moddedEntries.isEmpty()) {
            return InstanceState.NOOP;
        }

        Climate.ParameterList<Holder<Biome>> allParams =
                ((MultiNoiseBiomeSourceAccessor) source).biomeinjectionfix$parameters();

        Map<ResourceKey<Biome>, Holder<Biome>> keyToHolder = new HashMap<>();
        for (Pair<Climate.ParameterPoint, Holder<Biome>> pair : allParams.values()) {
            pair.getSecond().unwrapKey().ifPresent(key -> keyToHolder.put(key, pair.getSecond()));
        }

        List<Pair<Climate.ParameterPoint, Holder<Biome>>> moddedPairs = new ArrayList<>();
        for (var entry : moddedEntries) {
            Holder<Biome> holder = keyToHolder.get(entry.getSecond());
            if (holder != null) {
                moddedPairs.add(Pair.of(entry.getFirst(), holder));
            }
        }

        if (moddedPairs.isEmpty()) {
            LOGGER.debug("No modded biomes resolved in this biome source — filter inactive");
            return InstanceState.NOOP;
        }

        Climate.ParameterList<Holder<Biome>> moddedRTree = new Climate.ParameterList<>(moddedPairs);

        Climate.TargetPoint probe = sampler.sample(0, 0, 0);
        long worldSeed = probe.temperature() * 31 + probe.humidity() * 37
                + probe.continentalness() * 41 + probe.erosion() * 43
                + probe.depth() * 47 + probe.weirdness() * 53;

        PrevalenceConfig config = PrevalenceConfig.instance();
        double noiseScale = 2500.0;
        double noiseOctave2Scale = 600.0;
        double noiseOctave2Amplitude = 0.4;
        if (config != null) {
            noiseScale = config.getNoiseScale();
            noiseOctave2Scale = config.getNoiseOctave2Scale();
            noiseOctave2Amplitude = config.getNoiseOctave2Amplitude();
        }

        SimplexNoise2D noise1 = new SimplexNoise2D(worldSeed);
        SimplexNoise2D noise2 = new SimplexNoise2D(worldSeed ^ 0x9E3779B97F4A7C15L);

        LOGGER.info("Prevalence filter active: {} modded biomes, noise scale {} / {} blocks (octave2 amp {})",
                moddedPairs.size(), (int) noiseScale, (int) noiseOctave2Scale, noiseOctave2Amplitude);

        return new InstanceState(moddedRTree, worldSeed, noise1, noise2, noiseScale, noiseOctave2Scale, noiseOctave2Amplitude);
    }

    private static final double TRANSITION_WIDTH = 0.15;

    public static Holder<Biome> filter(InstanceState state, Holder<Biome> original,
            int qx, int qy, int qz, Climate.Sampler sampler) {
        if (state.isNoop()) return original;

        ResourceKey<Biome> originalKey = original.unwrapKey().orElse(null);
        if (originalKey == null || BiomeInjectionAPI.isModdedBiome(originalKey)) {
            return original;
        }

        PrevalenceConfig config = PrevalenceConfig.instance();
        if (config == null) return original;

        double blockX = qx * 4.0;
        double blockZ = qz * 4.0;
        double n1 = state.noise1.sample(blockX / state.noiseScale, blockZ / state.noiseScale);
        double n2 = state.noise2.sample(blockX / state.octave2Scale, blockZ / state.octave2Scale);
        double combined = n1 + n2 * state.octave2Amplitude;
        double maxRange = 1.0 + state.octave2Amplitude;
        float noiseUnit = (float) Math.max(0.0, Math.min(1.0, (combined / maxRange + 1.0) * 0.5));

        float jitter = positionJitter(state.seed, qx, qz, TRANSITION_WIDTH);
        noiseUnit += jitter;

        Climate.TargetPoint target = sampler.sample(qx, qy, qz);
        Holder<Biome> candidate = state.moddedRTree.findValue(target);
        ResourceKey<Biome> candidateKey = candidate.unwrapKey().orElse(null);
        if (candidateKey == null) return original;

        float prevalenceTarget = config.getEffectiveTarget(candidateKey);
        if (prevalenceTarget <= 0) return original;

        if (noiseUnit < prevalenceTarget) {
            return candidate;
        }

        return original;
    }

    static float positionJitter(long seed, int qx, int qz) {
        return positionJitter(seed, qx, qz, TRANSITION_WIDTH);
    }

    static float positionJitter(long seed, int qx, int qz, double width) {
        long h = seed ^ ((long) qx * 0x6C62272E07BB0142L) ^ ((long) qz * 0x517CC1B727220A95L);
        h = h * 6364136223846793005L + 1442695040888963407L;
        h ^= (h >>> 32);
        return (float) (((h & 0xFFFFL) / (double) 0xFFFFL - 0.5) * width);
    }

    public static class InstanceState {
        static final InstanceState NOOP = new InstanceState(null, 0, null, null, 0, 0, 0);

        final Climate.ParameterList<Holder<Biome>> moddedRTree;
        final long seed;
        final SimplexNoise2D noise1;
        final SimplexNoise2D noise2;
        final double noiseScale;
        final double octave2Scale;
        final double octave2Amplitude;

        InstanceState(Climate.ParameterList<Holder<Biome>> moddedRTree, long seed,
                SimplexNoise2D noise1, SimplexNoise2D noise2,
                double noiseScale, double octave2Scale, double octave2Amplitude) {
            this.moddedRTree = moddedRTree;
            this.seed = seed;
            this.noise1 = noise1;
            this.noise2 = noise2;
            this.noiseScale = noiseScale;
            this.octave2Scale = octave2Scale;
            this.octave2Amplitude = octave2Amplitude;
        }

        boolean isNoop() {
            return this == NOOP;
        }
    }
}
