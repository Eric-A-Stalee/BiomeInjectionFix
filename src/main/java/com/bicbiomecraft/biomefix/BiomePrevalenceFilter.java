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

        int cellSize = calibrateCellSize(allParams, sampler);

        PrevalenceConfig config = PrevalenceConfig.instance();
        if (config != null && config.getCellSizeOverride() != null) {
            cellSize = config.getCellSizeOverride();
            LOGGER.info("Using config cell size override: {} blocks", cellSize);
        }

        int cellSizeQuarts = Math.max(1, cellSize / 4);
        int cellShift = Integer.numberOfTrailingZeros(Integer.highestOneBit(cellSizeQuarts));

        if (config != null) {
            config.updateAutoCellSize(cellSize);
        }

        LOGGER.info("Prevalence filter active: {} modded biomes, cell size {} blocks (shift {})",
                moddedPairs.size(), cellSize, cellShift);

        return new InstanceState(moddedRTree, worldSeed, cellShift);
    }

    private static int calibrateCellSize(Climate.ParameterList<Holder<Biome>> params, Climate.Sampler sampler) {
        int gridSize = 16;
        int spacing = 64;
        int transitions = 0;
        int totalPairs = 0;

        for (int gx = 0; gx < gridSize; gx++) {
            Holder<Biome> prev = null;
            for (int gz = 0; gz < gridSize; gz++) {
                int qx = gx * spacing;
                int qz = gz * spacing;
                Climate.TargetPoint target = sampler.sample(qx, 0, qz);
                Holder<Biome> current = params.findValue(target);
                if (prev != null) {
                    totalPairs++;
                    if (current != prev) {
                        transitions++;
                    }
                }
                prev = current;
            }
        }

        if (transitions == 0) {
            LOGGER.debug("No biome transitions detected in sample — using default cell size 1024");
            return 1024;
        }

        float avgTransitionBlocks = (float) (totalPairs * spacing * 4) / transitions;
        int cellSize = Integer.highestOneBit((int) avgTransitionBlocks);
        cellSize = Math.max(256, Math.min(4096, cellSize));

        LOGGER.info("Auto-calibrated cell size: {} blocks (avg biome span: {} blocks, {} transitions in sample)",
                cellSize, (int) avgTransitionBlocks, transitions);

        return cellSize;
    }

    public static Holder<Biome> filter(InstanceState state, Holder<Biome> original,
            int qx, int qy, int qz, Climate.Sampler sampler) {
        if (state.isNoop()) return original;

        ResourceKey<Biome> originalKey = original.unwrapKey().orElse(null);
        if (originalKey == null || BiomeInjectionAPI.isModdedBiome(originalKey)) {
            return original;
        }

        PrevalenceConfig config = PrevalenceConfig.instance();
        if (config == null) return original;

        long cellX = (long) qx >> state.cellShift;
        long cellZ = (long) qz >> state.cellShift;
        long cellHash = hashCell(state.worldSeed, cellX, cellZ);
        float cellUnit = (cellHash & 0xFFFFFFFFL) / (float) 0x100000000L;

        Climate.TargetPoint target = sampler.sample(qx, qy, qz);
        Holder<Biome> candidate = state.moddedRTree.findValue(target);
        ResourceKey<Biome> candidateKey = candidate.unwrapKey().orElse(null);
        if (candidateKey == null) return original;

        float prevalenceTarget = config.getEffectiveTarget(candidateKey);
        if (prevalenceTarget <= 0) return original;

        if (cellUnit < prevalenceTarget) {
            return candidate;
        }

        return original;
    }

    private static long hashCell(long seed, long cellX, long cellZ) {
        long hash = seed;
        hash = hash * 6364136223846793005L + 1442695040888963407L;
        hash += cellX;
        hash = hash * 6364136223846793005L + 1442695040888963407L;
        hash += cellZ;
        hash = hash * 6364136223846793005L + 1442695040888963407L;
        return hash;
    }

    public static class InstanceState {
        static final InstanceState NOOP = new InstanceState(null, 0, 0);

        final Climate.ParameterList<Holder<Biome>> moddedRTree;
        final long worldSeed;
        final int cellShift;

        InstanceState(Climate.ParameterList<Holder<Biome>> moddedRTree, long worldSeed, int cellShift) {
            this.moddedRTree = moddedRTree;
            this.worldSeed = worldSeed;
            this.cellShift = cellShift;
        }

        boolean isNoop() {
            return this == NOOP;
        }
    }
}
