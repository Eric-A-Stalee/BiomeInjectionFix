package com.bicbiomecraft.biomefix;

import com.google.gson.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class PrevalenceConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("BiomeInjectionFix");
    private static PrevalenceConfig instance;

    private float globalTarget = 0.50f;
    private double noiseScale = 2500.0;
    private double noiseOctave2Scale = 600.0;
    private double noiseOctave2Amplitude = 0.4;
    private double familyNoiseScale = 1500.0;
    private final Map<String, Float> modOverrides = new HashMap<>();
    private final Map<String, Float> biomeOverrides = new HashMap<>();

    private PrevalenceConfig() {}

    static void initialize() {
        instance = new PrevalenceConfig();
        instance.loadAndMerge();
    }

    public static PrevalenceConfig instance() {
        return instance;
    }

    public float getGlobalTarget() {
        return globalTarget;
    }

    public double getNoiseScale() {
        return noiseScale;
    }

    public double getNoiseOctave2Scale() {
        return noiseOctave2Scale;
    }

    public double getNoiseOctave2Amplitude() {
        return noiseOctave2Amplitude;
    }

    public double getFamilyNoiseScale() {
        return familyNoiseScale;
    }

    public float getEffectiveTarget(ResourceKey<Biome> biomeKey) {
        String biomeId = biomeKey.location().toString();
        String modId = BiomeInjectionAPI.getOwner(biomeKey);

        if (biomeOverrides.containsKey(biomeId)) {
            return Math.min(biomeOverrides.get(biomeId), globalTarget);
        }
        if (modId != null && modOverrides.containsKey(modId)) {
            return Math.min(modOverrides.get(modId), globalTarget);
        }
        if (modId != null) {
            Float modDefault = BiomeInjectionAPI.getModTargets().get(modId);
            if (modDefault != null) {
                return Math.min(modDefault, globalTarget);
            }
        }
        return 0.0f;
    }

    private void loadAndMerge() {
        Path configDir = Path.of("config");
        Path configFile = configDir.resolve("biomeinjectionfix-prevalence.json");

        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile)) {
                parseOverrides(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (Exception e) {
                LOGGER.warn("Failed to read prevalence config, using defaults", e);
            }
        }

        try {
            Files.createDirectories(configDir);
            JsonObject merged = buildMergedConfig();
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(merged, writer);
            }
            LOGGER.info("Prevalence config written to {}", configFile);
        } catch (Exception e) {
            LOGGER.warn("Failed to write prevalence config", e);
        }
    }

    private void parseOverrides(JsonObject json) {
        if (json.has("global_target")) {
            globalTarget = json.get("global_target").getAsFloat();
        }
        if (json.has("noise_scale") && !json.get("noise_scale").isJsonNull()) {
            noiseScale = json.get("noise_scale").getAsDouble();
        }
        if (json.has("noise_octave2_scale") && !json.get("noise_octave2_scale").isJsonNull()) {
            noiseOctave2Scale = json.get("noise_octave2_scale").getAsDouble();
        }
        if (json.has("noise_octave2_amplitude") && !json.get("noise_octave2_amplitude").isJsonNull()) {
            noiseOctave2Amplitude = json.get("noise_octave2_amplitude").getAsDouble();
        }
        if (json.has("family_noise_scale") && !json.get("family_noise_scale").isJsonNull()) {
            familyNoiseScale = json.get("family_noise_scale").getAsDouble();
        }
        if (!json.has("mods")) return;
        JsonObject mods = json.getAsJsonObject("mods");
        for (var modEntry : mods.entrySet()) {
            String modId = modEntry.getKey();
            JsonObject modObj = modEntry.getValue().getAsJsonObject();
            if (modObj.has("target") && !modObj.get("target").isJsonNull()) {
                modOverrides.put(modId, modObj.get("target").getAsFloat());
            }
            if (!modObj.has("biomes")) continue;
            for (var biomeEntry : modObj.getAsJsonObject("biomes").entrySet()) {
                JsonObject biomeObj = biomeEntry.getValue().getAsJsonObject();
                if (biomeObj.has("target") && !biomeObj.get("target").isJsonNull()) {
                    biomeOverrides.put(biomeEntry.getKey(), biomeObj.get("target").getAsFloat());
                }
            }
        }
    }

    private JsonObject buildMergedConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("global_target", globalTarget);
        root.addProperty("noise_scale", noiseScale);
        root.addProperty("noise_octave2_scale", noiseOctave2Scale);
        root.addProperty("noise_octave2_amplitude", noiseOctave2Amplitude);
        root.addProperty("family_noise_scale", familyNoiseScale);

        Map<String, List<String>> modBiomes = new LinkedHashMap<>();
        for (var entry : BiomeInjectionAPI.getBiomeOwnership().entrySet()) {
            modBiomes.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                    .add(entry.getKey().location().toString());
        }

        JsonObject modsObj = new JsonObject();
        for (var modEntry : BiomeInjectionAPI.getModTargets().entrySet()) {
            String modId = modEntry.getKey();
            float modDefault = modEntry.getValue();

            JsonObject modObj = new JsonObject();
            modObj.addProperty("_default", modDefault);
            modObj.add("target", modOverrides.containsKey(modId)
                    ? new JsonPrimitive(modOverrides.get(modId)) : JsonNull.INSTANCE);

            JsonObject biomesObj = new JsonObject();
            List<String> biomes = modBiomes.getOrDefault(modId, Collections.emptyList());
            Collections.sort(biomes);
            for (String biomeId : biomes) {
                JsonObject biomeObj = new JsonObject();
                biomeObj.addProperty("_default", modDefault);
                biomeObj.add("target", biomeOverrides.containsKey(biomeId)
                        ? new JsonPrimitive(biomeOverrides.get(biomeId)) : JsonNull.INSTANCE);
                biomesObj.add(biomeId, biomeObj);
            }
            modObj.add("biomes", biomesObj);
            modsObj.add(modId, modObj);
        }

        root.add("mods", modsObj);
        return root;
    }
}
