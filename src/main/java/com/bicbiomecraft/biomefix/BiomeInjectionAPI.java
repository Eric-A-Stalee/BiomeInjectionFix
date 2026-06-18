package com.bicbiomecraft.biomefix;

import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

import java.util.*;
import java.util.function.Consumer;

public final class BiomeInjectionAPI {

    private static final List<Registration> registrations = new ArrayList<>();
    private static final Map<ResourceKey<Biome>, String> biomeOwnership = new LinkedHashMap<>();
    private static final Map<String, Float> modTargets = new LinkedHashMap<>();
    private static final List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> moddedEntries = new ArrayList<>();
    private static final Map<ResourceKey<Biome>, Integer> familyAssignments = new LinkedHashMap<>();
    private static final Map<ResourceKey<Biome>, Float> biomeDefaultTargets = new LinkedHashMap<>();
    private static boolean fired = false;

    public static void register(String modId, float defaultTarget,
            Consumer<Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>> callback) {
        registrations.add(new Registration(modId, defaultTarget, callback));
        modTargets.put(modId, defaultTarget);
    }

    public static void fireAll(Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> consumer) {
        if (fired) {
            for (var entry : moddedEntries) {
                consumer.accept(entry);
            }
            return;
        }
        fired = true;
        for (var reg : registrations) {
            reg.callback().accept(pair -> {
                biomeOwnership.put(pair.getSecond(), reg.modId());
                moddedEntries.add(pair);
                consumer.accept(pair);
            });
        }
        PrevalenceConfig.initialize();
    }

    public static void setFamily(ResourceKey<Biome> biomeKey, int familyIndex) {
        if (familyIndex < 0) throw new IllegalArgumentException("familyIndex must be >= 0");
        familyAssignments.put(biomeKey, familyIndex);
    }

    public static int getFamily(ResourceKey<Biome> key) {
        Integer idx = familyAssignments.get(key);
        return idx != null ? idx : -1;
    }

    public static void setDefaultTarget(ResourceKey<Biome> biomeKey, float target) {
        biomeDefaultTargets.put(biomeKey, target);
    }

    public static Float getBiomeDefaultTarget(ResourceKey<Biome> key) {
        return biomeDefaultTargets.get(key);
    }

    public static boolean isModdedBiome(ResourceKey<Biome> key) {
        return biomeOwnership.containsKey(key);
    }

    public static String getOwner(ResourceKey<Biome> key) {
        return biomeOwnership.get(key);
    }

    static Map<ResourceKey<Biome>, String> getBiomeOwnership() {
        return Collections.unmodifiableMap(biomeOwnership);
    }

    static Map<String, Float> getModTargets() {
        return Collections.unmodifiableMap(modTargets);
    }

    static List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> getModdedEntries() {
        return Collections.unmodifiableList(moddedEntries);
    }

    static Map<ResourceKey<Biome>, Integer> getFamilyAssignments() {
        return Collections.unmodifiableMap(familyAssignments);
    }

    private BiomeInjectionAPI() {}

    record Registration(String modId, float defaultTarget,
            Consumer<Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>> callback) {}
}
