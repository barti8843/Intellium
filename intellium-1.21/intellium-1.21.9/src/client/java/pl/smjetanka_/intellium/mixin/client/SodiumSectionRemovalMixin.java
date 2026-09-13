package pl.smjetanka_.intellium.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.smjetanka_.intellium.client.IntelTerrainBackend;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
abstract class SodiumSectionRemovalMixin {
    @Inject(method = "onSectionRemoved", at = @At("TAIL"))
    private void intellium$releaseSection(int x, int y, int z, CallbackInfo ci) {
        IntelTerrainBackend.getInstance().removeSection(x, y, z);
    }
}
