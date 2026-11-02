package dev.villani;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Semaphore + virtual threads, comparing where the 10 KB per-task buffer lives:
 * heap=true  -> byte[] allocated before acquire (original behaviour, O(N) heap data)
 * heap=false -> one off-heap slab with PERMITS slots; a slot is claimed after acquire via a CAS bitmask
 *               (no per-task data object on the heap).
 */
public final class SemaphoreSlabBench {

    static final int PERMITS = 50;
    static final int SLOT_BYTES = 10 * 1024;

    public static long run(final int tasks, final long sleepMs, final boolean heap) {
        final Semaphore semaphore = new Semaphore(PERMITS);
        final AtomicLong freeMask = new AtomicLong(-1L >>> (64 - PERMITS));
        final LongAdder done = new LongAdder();
        try (Arena arena = Arena.ofShared();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final MemorySegment slab = arena.allocate((long) PERMITS * SLOT_BYTES, 64);
            for (int i = 0; i < tasks; i++) {
                executor.execute(() -> {
                    final byte[] data = heap ? new byte[SLOT_BYTES] : null;
                    semaphore.acquireUninterruptibly();
                    int slot = -1;
                    try {
                        if (data != null) {
                            data[0] = 1;
                        } else {
                            slot = claim(freeMask);
                            slab.set(ValueLayout.JAVA_BYTE, (long) slot * SLOT_BYTES, (byte) 1);
                        }
                        Thread.sleep(Duration.ofMillis(sleepMs));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        if (slot >= 0) release(freeMask, slot);
                        semaphore.release();
                    }
                    done.increment();
                });
            }
        }
        return done.sum();
    }

    private static int claim(AtomicLong mask) {
        while (true) {
            final long m = mask.get();
            final int slot = Long.numberOfTrailingZeros(m);
            if (mask.compareAndSet(m, m & ~(1L << slot))) return slot;
        }
    }

    private static void release(AtomicLong mask, int slot) {
        mask.getAndUpdate(m -> m | (1L << slot));
    }
}
