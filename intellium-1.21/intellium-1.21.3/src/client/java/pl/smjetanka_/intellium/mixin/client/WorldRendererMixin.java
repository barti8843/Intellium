package pl.smjetanka_.intellium.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.smjetanka_.intellium.client.IntelRenderer;

/**
 * Render-thread hook. It deliberately observes vanilla rendering instead of cancelling it:
 * terrain mesh capture must be complete before Intellium can replace vanilla terrain draws.
 */
// Official Mojang mappings call Yarn's WorldRenderer "LevelRenderer".
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer")
abstract class WorldRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void intellium$beginWorldRender(CallbackInfo ci) {
        IntelRenderer.getInstance().beginWorldRender();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void intellium$endWorldRender(CallbackInfo ci) {
        IntelRenderer.getInstance().endWorldRender();
    }
}
