package pl.smjetanka_.intellium.mixin.client;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask", remap = false)
public abstract class SodiumChunkBuilderMixin {
    // Wycofane ze względu na blokowanie wątków tła Sodium w wersji 1.21.x.
}