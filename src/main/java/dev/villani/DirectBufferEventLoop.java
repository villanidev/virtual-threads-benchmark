package dev.villani;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.LockSupport;

/** Same loop as FlatEventLoop, but with the classic DirectByteBuffer (off-heap, no FFM). */
public final class DirectBufferEventLoop {

    public static long run(final int tasks, final long delayNanos) {
        final ByteBuffer deadlines = ByteBuffer.allocateDirect(tasks * Long.BYTES).order(ByteOrder.nativeOrder());
        final long start = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            deadlines.putLong(i * Long.BYTES, start + delayNanos);
        }
        long completed = 0;
        int head = 0;
        while (head < tasks) {
            final long wait = deadlines.getLong(head * Long.BYTES) - System.nanoTime();
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
