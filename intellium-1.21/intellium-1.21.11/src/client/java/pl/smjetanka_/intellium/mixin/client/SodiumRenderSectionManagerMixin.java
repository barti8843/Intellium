package pl.smjetanka_.intellium.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.smjetanka_.intellium.client.IntelRenderer;

/**
 * Appends Intellium's terrain pass after Sodium has completed its render layer.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
abstract class SodiumRenderSectionManagerMixin {
    @Inject(method = "renderLayer", at = @At("RETURN"))
    private void intellium$finishSodiumTerrainPass(CallbackInfo ci) {
        IntelRenderer.getInstance().flushInstancedTerrain();
    }
}
