package dev.villani.server.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Fixed-capacity FIFO ring of ints stored off-heap. Single-threaded (event loop only), no per-op allocation.
 * Capacity is rounded up to a power of two so wrap-around is a mask.
 */
public final class IntRing {

    /**
     * A capacidade é arredondada para potência de 2, então a volta é uma máscara.
     * poll() devolve -1 quando vazio, por isso os valores precisam ser >= 0.
     * É single-thread, sem atomics
     */

    private final ByteBuffer data;
    private final int mask;
    private int head;
    private int size;

    public IntRing(int minCapacity) {
        int cap = Integer.highestOneBit(Math.max(2, minCapacity) - 1) << 1;
        this.mask = cap - 1;
        this.data = ByteBuffer.allocateDirect(cap * Integer.BYTES).order(ByteOrder.nativeOrder());
    }

    public int capacity() {
        return mask + 1;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean isFull() {
        return size == mask + 1;
    }

    /** @return false if the ring is full */
    public boolean offer(int value) {
        if (size == mask + 1) {
            return false;
        }
        data.putInt(((head + size) & mask) << 2, value);
        size++;
        return true;
    }

    /** @return the oldest value, or -1 if empty (values must be >= 0) */
    public int poll() {
        if (size == 0) {
            return -1;
        }
        final int v = data.getInt(head << 2);
        head = (head + 1) & mask;
        size--;
        return v;
    }
}
