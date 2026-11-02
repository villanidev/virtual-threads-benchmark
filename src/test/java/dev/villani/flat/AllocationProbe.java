package dev.villani.flat;

import java.lang.management.ManagementFactory;

/**
 * Measures heap bytes allocated by the current thread while running a body in steady state.
 * The body must be a pre-created Runnable (a capturing lambda created inside the loop would itself allocate).
 */
public final class AllocationProbe {

    private static final com.sun.management.ThreadMXBean TMX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private AllocationProbe() {
    }

    /** Bytes allocated by the current thread across {@code iterations} runs, after {@code warmup} runs. */
    public static long bytesAllocated(Runnable body, int warmup, int iterations) {
        for (int i = 0; i < warmup; i++) {
            body.run();
        }
        final long overhead = measureEmpty();
        final long before = TMX.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            body.run();
        }
        final long after = TMX.getCurrentThreadAllocatedBytes();
        return Math.max(0, after - before - overhead);
    }

    public static void assertZeroAlloc(Runnable body, int warmup, int iterations) {
        final long bytes = bytesAllocated(body, warmup, iterations);
        if (bytes != 0) {
            throw new AssertionError("expected zero allocation but thread allocated " + bytes
                    + " bytes over " + iterations + " iterations");
        }
    }

    private static long measureEmpty() {
        TMX.getCurrentThreadAllocatedBytes();
        final long a = TMX.getCurrentThreadAllocatedBytes();
        final long b = TMX.getCurrentThreadAllocatedBytes();
        return b - a;
    }
}
