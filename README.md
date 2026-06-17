# BiomeInjectionFix — Fabric

**Fabric version of BiomeInjectionFix for Minecraft 1.20.1.**

This is the Fabric build of [BiomeInjectionFix](https://github.com/Eric-A-Stalee/BiomeInjectionFix). The Forge/NeoForge version is on the `main` branch.

## What It Does

Fixes the dead `BiomeManager.addBiome()` on Forge 1.18.2–1.20.1 by providing a generic Mixin-based biome injection API. While Fabric has native Mixin support and doesn't suffer from the same JS coremod deprecation, this mod provides a **shared API** (`BiomeInjectionAPI.register()`) that works identically across Forge, NeoForge, and Fabric — allowing biome mods to use the same registration code on all loaders.

## Usage

### For mod developers

**1. Add as a dependency:**

```gradle
dependencies {
    modImplementation files('libs/biomeinjectionfix-fabric-1.0.0.jar')
}
```

**2. Declare dependency in `fabric.mod.json`:**
```json
{
  "depends": {
    "biomeinjectionfix": ">=1.0.0"
  }
}
```

**3. Register your biomes at mod init:**
```java
import com.bicbiomecraft.biomefix.BiomeInjectionAPI;

public class YourMod implements ModInitializer {
    @Override
    public void onInitialize() {
        BiomeInjectionAPI.register(consumer -> {
            consumer.accept(Pair.of(climateParams, biomeKey));
        });
    }
}
```

### For players

Drop `biomeinjectionfix-fabric-1.0.0.jar` into your `mods/` folder alongside any mod that requires it.

## Building

```bash
./gradlew build
```

Requires JDK 17. Uses Fabric Loom with Mojang mappings. Output: `build/libs/biomeinjectionfix-fabric-1.0.0.jar`.

## Technical Details

See [docs/HOW_IT_WORKS.md](https://github.com/Eric-A-Stalee/BiomeInjectionFix/blob/main/docs/HOW_IT_WORKS.md) on the `main` branch for the full technical explanation of how biome injection works, climate parameters, and interactions with other mods.

## License

MIT
