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
    private static final long FAMILY_PENALTY = 100_000_000L;

    private static long paramFitness(Climate.ParameterPoint pp, Climate.TargetPoint tp) {
        long d0 = pp.temperature().distance(tp.temperature());
        long d1 = pp.humidity().distance(tp.humidity());
        long d2 = pp.continentalness().distance(tp.continentalness());
        long d3 = pp.erosion().distance(tp.erosion());
        long d4 = pp.depth().distance(tp.depth());
        long d5 = pp.weirdness().distance(tp.weirdness());
        long d6 = pp.offset();
        return d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3 + d4 * d4 + d5 * d5 + d6 * d6;
    }

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

        List<ModdedEntry> resolvedEntries = new ArrayList<>();
        int maxFamilyIndex = -1;
        for (var entry : moddedEntries) {
            Holder<Biome> holder = keyToHolder.get(entry.getSecond());
            if (holder != null) {
                int familyIndex = BiomeInjectionAPI.getFamily(entry.getSecond());
                resolvedEntries.add(new ModdedEntry(entry.getFirst(), holder, familyIndex));
                if (familyIndex > maxFamilyIndex) {
                    maxFamilyIndex = familyIndex;
                }
            }
        }

        if (resolvedEntries.isEmpty()) {
            LOGGER.debug("No modded biomes resolved in this biome source — filter inactive");
            return InstanceState.NOOP;
        }

        ModdedEntry[] entries = resolvedEntries.toArray(new ModdedEntry[0]);
        int familyCount = maxFamilyIndex + 1;

        Climate.TargetPoint probe = sampler.sample(0, 0, 0);
        long worldSeed = probe.temperature() * 31 + probe.humidity() * 37
                + probe.continentalness() * 41 + probe.erosion() * 43
                + probe.depth() * 47 + probe.weirdness() * 53;

        PrevalenceConfig config = PrevalenceConfig.instance();
        double noiseScale = 2500.0;
        double noiseOctave2Scale = 600.0;
        double noiseOctave2Amplitude = 0.4;
        double familyNoiseScale = 1500.0;
        if (config != null) {
            noiseScale = config.getNoiseScale();
            noiseOctave2Scale = config.getNoiseOctave2Scale();
            noiseOctave2Amplitude = config.getNoiseOctave2Amplitude();
            familyNoiseScale = config.getFamilyNoiseScale();
        }

        SimplexNoise2D noise1 = new SimplexNoise2D(worldSeed);
        SimplexNoise2D noise2 = new SimplexNoise2D(worldSeed ^ 0x9E3779B97F4A7C15L);

        SimplexNoise2D[] familyNoises = new SimplexNoise2D[familyCount];
        for (int i = 0; i < familyCount; i++) {
            familyNoises[i] = new SimplexNoise2D(worldSeed ^ (i * 0x9E3779B97F4A7C15L + 0xABCDEF0123456789L));
        }

        LOGGER.info("Prevalence filter active: {} modded biomes, {} families, noise scale {} / {} blocks (octave2 amp {})",
                entries.length, familyCount, (int) noiseScale, (int) noiseOctave2Scale, noiseOctave2Amplitude);

        return new InstanceState(entries, worldSeed, noise1, noise2, noiseScale, noiseOctave2Scale, noiseOctave2Amplitude,
                familyNoises, familyCount, familyNoiseScale);
    }

    private static final double TRANSITION_WIDTH = 0.0;

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

        int activeFamily = -1;
        if (state.familyCount > 0) {
            double bestNoise = Double.NEGATIVE_INFINITY;
            for (int f = 0; f < state.familyCount; f++) {
                double fn = state.familyNoises[f].sample(
                    blockX / state.familyNoiseScale, blockZ / state.familyNoiseScale);
                if (fn > bestNoise) {
                    bestNoise = fn;
                    activeFamily = f;
                }
            }
        }

        Holder<Biome> candidate = null;
        long bestFitness = Long.MAX_VALUE;
        for (ModdedEntry entry : state.entries) {
            long fitness = paramFitness(entry.point, target);
            if (entry.familyIndex >= 0 && entry.familyIndex != activeFamily) {
                fitness += FAMILY_PENALTY;
            }
            if (fitness < bestFitness) {
                bestFitness = fitness;
                candidate = entry.holder;
            }
        }
        if (candidate == null) return original;

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

    static final class ModdedEntry {
        final Climate.ParameterPoint point;
        final Holder<Biome> holder;
        final int familyIndex;
        ModdedEntry(Climate.ParameterPoint point, Holder<Biome> holder, int familyIndex) {
            this.point = point;
            this.holder = holder;
            this.familyIndex = familyIndex;
        }
    }

    public static class InstanceState {
        static final InstanceState NOOP = new InstanceState(null, 0, null, null, 0, 0, 0, null, 0, 0);

        final ModdedEntry[] entries;
        final long seed;
        final SimplexNoise2D noise1;
        final SimplexNoise2D noise2;
        final double noiseScale;
        final double octave2Scale;
        final double octave2Amplitude;
        final SimplexNoise2D[] familyNoises;
        final int familyCount;
        final double familyNoiseScale;

        InstanceState(ModdedEntry[] entries, long seed,
                SimplexNoise2D noise1, SimplexNoise2D noise2,
                double noiseScale, double octave2Scale, double octave2Amplitude,
                SimplexNoise2D[] familyNoises, int familyCount, double familyNoiseScale) {
            this.entries = entries;
            this.seed = seed;
            this.noise1 = noise1;
            this.noise2 = noise2;
            this.noiseScale = noiseScale;
            this.octave2Scale = octave2Scale;
            this.octave2Amplitude = octave2Amplitude;
            this.familyNoises = familyNoises;
            this.familyCount = familyCount;
            this.familyNoiseScale = familyNoiseScale;
        }

        boolean isNoop() {
            return this == NOOP;
        }
    }
}
