package pl.smjetanka_.intellium.client;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenGL 3.3-compatible Intellium renderer.
 *
 * <p>All OpenGL work is performed on Minecraft's render thread. Worker threads
 * only enqueue immutable chunk records.</p>
 */
public final class IntelRenderer implements AutoCloseable {
    public enum RenderPath {
        DISABLED,
        INTEL_LEGACY_UHD,
        INTEL_XE_UHD_COMPATIBILITY,
        INTEL_IRIS_XE_ADVANCED,
        INTEL_ARC_ULTRA
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Intellium/Renderer");
    private static final IntelRenderer INSTANCE = new IntelRenderer();
    private static final int MAX_CHUNKS = 16_384;

    private final ConcurrentLinkedQueue<ChunkUpdate> pendingChunks = new ConcurrentLinkedQueue<>();
    private final Map<Long, ChunkDraw> activeChunks = new LinkedHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean();
    private final AtomicBoolean disabled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile RenderPath renderPath = RenderPath.DISABLED;

    private IntelTerrainBackend backend;
    private IntelTerrainProgram terrainProgram;
    private int frameChunkCount;
    private boolean frameOpen;
    private boolean stagedGeometryUploaded;

    private IntelRenderer() {
    }

    public static IntelRenderer getInstance() {
        return INSTANCE;
    }

    public RenderPath getRenderPath() {
        return renderPath;
    }

    public void initialize() {
        LOGGER.info("Intellium renderer registered; OpenGL 3.3 resources are deferred until the first world frame.");
    }

    public void beginWorldRender() {
        if (disabled.get() || closed.get()) {
            return;
        }
        if (!initialized.get()) {
            initializeOnRenderThread();
        }
        if (!isBackendReady()) {
            return;
        }

        drainChunkQueue();
        backend.buildFrameSnapshots(0);
        stagedGeometryUploaded = false;
        frameOpen = true;
    }

    public void endWorldRender() {
        frameOpen = false;
    }

    public void onSodiumTerrainPass() {
        if (!initialized.get() && !disabled.get() && !closed.get()) {
            initializeOnRenderThread();
        }
    }

    public void submitChunk(long sectionKey, ChunkDraw draw) {
        if (!closed.get()) {
            pendingChunks.offer(new ChunkUpdate(sectionKey, draw));
        }
    }

    public void removeChunk(long sectionKey) {
        if (!closed.get()) {
            pendingChunks.offer(new ChunkUpdate(sectionKey, null));
        }
    }

    /**
     * Draws the compatibility VAO with the ordinary OpenGL 3.3 indexed path.
     */
    public boolean flushInstancedTerrain() {
        return flushInstancedTerrain(false, 0.0f);
    }

    /**
     * Draws one terrain pass and explicitly supplies the alpha-test state.
     * SOLID uses {@code cutoutPass = false} and an alpha cutoff of {@code 0.0f}.
     */
    public boolean flushInstancedTerrain(boolean cutoutPass, float alphaCutoff) {
        if (!frameOpen || !isBackendReady() || frameChunkCount == 0) {
            return false;
        }

        if (!stagedGeometryUploaded) {
            backend.uploadStagedGeometry();
            stagedGeometryUploaded = true;
        }
        terrainProgram.use();
        GL30.glBindVertexArray(backend.getGlobalVAO());
        GL11.glDrawElements(GL11.GL_TRIANGLES, frameChunkCount * 6, GL11.GL_UNSIGNED_INT, 0L);
        GL30.glBindVertexArray(0);
        return true;
    }

    public boolean isBackendReady() {
        return initialized.get() && !disabled.get() && !closed.get()
                && backend != null && terrainProgram != null;
    }

    public boolean supportsShaderDrawParameters() {
        return false;
    }

    private void initializeOnRenderThread() {
        if (!RenderSystem.isOnRenderThread() || !initialized.compareAndSet(false, true)) {
            return;
        }

        try {
            String renderer = safeString(GL11.GL_RENDERER);
            renderPath = detectRenderPath(renderer);
            if (renderPath != RenderPath.INTEL_XE_UHD_COMPATIBILITY
                    && renderPath != RenderPath.INTEL_IRIS_XE_ADVANCED
                    && renderPath != RenderPath.INTEL_ARC_ULTRA) {
                LOGGER.info("Intellium disabled for render path {} (GPU: {})", renderPath, renderer);
                initialized.set(false);
                disabled.set(true);
                return;
            }

            backend = IntelTerrainBackend.getInstance();
            if (!backend.initialize(renderer)) {
                throw new IllegalStateException("Unable to initialize OpenGL 3.3 terrain buffers");
            }
            terrainProgram = new IntelTerrainProgram();
            if (!terrainProgram.verifyCompactChunkVertexLayout()) {
                throw new IllegalStateException("OpenGL 3.3 terrain shader validation failed");
            }
            LOGGER.info("Intellium initialized for {} using the {} path.", renderer, renderPath);
        } catch (RuntimeException exception) {
            initialized.set(false);
            disabled.set(true);
            renderPath = RenderPath.DISABLED;
            closeResources();
            LOGGER.warn("Intellium GPU path disabled; vanilla renderer remains active.", exception);
        }
    }

    private void drainChunkQueue() {
        ChunkUpdate update;
        while ((update = pendingChunks.poll()) != null) {
            if (update.draw == null) {
                activeChunks.remove(update.sectionKey);
            } else {
                activeChunks.put(update.sectionKey, update.draw);
            }
        }
        frameChunkCount = Math.min(activeChunks.size(), MAX_CHUNKS);
    }

    private static String safeString(int name) {
        String value = GL11.glGetString(name);
        return value == null ? "unknown" : value;
    }

    private static RenderPath detectRenderPath(String renderer) {
        String identity = renderer.toLowerCase(Locale.ROOT);

        if (identity.contains("arc")) {
            return RenderPath.INTEL_ARC_ULTRA;
        }
        if (identity.contains("intel")
                && (identity.contains("g4")
                || identity.contains("1115g4")
                || identity.contains("xe")
                || identity.contains("770")
                || identity.contains("uhd graphics"))) {
            return RenderPath.INTEL_XE_UHD_COMPATIBILITY;
        }
        if (identity.contains("intel")
                && (identity.contains("620")
                || identity.contains("630")
                || (identity.contains("hd graphics") && !identity.contains("uhd graphics"))
                || identity.contains("600")
                || identity.contains("730")
                || identity.contains("750"))) {
            return RenderPath.INTEL_LEGACY_UHD;
        }
        if (identity.contains("iris")) {
            return RenderPath.INTEL_IRIS_XE_ADVANCED;
        }
        return RenderPath.DISABLED;
    }

    private void closeResources() {
        if (terrainProgram != null) {
            terrainProgram.close();
            terrainProgram = null;
        }
        if (backend != null) {
            backend.close();
            backend = null;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pendingChunks.clear();
        activeChunks.clear();
        closeResources();
        frameOpen = false;
        stagedGeometryUploaded = false;
        renderPath = RenderPath.DISABLED;
    }

    public record ChunkDraw(float x, float y, float z, int indexCount, int firstIndex, int baseVertex) {
    }

    private record ChunkUpdate(long sectionKey, ChunkDraw draw) {
    }
}
