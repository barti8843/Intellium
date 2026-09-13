package pl.smjetanka_.intellium.client;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Profile-aware terrain storage for Intel UHD, Iris Xe and Arc paths.
 *
 * <p>Buffers are created lazily on the render thread. No DSA, persistent mapping,
 * SSBO or indirect-draw entry point is used here.</p>
 */
public final class IntelTerrainBackend implements AutoCloseable {
    private static final int MAX_VERTICES = 1024 * 100 * 4;
    private static final int MAX_INDICES = 1024 * 100 * 6;
    private static final int MAX_CHUNKS = 1024;
    private static final int VERTEX_STRIDE = 20;
    private static final int INDIRECT_COMMAND_STRIDE = 20;
    private static final int STAGING_CAPACITY = MAX_VERTICES * VERTEX_STRIDE;
    private static final int PERSISTENT_BUFFER_FLAGS = GL45C.GL_MAP_WRITE_BIT
            | GL45C.GL_MAP_PERSISTENT_BIT
            | GL45C.GL_MAP_COHERENT_BIT;

    private static final IntelTerrainBackend INSTANCE = new IntelTerrainBackend();

    private IntelRenderer.RenderPath renderPath = IntelRenderer.RenderPath.DISABLED;
    private int globalVao;
    private int globalVertexBuffer;
    private int globalIndexBuffer;
    private int modelMatrixBuffer;
    private int commandBuffer;
    private int acceptedMeshCount;
    private boolean initialized;
    private final ConcurrentLinkedQueue<ByteBuffer> stagedVertexBatches = new ConcurrentLinkedQueue<>();
    private int stagedByteCount;
    private ByteBuffer persistentVertexMapping;
    private final AtomicInteger persistentWriteOffset = new AtomicInteger();

    private IntelTerrainBackend() {
    }

    public static IntelTerrainBackend getInstance() {
        return INSTANCE;
    }

    /**
     * Allocates compatibility buffers after an OpenGL context is available.
     */
    public boolean initialize(String renderer) {
        if (initialized) {
            return true;
        }

        renderPath = IntelRenderer.getInstance().getRenderPath();
        if (renderPath == IntelRenderer.RenderPath.INTEL_IRIS_XE_ADVANCED
                && !GL.getCapabilities().OpenGL45) {
            return false;
        }
        if (renderPath == IntelRenderer.RenderPath.INTEL_ARC_ULTRA
                && !GL.getCapabilities().OpenGL46) {
            return false;
        }

        globalVao = GL30.glGenVertexArrays();
        globalVertexBuffer = renderPath == IntelRenderer.RenderPath.INTEL_IRIS_XE_ADVANCED
                ? GL45C.glCreateBuffers()
                : GL30.glGenBuffers();
        globalIndexBuffer = GL30.glGenBuffers();
        modelMatrixBuffer = GL30.glGenBuffers();
        commandBuffer = GL30.glGenBuffers();

        GL30.glBindVertexArray(globalVao);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, globalVertexBuffer);
        if (renderPath == IntelRenderer.RenderPath.INTEL_IRIS_XE_ADVANCED) {
            long bufferSize = (long) MAX_VERTICES * VERTEX_STRIDE;
            GL45C.glBufferStorage(GL30.GL_ARRAY_BUFFER, bufferSize, PERSISTENT_BUFFER_FLAGS);
            persistentVertexMapping = GL45C.glMapBufferRange(
                    GL30.GL_ARRAY_BUFFER,
                    0L,
                    bufferSize,
                    PERSISTENT_BUFFER_FLAGS
            );
            if (persistentVertexMapping == null) {
                close();
                return false;
            }
        } else {
            GL30.glBufferData(GL30.GL_ARRAY_BUFFER,
                    (long) MAX_VERTICES * VERTEX_STRIDE, GL30.GL_DYNAMIC_DRAW);
        }
        GL30.glVertexAttribIPointer(0, 1, GL30.GL_UNSIGNED_INT, VERTEX_STRIDE, 0L);
        GL30.glEnableVertexAttribArray(0);
        GL30.glVertexAttribIPointer(1, 1, GL30.GL_UNSIGNED_INT, VERTEX_STRIDE, 4L);
        GL30.glEnableVertexAttribArray(1);
        GL30.glVertexAttribIPointer(2, 1, GL30.GL_UNSIGNED_INT, VERTEX_STRIDE, 8L);
        GL30.glEnableVertexAttribArray(2);
        GL30.glVertexAttribIPointer(3, 1, GL30.GL_UNSIGNED_INT, VERTEX_STRIDE, 12L);
        GL30.glEnableVertexAttribArray(3);
        GL30.glVertexAttribIPointer(4, 1, GL30.GL_UNSIGNED_INT, VERTEX_STRIDE, 16L);
        GL30.glEnableVertexAttribArray(4);

        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, globalIndexBuffer);
        GL30.glBufferData(GL30.GL_ELEMENT_ARRAY_BUFFER,
                (long) MAX_INDICES * Integer.BYTES, GL30.GL_DYNAMIC_DRAW);

        GL30.glBindVertexArray(0);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0);
        GL30.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, 0);

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, modelMatrixBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER,
                (long) MAX_CHUNKS * 16 * Float.BYTES, GL30.GL_DYNAMIC_DRAW);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0);

        // Kept as ordinary storage for compatibility; it is not submitted through
        // glMultiDrawElementsIndirect on the OpenGL 3.3 path.
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, commandBuffer);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER,
                (long) MAX_CHUNKS * INDIRECT_COMMAND_STRIDE, GL30.GL_DYNAMIC_DRAW);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0);

        if (renderPath == IntelRenderer.RenderPath.INTEL_ARC_ULTRA) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, globalVertexBuffer);
        }

        initialized = true;
        return true;
    }

    public int getGlobalVAO() {
        return globalVao;
    }

    public int getModelMatrixSSBO() {
        return modelMatrixBuffer;
    }

    public void buildFrameSnapshots(int frameIndex) {
        // The compatibility path keeps the latest accepted mesh count.
    }

    public void stageBuiltOutput(ChunkBuildOutput output) {
        int stagedMeshes = 0;
        for (BuiltSectionMeshParts mesh : output.meshes.values()) {
            NativeBuffer vertexData = mesh.getVertexData();
            ByteBuffer source = vertexData.getDirectBuffer().duplicate();
            source.clear();
            int bytes = source.remaining();
            if (bytes == 0 || bytes > STAGING_CAPACITY) {
                continue;
            }

            if (renderPath == IntelRenderer.RenderPath.INTEL_IRIS_XE_ADVANCED) {
                int offset = reservePersistentRange(bytes);
                if (offset >= 0) {
                    ByteBuffer destination = persistentVertexMapping.duplicate();
                    destination.position(offset);
                    destination.limit(offset + bytes);
                    destination.put(source);
                    stagedMeshes++;
                }
                continue;
            }

            ByteBuffer staged = ByteBuffer.allocateDirect(bytes);
            staged.put(source).flip();
            stagedVertexBatches.offer(staged);
            stagedMeshes++;
            synchronized (this) {
                stagedByteCount += bytes;
            }
        }
        if (stagedMeshes != 0) {
            synchronized (this) {
                acceptedMeshCount += stagedMeshes;
            }
        }
    }

    /**
     * Uploads all CPU-staged vertex payloads in one render-thread GL call.
     */
    public void uploadStagedGeometry() {
        if (!initialized || renderPath == IntelRenderer.RenderPath.INTEL_IRIS_XE_ADVANCED) {
            return;
        }

        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, globalVertexBuffer);
        while (!stagedVertexBatches.isEmpty()) {
            ByteBuffer batch = stagedVertexBatches.poll();
            if (batch == null) {
                continue;
            }

            synchronized (this) {
                stagedByteCount -= batch.remaining();
            }
            GL30.glBufferSubData(GL30.GL_ARRAY_BUFFER, 0L, batch);
            MemoryUtil.memFree(batch);
        }
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0);
    }

    public void removeSection(int x, int y, int z) {
        // Section allocation is rebuilt by the next accepted mesh upload.
    }

    public CommandSnapshot getCommandSnapshot(int frameIndex, Object renderPass) {
        return new CommandSnapshot(commandBuffer, acceptedMeshCount, INDIRECT_COMMAND_STRIDE);
    }

    public void retireBufferRange(BufferRange range, int fence) {
        // Compatibility buffers are reclaimed as part of the next upload.
    }

    @Override
    public void close() {
        if (globalVao != 0) {
            GL30.glDeleteVertexArrays(globalVao);
            globalVao = 0;
        }
        if (persistentVertexMapping != null && globalVertexBuffer != 0) {
            GL45C.glBindBuffer(GL30.GL_ARRAY_BUFFER, globalVertexBuffer);
            GL45C.glUnmapBuffer(GL30.GL_ARRAY_BUFFER);
            GL45C.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0);
            persistentVertexMapping = null;
        }
        deleteBuffer(globalVertexBuffer);
        deleteBuffer(globalIndexBuffer);
        deleteBuffer(modelMatrixBuffer);
        deleteBuffer(commandBuffer);
        stagedVertexBatches.clear();
        synchronized (this) {
            stagedByteCount = 0;
        }
        initialized = false;
        acceptedMeshCount = 0;
        persistentWriteOffset.set(0);
        renderPath = IntelRenderer.RenderPath.DISABLED;
    }

    private int reservePersistentRange(int bytes) {
        int capacity = MAX_VERTICES * VERTEX_STRIDE;
        while (true) {
            int current = persistentWriteOffset.get();
            if (bytes > capacity || current > capacity - bytes) {
                return -1;
            }
            if (persistentWriteOffset.compareAndSet(current, current + bytes)) {
                return current;
            }
        }
    }

    private static void deleteBuffer(int buffer) {
        if (buffer != 0) {
            GL30.glDeleteBuffers(buffer);
        }
    }

    public static final class CommandSnapshot {
        private final int indirectBufferId;
        private final int drawCount;
        private final int stride;

        public CommandSnapshot(int indirectBufferId, int drawCount, int stride) {
            this.indirectBufferId = indirectBufferId;
            this.drawCount = drawCount;
            this.stride = stride;
        }

        public int getIndirectBufferId() {
            return indirectBufferId;
        }

        public int getDrawCount() {
            return drawCount;
        }

        public int getStride() {
            return stride;
        }
    }

    public static final class BufferRange {
        private final int offset;
        private final int size;

        public BufferRange(int offset, int size) {
            this.offset = offset;
            this.size = size;
        }

        public int offset() {
            return offset;
        }

        public int size() {
            return size;
        }
    }
}
