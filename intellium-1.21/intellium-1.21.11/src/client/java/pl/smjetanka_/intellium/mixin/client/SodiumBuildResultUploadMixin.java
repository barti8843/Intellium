package pl.smjetanka_.intellium.mixin.client;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.smjetanka_.intellium.client.IntelRenderer;
import pl.smjetanka_.intellium.client.IntelTerrainBackend;

/** Copies completed Sodium mesh payloads from worker threads into CPU staging memory. */
@Mixin(ChunkBuilderMeshingTask.class)
abstract class SodiumBuildResultUploadMixin {
    @Inject(
            method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            at = @At("RETURN")
    )
    private void intellium$stageCompletedMesh(
            ChunkBuildContext context,
            CancellationToken cancellationToken,
            CallbackInfoReturnable<ChunkBuildOutput> cir
    ) {
        if (IntelRenderer.getInstance().isBackendReady()) {
            ChunkBuildOutput output = cir.getReturnValue();
            if (output != null) {
                IntelTerrainBackend.getInstance().stageBuiltOutput(output);
            }
        }
    }
}
