package pl.smjetanka_.intellium.client.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vulkan terrain-buffer skeleton for the Minecraft 26.1 module.
 *
 * <p>Vulkan object creation remains on the caller's device-setup thread.
 * Worker threads only copy into the persistently mapped host-visible staging
 * allocation while holding the staging lock. Command-buffer submission is
 * intentionally left to the future render graph.</p>
 */
public final class IntelVulkanBackend implements AutoCloseable {
    private static final int NO_ALLOCATION_CALLBACKS = 0;
    private static final long WHOLE_ALLOCATION = VK13.VK_WHOLE_SIZE;

    private final long vertexBufferSize;
    private final int deviceLocalMemoryTypeIndex;
    private final int stagingMemoryTypeIndex;
    private final ExecutorService stagingExecutor;
    private final Object stagingLock = new Object();

    private VkDevice device;
    private long globalVertexBuffer;
    private long globalVertexMemory;
    private long stagingBuffer;
    private long stagingMemory;
    private ByteBuffer mappedStagingMemory;
    private boolean initialized;

    public IntelVulkanBackend(
            long vertexBufferSize,
            int deviceLocalMemoryTypeIndex,
            int stagingMemoryTypeIndex
    ) {
        if (vertexBufferSize <= 0) {
            throw new IllegalArgumentException("vertexBufferSize must be positive");
        }
        this.vertexBufferSize = vertexBufferSize;
        this.deviceLocalMemoryTypeIndex = deviceLocalMemoryTypeIndex;
        this.stagingMemoryTypeIndex = stagingMemoryTypeIndex;
        this.stagingExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Intellium-Vulkan-Staging");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Creates the device-local vertex buffer and its host-visible staging buffer.
     *
     * <p>The memory type indices must be selected from the active physical
     * device's {@code VkPhysicalDeviceMemoryProperties}; they are not portable
     * constants and therefore are supplied by the Vulkan device bootstrap.</p>
     */
    public synchronized void initializeVulkanResources(VkDevice device) {
        if (initialized) {
            return;
        }
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }

        this.device = device;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            globalVertexBuffer = createBuffer(
                    stack,
                    vertexBufferSize,
                    VK13.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
                            | VK13.VK_BUFFER_USAGE_TRANSFER_DST_BIT
            );
            globalVertexMemory = allocateAndBindMemory(
                    stack,
                    globalVertexBuffer,
                    deviceLocalMemoryTypeIndex
            );

            stagingBuffer = createBuffer(
                    stack,
                    vertexBufferSize,
                    VK13.VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            );
            stagingMemory = allocateAndBindMemory(
                    stack,
                    stagingBuffer,
                    stagingMemoryTypeIndex
            );

            PointerBuffer mappedAddress = stack.mallocPointer(1);
            checkResult(
                    VK13.vkMapMemory(
                            device,
                            stagingMemory,
                            0L,
                            WHOLE_ALLOCATION,
                            0,
                            mappedAddress
                    ),
                    "vkMapMemory"
            );
            mappedStagingMemory = MemoryUtil.memByteBuffer(
                    mappedAddress.get(0),
                    Math.toIntExact(vertexBufferSize)
            );
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }

        initialized = true;
    }

    /**
     * Copies geometry asynchronously into the persistently mapped staging
     * allocation. A transfer command must later copy this staging range into
     * {@link #globalVertexBuffer()} on the render/transfer queue.
     */
    public void stageVulkanGeometry(ByteBuffer vertexData) {
        if (vertexData == null) {
            throw new IllegalArgumentException("vertexData must not be null");
        }
        ByteBuffer source = vertexData.duplicate();
        synchronized (this) {
            if (!initialized || mappedStagingMemory == null) {
                throw new IllegalStateException("Vulkan resources are not initialized");
            }
            if (source.remaining() > mappedStagingMemory.capacity()) {
                throw new IllegalArgumentException("vertexData exceeds staging capacity");
            }
        }

        stagingExecutor.execute(() -> {
            synchronized (this) {
                if (!initialized || mappedStagingMemory == null) {
                    return;
                }
                synchronized (stagingLock) {
                    ByteBuffer destination = mappedStagingMemory.duplicate();
                    destination.clear();
                    destination.put(source);
                }
            }
        });
    }

    public long globalVertexBuffer() {
        return globalVertexBuffer;
    }

    @Override
    public synchronized void close() {
        if (device == null) {
            stagingExecutor.shutdownNow();
            return;
        }

        if (mappedStagingMemory != null && stagingMemory != 0L) {
            VK13.vkUnmapMemory(device, stagingMemory);
            mappedStagingMemory = null;
        }
        if (stagingBuffer != 0L) {
            VK13.vkDestroyBuffer(device, stagingBuffer, null);
            stagingBuffer = 0L;
        }
        if (stagingMemory != 0L) {
            VK13.vkFreeMemory(device, stagingMemory, null);
            stagingMemory = 0L;
        }
        if (globalVertexBuffer != 0L) {
            VK13.vkDestroyBuffer(device, globalVertexBuffer, null);
            globalVertexBuffer = 0L;
        }
        if (globalVertexMemory != 0L) {
            VK13.vkFreeMemory(device, globalVertexMemory, null);
            globalVertexMemory = 0L;
        }
        initialized = false;
        device = null;
        stagingExecutor.shutdownNow();
    }

    private long createBuffer(
            MemoryStack stack,
            long size,
            int usage
    ) {
        VkBufferCreateInfo createInfo = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(size)
                .usage(usage)
                .sharingMode(VK13.VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer handle = stack.mallocLong(1);
        checkResult(
                VK13.vkCreateBuffer(device, createInfo, null, handle),
                "vkCreateBuffer"
        );
        return handle.get(0);
    }

    private long allocateAndBindMemory(
            MemoryStack stack,
            long buffer,
            int memoryTypeIndex
    ) {
        VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
        VK13.vkGetBufferMemoryRequirements(device, buffer, requirements);

        VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType$Default()
                .allocationSize(requirements.size())
                .memoryTypeIndex(memoryTypeIndex);
        LongBuffer handle = stack.mallocLong(1);
        checkResult(
                VK13.vkAllocateMemory(device, allocateInfo, null, handle),
                "vkAllocateMemory"
        );

        checkResult(
                VK13.vkBindBufferMemory(device, buffer, handle.get(0), 0L),
                "vkBindBufferMemory"
        );
        return handle.get(0);
    }

    private static void checkResult(int result, String operation) {
        if (result != VK13.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }
}
