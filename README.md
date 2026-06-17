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

The six climate parameters determine where your biome generates:

| Parameter | What it controls | Typical ranges |
|-----------|-----------------|----------------|
| Temperature | Hot vs cold | -1.0 (frozen) to 1.0 (desert) |
| Humidity | Wet vs dry | -1.0 (arid) to 1.0 (tropical) |
| Continentalness | Coast vs inland | -0.19 (ocean) to 1.0 (deep inland) |
| Erosion | Flat vs mountainous | -1.0 (peaks) to 1.0 (flat plains) |
| Depth | Surface vs cave | 0.0 for surface biomes |
| Weirdness | Biome variant selection | -1.0 to 1.0 |

Your biome generates wherever its parameter box is the closest match to the sampled noise values. Vanilla biomes tile the entire parameter space at fitness 0, so your biome wins by exact-fitness ties — the box shape and position matter more than the box size.

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
