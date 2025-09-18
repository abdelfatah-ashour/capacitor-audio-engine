package com.capacitor.audioengine;

import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread-safe pool for ByteBuffer objects to reduce memory allocation overhead
 * during audio segment processing operations.
 */
public class ByteBufferPool {
    private static final String TAG = "ByteBufferPool";
    private static final int DEFAULT_POOL_SIZE = 10;
    private static final int MAX_POOL_SIZE = 20;

    private final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();
    private final AtomicInteger poolSize = new AtomicInteger(0);
    private final AtomicInteger createdBuffers = new AtomicInteger(0);
    private final AtomicInteger reusedBuffers = new AtomicInteger(0);
    private final int bufferCapacity;

    public ByteBufferPool(int bufferCapacity) {
        this.bufferCapacity = bufferCapacity;

        // Pre-allocate some buffers
        for (int i = 0; i < DEFAULT_POOL_SIZE; i++) {
            ByteBuffer buffer = ByteBuffer.allocate(bufferCapacity);
            pool.offer(buffer);
            poolSize.incrementAndGet();
        }

        Log.d(TAG, "ByteBufferPool created with capacity=" + bufferCapacity +
              ", initial pool size=" + DEFAULT_POOL_SIZE);
    }

    /**
     * Get a ByteBuffer from the pool or create a new one if pool is empty
     * @return A ByteBuffer ready for use (cleared and positioned at 0)
     */
    public ByteBuffer acquire() {
        ByteBuffer buffer = pool.poll();

        if (buffer != null) {
            poolSize.decrementAndGet();
            reusedBuffers.incrementAndGet();
            buffer.clear(); // Reset position and limit
            return buffer;
        } else {
            // Pool is empty, create new buffer
            createdBuffers.incrementAndGet();
            ByteBuffer newBuffer = ByteBuffer.allocate(bufferCapacity);
            Log.d(TAG, "Created new buffer (pool empty), total created: " + createdBuffers.get());
            return newBuffer;
        }
    }

    /**
     * Return a ByteBuffer to the pool for reuse
     * @param buffer The buffer to return (must not be null)
     */
    public void release(ByteBuffer buffer) {
        if (buffer == null) {
            Log.w(TAG, "Attempted to release null buffer");
            return;
        }

        if (buffer.capacity() != bufferCapacity) {
            Log.w(TAG, "Buffer capacity mismatch: expected=" + bufferCapacity +
                  ", actual=" + buffer.capacity() + " - not returning to pool");
            return;
        }

        // Don't let pool grow too large
        if (poolSize.get() >= MAX_POOL_SIZE) {
            Log.d(TAG, "Pool at max capacity (" + MAX_POOL_SIZE + "), discarding buffer");
            return;
        }

        buffer.clear(); // Reset for next use
        if (pool.offer(buffer)) {
            poolSize.incrementAndGet();
        }
    }

    /**
     * Get pool statistics for monitoring
     * @return Debug string with pool status
     */
    public String getStats() {
        return "ByteBufferPool{" +
               "capacity=" + bufferCapacity +
               ", poolSize=" + poolSize.get() +
               ", created=" + createdBuffers.get() +
               ", reused=" + reusedBuffers.get() +
               ", efficiency=" + String.format("%.1f%%", getEfficiency()) +
               "}";
    }

    /**
     * Calculate buffer reuse efficiency
     * @return Percentage of buffer operations that used pooled buffers
     */
    public double getEfficiency() {
        int total = createdBuffers.get() + reusedBuffers.get();
        if (total == 0) return 0.0;
        return (double) reusedBuffers.get() / total * 100.0;
    }

    /**
     * Clear all buffers from the pool
     */
    public void clear() {
        pool.clear();
        poolSize.set(0);
        Log.d(TAG, "Pool cleared");
    }

    /**
     * Get current pool size
     * @return Number of buffers currently in the pool
     */
    public int getPoolSize() {
        return poolSize.get();
    }

    /**
     * Get the buffer capacity for buffers in this pool
     * @return Buffer capacity in bytes
     */
    public int getBufferCapacity() {
        return bufferCapacity;
    }
}