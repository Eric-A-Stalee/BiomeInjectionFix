# BiomeInjectionFix

**Fixes the dead `BiomeManager.addBiome()` on Forge/NeoForge 1.18.2 through 1.20.1.**

Since Forge 1.18.2, `BiomeManager.addBiome()` populates a list that nothing reads. Vanilla's `OverworldBiomeBuilder.addBiomes()` only knows about vanilla biomes, so modded biomes registered through the official API never appear in the world. This mod fixes that by injecting a Mixin hook at the tail of `addBiomes()`, providing a generic callback API that any mod can use.

## The Problem

When Minecraft 1.18 rewrote worldgen to use `MultiNoiseBiomeSource` with climate parameters, the old `BiomeManager.addBiome()` path stopped working. Forge kept the method signature but the internal list it populates is never consulted during world generation. Modded biomes registered this way simply don't generate.

The previous workaround was a JavaScript coremod using Nashorn to patch bytecode at class-loading time. Nashorn was removed from the JDK in Java 15, and Forge bundles a standalone version — but NeoForge is deprecating JS coremods entirely. This Mixin-based fix replaces both approaches with a clean, future-proof solution.

## How It Works

A Mixin injects at the tail of `OverworldBiomeBuilder.addBiomes(Consumer)`. After vanilla finishes adding its biomes, the Mixin calls `BiomeInjectionAPI.fireAll(consumer)`, which iterates all registered callbacks and passes them the same `Consumer` that vanilla used. Each callback can add biomes with proper `Climate.ParameterPoint` climate parameters, making them first-class citizens in the `MultiNoiseBiomeSource`.

## Usage

### For mod developers

**1. Add BiomeInjectionFix as a dependency.**

Either include the jar in your `libs/` folder:
```gradle
repositories {
    flatDir { dirs 'libs' }
}

dependencies {
    implementation name: 'biomeinjectionfix-1.0.0'
}
```

Or add it as a git submodule:
```bash
git submodule add https://github.com/Eric-A-Stalee/BiomeInjectionFix.git libs/biomeinjectionfix
```

**2. Declare a mandatory dependency in your `mods.toml`:**
```toml
[[dependencies.yourmod]]
modId="biomeinjectionfix"
mandatory=true
versionRange="[1.0.0,)"
ordering="BEFORE"
side="BOTH"
```

**3. Register your biomes at mod construction:**
```java
import com.bicbiomecraft.biomefix.BiomeInjectionAPI;

@Mod("yourmod")
public class YourMod {
    public YourMod(IEventBus modBus) {
        BiomeInjectionAPI.register(consumer -> {
            // Add biomes with climate parameters
            consumer.accept(Pair.of(
                new Climate.ParameterPoint(
                    Climate.Parameter.span(-0.2f, 0.5f),   // temperature
                    Climate.Parameter.span(-0.1f, 0.3f),   // humidity
                    Climate.Parameter.span(0.03f, 1.0f),   // continentalness
                    Climate.Parameter.span(0.05f, 0.45f),  // erosion
                    Climate.Parameter.point(0.0f),          // depth (surface)
                    Climate.Parameter.span(-1.0f, 1.0f),   // weirdness
                    0L                                      // offset
                ),
                ResourceKey.create(Registries.BIOME,
                    new ResourceLocation("yourmod", "your_biome"))
            ));
        });
    }
}
```

### Climate Parameters

Each biome declares a "box" in a 6-dimensional space (temperature, humidity, continentalness, erosion, depth, weirdness). At every world position, Minecraft samples six noise values and picks the biome whose box is the closest match. Think of it like declaring "my biome goes in hot, dry, inland, flat areas" — but in precise numeric ranges.

The tricky part: vanilla biomes already cover the entire space perfectly. Your modded biome doesn't claim empty territory — it competes with vanilla biomes for the same regions. Where your box overlaps a vanilla box and the noise lands inside both, the game picks one based on internal tie-breaking. This means **where you position your box matters more than how big you make it.**

For a detailed explanation of how the parameter system works, how to tune your biome's prevalence, how the R-tree selection works, and how this interacts with mods like ReTerraForged and Distant Horizons, see **[docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md)**.

### For players

Drop `biomeinjectionfix-1.0.0.jar` into your `mods/` folder alongside any mod that requires it. If a mod depends on BiomeInjectionFix, it will show a clear "missing dependency" screen if the jar is absent.

## Compatibility

- **Forge 1.20.1** (47.x) — works
- **NeoForge 1.20.1** (47.1.x) — works
- **Forge 1.18.2 / 1.19.4** — should work (same dead API, same Mixin target)
- **NeoForge 1.21.1+** — may not be needed if NeoForge fixed `addBiome()`. Check before including.

The Mixin targets both Mojang-mapped (`addBiomes`) and SRG-mapped (`m_187175_`) method names for cross-loader compatibility.

## Building

```bash
./gradlew build
```

Requires JDK 17. Output jar is at `build/libs/biomeinjectionfix-1.0.0.jar`.

## License

MIT
