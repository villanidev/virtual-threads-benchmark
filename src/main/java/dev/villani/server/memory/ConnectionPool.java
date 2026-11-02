package dev.villani.server.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Object pool of connection slots, fully pre-allocated. A slot is an int index; its read and write buffers are
 * slices of one off-heap slab. The ByteBuffer views are created once at construction (the only allocations),
 * so acquire/release never allocate. Free slots are kept in a FIFO IntRing.
 * Single-threaded: use from the event loop only.
 */
public final class ConnectionPool {

    /**
     * o object pool, com capacity, readBytes e writeBytes fixos na criação.
     * Uma única slab off-heap guarda os buffers de leitura e de escrita de todos os slots.
     * Os ByteBuffer de cada slot são views criadas uma vez no construtor, que é a única alocação.
     * acquire() devolve o índice do slot ou NONE (-1) quando esgota, e é aí que o servidor aplica backpressure.
     * release(slot) levanta IllegalStateException em liberação dupla ou slot inválido.
     * acquire() limpa os buffers do slot, e os slots livres saem em ordem FIFO.
     */

    public static final int NONE = -1;

    private final int capacity;
    private final ByteBuffer slab;
    private final ByteBuffer[] readViews;
    private final ByteBuffer[] writeViews;
    private final ByteBuffer inUse; // 1 byte per slot, off-heap
    private final IntRing free;
    private int active;

    public ConnectionPool(int capacity, int readBytes, int writeBytes) {
        if (capacity <= 0 || readBytes <= 0 || writeBytes <= 0) {
            throw new IllegalArgumentException("capacity and buffer sizes must be > 0");
        }
        this.capacity = capacity;
        final long total = (long) capacity * (readBytes + writeBytes);
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("slab too large: " + total);
        }
        this.slab = ByteBuffer.allocateDirect((int) total).order(ByteOrder.nativeOrder());
        this.readViews = new ByteBuffer[capacity];
        this.writeViews = new ByteBuffer[capacity];
        this.inUse = ByteBuffer.allocateDirect(capacity);
        this.free = new IntRing(capacity);
        final int stride = readBytes + writeBytes;
        for (int i = 0; i < capacity; i++) {
            final int base = i * stride;
            readViews[i] = slab.slice(base, readBytes);
            writeViews[i] = slab.slice(base + readBytes, writeBytes);
            free.offer(i);
        }
    }

    public int capacity() {
        return capacity;
    }

    public int active() {
        return active;
    }

    /** @return a slot index, or NONE when exhausted (caller applies backpressure) */
    public int acquire() {
        final int slot = free.poll();
        if (slot < 0) {
            return NONE;
        }
        inUse.put(slot, (byte) 1);
        readViews[slot].clear();
        writeViews[slot].clear();
        active++;
        return slot;
    }

    public void release(int slot) {
        if (slot < 0 || slot >= capacity || inUse.get(slot) == 0) {
            throw new IllegalStateException("slot not in use: " + slot);
        }
        inUse.put(slot, (byte) 0);
        active--;
        free.offer(slot);
    }

    public boolean isInUse(int slot) {
        return slot >= 0 && slot < capacity && inUse.get(slot) != 0;
    }

    public ByteBuffer readBuffer(int slot) {
        return readViews[slot];
    }

    public ByteBuffer writeBuffer(int slot) {
        return writeViews[slot];
    }
}
