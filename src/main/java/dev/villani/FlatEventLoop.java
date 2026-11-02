package dev.villani;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.locks.LockSupport;

/**
 * Thread-per-core: one thread, task state in flat off-heap memory (struct-of-arrays),
 * a task is just an int index. Constant delay => deadlines are monotonic, so a FIFO ring
 * replaces a timer wheel. No per-task objects on the Java heap.
 */
public final class FlatEventLoop {

    public static long run(final int tasks, final long delayNanos) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment deadlines = arena.allocate((long) tasks * Long.BYTES, 64);
            final long start = System.nanoTime();
            for (int i = 0; i < tasks; i++) {
                deadlines.setAtIndex(ValueLayout.JAVA_LONG, i, start + delayNanos);
            }
            long completed = 0;
            int head = 0;
            while (head < tasks) {
                final long wait = deadlines.getAtIndex(ValueLayout.JAVA_LONG, head) - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                } else {
                    completed++;
                    head++;
                }
            }
            return completed;
        }
    }
}
