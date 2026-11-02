package dev.villani.flat;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationProbeTest {

    @Test
    void directBufferLoopAllocatesNothing() {
        final ByteBuffer buf = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());
        final long[] sink = new long[1];
        final Runnable body = () -> {
            for (int i = 0; i < 512; i++) {
                buf.putLong(i * 8, i);
            }
            sink[0] += buf.getLong(8);
        };
        assertDoesNotThrow(() -> AllocationProbe.assertZeroAlloc(body, 20_000, 1_000));
    }

    @Test
    void detectsAllocation() {
        final Object[] sink = new Object[1];
        final Runnable body = () -> sink[0] = new byte[256];
        assertTrue(AllocationProbe.bytesAllocated(body, 1_000, 1_000) >= 256 * 1_000);
        assertThrows(AssertionError.class, () -> AllocationProbe.assertZeroAlloc(body, 1_000, 1_000));
    }
}
