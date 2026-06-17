# How Biome Injection Works — The Full Picture

This document explains the problem BiomeInjectionFix solves, how Minecraft's biome system works under the hood, how the fix patches into it, and what to watch out for when adding your own biomes.

## Table of Contents

1. [How Vanilla Biomes Generate](#how-vanilla-biomes-generate)
2. [What Broke in 1.18](#what-broke-in-118)
3. [How This Fix Works](#how-this-fix-works)
4. [Climate Parameters Explained](#climate-parameters-explained)
5. [Why Your Biome Might Not Generate](#why-your-biome-might-not-generate)
6. [Interactions With Other Mods](#interactions-with-other-mods)
7. [Technical Details](#technical-details)

---

## How Vanilla Biomes Generate

Since Minecraft 1.18, biomes are selected using a system called **MultiNoiseBiomeSource**. Think of it like a 6-dimensional coordinate system. At every position in the world, Minecraft samples six noise values:

| Noise | What it represents | Real-world analogy |
|-------|--------------------|--------------------|
| **Temperature** | How hot or cold | Latitude / elevation |
| **Humidity** | How wet or dry | Distance from ocean / rainfall |
| **Continentalness** | How far inland | Coast vs interior |
| **Erosion** | How flat or mountainous | Geological age of terrain |
| **Depth** | Surface vs underground | Altitude below ground |
| **Weirdness** | Variant selection | "Mutated" biome variants |

Each biome declares a "box" in this 6D space — a range for each parameter. For example, a desert might say "I want temperature 0.5 to 1.0, humidity -1.0 to -0.3, inland, flat terrain." A tundra might say "temperature -1.0 to -0.4, any humidity, inland."

When the game needs to decide what biome goes at a specific block position, it:

1. Samples the six noise values at that position
2. Looks at every registered biome's parameter box
3. Picks the biome whose box is the **closest match** to the sampled values

"Closest match" means the biome whose parameter box has the smallest distance to the sampled point. If the point is inside a box, the distance is zero — a perfect match.

### The Key Insight

Vanilla registers enough biomes to cover the **entire** 6D parameter space with zero-distance matches. There's no gap. Every possible combination of noise values has at least one vanilla biome that claims it perfectly. This means a modded biome can't simply declare a box and expect to "own" that region — vanilla already has a biome there with the same perfect fitness score.

---

## What Broke in 1.18

Before 1.18, Forge provided `BiomeManager.addBiome()` — you'd call it with your biome and a weight, and Forge would mix it into the biome selection. Simple.

When 1.18 rewrote worldgen, `BiomeManager.addBiome()` was kept for backwards compatibility, but **the list it populates is never read by the new worldgen code**. It's dead code. Your biome gets registered into a list that nothing consults.

The actual biome list lives inside `OverworldBiomeBuilder.addBiomes()`, a vanilla method that hardcodes all vanilla biome entries. Forge provides no hook to add entries to this method. Your biome is registered in Forge's system but invisible to Minecraft's actual biome placement.

This has been broken since Forge 1.18.2 (version 40.x) through at least 1.20.1 (version 47.x). It affects every Forge and NeoForge mod that adds overworld biomes.

---

## How This Fix Works

BiomeInjectionFix applies a [Mixin](https://github.com/SpongePowered/Mixin) to `OverworldBiomeBuilder.addBiomes()`. A Mixin is a way to inject code into vanilla Minecraft classes at load time — cleaner than bytecode manipulation, supported by both Forge and NeoForge.

The Mixin adds a single call at the **tail** (end) of the `addBiomes()` method. After vanilla finishes adding all its biomes to the list, our injected code runs:

```
Vanilla: addBiomes(consumer) {
    consumer.accept(plains);
    consumer.accept(desert);
    consumer.accept(forest);
    ... 60+ vanilla biomes ...
    // <- BiomeInjectionFix inserts here (TAIL)
    BiomeInjectionAPI.fireAll(consumer);  // Calls all registered mod callbacks
}
```

Each mod that registered a callback via `BiomeInjectionAPI.register()` gets the same `consumer` that vanilla used. The mod adds its biomes with `consumer.accept(Pair.of(climateParameters, biomeKey))`, and they become part of the biome parameter list alongside vanilla biomes.

### Why TAIL, not HEAD?

Injecting at the tail (after vanilla) means vanilla biomes are already in the list when your biome is added. This matters because the internal data structure (`Climate.RTree`) is built from the list order, and tie-breaking between biomes with equal fitness scores can depend on insertion order. Injecting after vanilla matches the behavior of the original `BiomeManager.addBiome()` intent.

---

## Climate Parameters Explained

When you register a biome, you provide a `Climate.ParameterPoint` — six parameter ranges that define where your biome should generate.

### Parameter Ranges

Each parameter is specified as a range using `Climate.Parameter.span(min, max)` or a single point using `Climate.Parameter.point(value)`. The range is -1.0 to 1.0 for all parameters.

**Temperature** (-1.0 to 1.0):
- -1.0 to -0.45: Frozen (snowy tundra, ice spikes)
- -0.45 to -0.15: Cold (taiga, old growth)
- -0.15 to 0.2: Temperate (forest, plains)
- 0.2 to 0.55: Warm (jungle, savanna)
- 0.55 to 1.0: Hot (desert, badlands)

**Humidity** (-1.0 to 1.0):
- -1.0 to -0.35: Arid (desert, badlands)
- -0.35 to -0.1: Dry (savanna, plains)
- -0.1 to 0.1: Neutral (forest, meadow)
- 0.1 to 0.3: Wet (swamp, old growth)
- 0.3 to 1.0: Humid (jungle, mushroom)

**Continentalness** (-1.0 to 1.0):
- -1.0 to -0.19: Ocean
- -0.19 to 0.03: Coast
- 0.03 to 0.3: Near-inland
- 0.3 to 1.0: Far inland

Most modded biomes want `span(0.03, 1.0)` (inland) unless they're specifically coastal or ocean biomes.

**Erosion** (-1.0 to 1.0):
- -1.0 to -0.375: Peaks and ridges
- -0.375 to 0.05: Hills and moderate terrain
- 0.05 to 0.45: Rolling terrain
- 0.45 to 1.0: Flat plains and valleys

**Depth**: Use `Climate.Parameter.point(0.0)` for surface biomes. Cave biomes use other values.

**Weirdness** (-1.0 to 1.0): Controls biome variant selection. Most mods use `span(-1.0, 1.0)` (full range) unless they need to split variants.

### How Biome Selection Actually Works

The game builds a spatial search tree (an R-tree) from all registered parameter points. For each world position, it queries the tree with the six sampled noise values and finds the closest match.

**The crucial thing to understand:** Vanilla biomes already cover the entire parameter space perfectly. Your modded biome competes with vanilla biomes for the same space. When your biome's box overlaps a vanilla biome's box and the sampled point is inside both, both have distance 0 (perfect match). The tree breaks ties using an internal ordering that depends on how the tree was built — which is influenced by insertion order and the spatial structure of all the boxes.

This means:

- **Making your box bigger doesn't help much.** A huge box competes with more vanilla biomes.
- **Box position matters more than box size.** A well-positioned box that overlaps fewer vanilla biomes will generate more consistently.
- **You're winning tie-breaks, not claiming empty space.** There's no empty space in the parameter system.
- **Your biome's prevalence is hard to predict** from the parameter box alone. The only reliable way to tune it is to generate a test world and measure.

---

## Why Your Biome Might Not Generate

Common reasons modded biomes don't appear:

1. **You're still using `BiomeManager.addBiome()`** — This is the dead API. Use `BiomeInjectionAPI.register()` instead.

2. **Your biome isn't registered in the biome registry** — The biome must exist as a registered `Biome` object (via datapack JSON or `RegisterEvent`) before injection. The parameter point tells the game *where* to place it; the registry entry defines *what* it is.

3. **Your climate box is too narrow** — A very specific box (e.g., temperature exactly 0.3-0.31) may never win any ties because the noise doesn't sample into a region where your box beats vanilla.

4. **Your climate box is in a crowded region** — Temperature -0.15 to 0.2, humidity -0.1 to 0.3, moderate erosion is where forest, plains, and meadow all compete. Adding another biome there means you're fighting three vanilla biomes for tie-breaks.

5. **Duplicate parameter points** — If two of your biomes declare identical parameter boxes, the tree permanently silences one of them. Each biome needs a unique box.

6. **Your biome JSON is missing or malformed** — Check that `data/yourmod/worldgen/biome/your_biome.json` exists and is valid JSON with proper biome settings.

---

## Interactions With Other Mods

### ReTerraForged (RTF)

RTF replaces the vanilla chunk generator but still uses `MultiNoiseBiomeSource` for biome selection. BiomeInjectionFix patches `OverworldBiomeBuilder.addBiomes()` which is called during `MultiNoiseBiomeSource` construction — this happens regardless of which chunk generator is active. **Your biomes will generate with RTF.**

However, RTF changes the actual terrain shape. Your biome's climate parameters still control *where* it's selected, but the terrain at that location is shaped by RTF's generator, not vanilla's. A biome that expects flat plains terrain might end up on an RTF mountain if the erosion parameter doesn't align with RTF's terrain decisions.

### Distant Horizons (DH)

DH generates LOD (level-of-detail) chunks at distance. It invokes the chunk generator's worldgen pipeline, which includes biome selection. Since BiomeInjectionFix patches biome selection (not chunk generation), **your biomes appear correctly in DH's LOD rendering.**

Note: DH's "Features" distant generation mode can crash with custom chunk generators like RTF. Use "Surface" mode for stability. This is a DH + RTF issue, not related to biome injection.

### Other Biome Mods (Terralith, Biomes O' Plenty, etc.)

If another mod also patches `OverworldBiomeBuilder.addBiomes()`, both patches run — Mixins compose naturally. If they use BiomeInjectionFix's API, all callbacks fire in registration order.

If another mod replaces `MultiNoiseBiomeSource` entirely (rare), biome injection won't work because the patched method is never called.

### Forge's `BiomeModifier` System (1.19.2+)

Forge 1.19.2 introduced `BiomeModifier` JSON files for adding features, spawns, and other properties to existing biomes. This system modifies biomes *after* they're selected — it doesn't control *which* biome is selected at a given position. `BiomeModifier` and `BiomeInjectionFix` are complementary: use BiomeInjectionFix to get your biome into the world, then use `BiomeModifier` to add features/spawns to it or to existing vanilla biomes.

---

## Technical Details

### The Mixin

```java
@Mixin(OverworldBiomeBuilder.class)
public class OverworldBiomeBuilderMixin {
    @Inject(method = {"addBiomes", "m_187175_"}, at = @At("TAIL"))
    protected void biomeinjectionfix$fireCallbacks(
            Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> consumer,
            CallbackInfo ci) {
        BiomeInjectionAPI.fireAll(consumer);
    }
}
```

The method target includes both the Mojang-mapped name (`addBiomes`) and the SRG-mapped name (`m_187175_`). Mojang mappings are used by NeoForge at runtime; SRG mappings are used by Forge at runtime. Including both ensures the Mixin works on either loader without a refmap.

### The API

```java
public final class BiomeInjectionAPI {
    private static final List<Consumer<Consumer<Pair<ParameterPoint, ResourceKey<Biome>>>>> callbacks = new ArrayList<>();

    public static void register(Consumer<Consumer<Pair<ParameterPoint, ResourceKey<Biome>>>> callback) {
        callbacks.add(callback);
    }

    public static void fireAll(Consumer<Pair<ParameterPoint, ResourceKey<Biome>>> consumer) {
        for (var cb : callbacks) cb.accept(consumer);
    }
}
```

`register()` should be called during mod construction (before worldgen runs). The callback receives the same `Consumer` that vanilla uses internally, so `consumer.accept(Pair.of(params, biomeKey))` adds your biome directly to the parameter list.

### Version Compatibility

The `OverworldBiomeBuilder.addBiomes()` method has existed since 1.18 with the same signature. The SRG name `m_187175_` is stable across Forge 1.18.2 through 1.20.1. NeoForge 1.21.1+ may have renamed or restructured this class — check before using this fix on newer versions.

### Why Not Fix Forge's `addBiome()` Directly?

The dead code path in Forge's `BiomeManager` populates a `List<BiomeEntry>` that's never read by `MultiNoiseBiomeSource`. Fixing it would require patching `MultiNoiseBiomeSource` to consult that list during construction — a larger and more fragile change. The `addBiomes()` hook is simpler, more direct, and doesn't depend on Forge's internal data structures.
