package com.bicbiomecraft.biomefix.mixin;

import com.bicbiomecraft.biomefix.BiomePrevalenceFilter;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiNoiseBiomeSource.class)
public class MultiNoiseBiomeSourceMixin {

    @Unique
    private volatile BiomePrevalenceFilter.InstanceState biomeinjectionfix$state;

    @Inject(method = "getNoiseBiome", at = @At("RETURN"), cancellable = true)
    private void biomeinjectionfix$filterPrevalence(int x, int y, int z,
            Climate.Sampler sampler, CallbackInfoReturnable<Holder<Biome>> cir) {
        BiomePrevalenceFilter.InstanceState state = this.biomeinjectionfix$state;
        if (state == null) {
            synchronized (this) {
                state = this.biomeinjectionfix$state;
                if (state == null) {
                    state = BiomePrevalenceFilter.initForSource(
                            (MultiNoiseBiomeSource) (Object) this, sampler);
                    this.biomeinjectionfix$state = state;
                }
            }
        }

        Holder<Biome> filtered = BiomePrevalenceFilter.filter(
                state, cir.getReturnValue(), x, y, z, sampler);
        if (filtered != cir.getReturnValue()) {
            cir.setReturnValue(filtered);
        }
    }
}
