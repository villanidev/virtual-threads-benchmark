package dev.villani.benchmark.eventloop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Selector;

/**
 * NIO variant: the loop waits in Selector.select(timeoutMs), the same call a real NIO server
 * uses to wait for sockets, so timers and I/O readiness share one blocking point.
 * Deadlines live in a DirectByteBuffer. Timeout granularity is 1 ms.
 */
public final class NioEventLoop {

    public static long run(final int tasks, final long delayNanos) throws IOException {
        final ByteBuffer deadlines = ByteBuffer.allocateDirect(tasks * Long.BYTES).order(ByteOrder.nativeOrder());
        final long start = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            deadlines.putLong(i * Long.BYTES, start + delayNanos);
        }
        long completed = 0;
        int head = 0;
        try (Selector selector = Selector.open()) {
            while (head < tasks) {
                final long wait = deadlines.getLong(head * Long.BYTES) - System.nanoTime();
                if (wait > 0) {
                    selector.select((wait + 999_999) / 1_000_000);
                } else {
                    completed++;
                    head++;
                }
            }
        }
        return completed;
    }
}
