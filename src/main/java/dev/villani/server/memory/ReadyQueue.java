package dev.villani.server.memory;

/**
 * FIFO of slots that are ready to be processed. A slot appears at most once: marking a slot that is already
 * queued is a no-op, so repeated readiness notifications from the selector never overflow the queue.
 * Capacity equals the number of slots, so the underlying ring can never be full. Single-threaded, no allocation.
 */
public final class ReadyQueue {

    /**
     * fila FIFO de slots prontos sobre o IntRing, com um flag por slot. markReady é idempotente, porque o selector 
     * pode notificar o mesmo slot várias vezes. A fila nunca estoura, já que a capacidade é o número de slots
     */

    public static final int NONE = -1;

    private final IntRing ring;
    private final Region queued;
    private final int slots;

    public ReadyQueue(int slots) {
        if (slots <= 0) {
            throw new IllegalArgumentException("slots must be > 0");
        }
        this.slots = slots;
        this.ring = new IntRing(slots);
        this.queued = Regions.allocate(slots);
    }

    /** @return true if the slot was enqueued, false if it was already queued */
    public boolean markReady(int slot) {
        if (slot < 0 || slot >= slots) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        if (queued.get(slot) != 0) {
            return false;
        }
        queued.put(slot, (byte) 1);
        ring.offer(slot);
        return true;
    }

    /** @return the oldest ready slot, or NONE if there is none */
    public int next() {
        final int slot = ring.poll();
        if (slot >= 0) {
            queued.put(slot, (byte) 0);
        }
        return slot;
    }

    public boolean isEmpty() {
        return ring.isEmpty();
    }

    public int size() {
        return ring.size();
    }
}
