package pl.smjetanka_.intellium.mixin.client;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs Sodium's ownership-aware native buffer reclamation after a build output
 * has released its buffers. Intellium's staging queue contains independent
 * copies, so this cannot free queued staging data.
 */
@Mixin(ChunkBuildOutput.class)
abstract class SodiumCleanUpMixin {
    @Inject(method = "destroy", at = @At("RETURN"))
    private void intellium$reclaimReleasedBuffers(CallbackInfo callbackInfo) {
        NativeBuffer.reclaim(false);
    }
}
