package dev.villani.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AllocationProbe")
class AllocationProbeTest {

    @Test
    @DisplayName("given a body that only touches a direct buffer, when asserting zero allocation, then it passes")
    void givenDirectBufferOnlyBody_whenAssertingZeroAlloc_thenPasses() {
        // given
        final ByteBuffer buffer = ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());
        final long[] sink = new long[1];
        final Runnable body = () -> {
            for (int i = 0; i < 512; i++) {
                buffer.putLong(i * 8, i);
            }
            sink[0] += buffer.getLong(8);
        };

        // when / then
        assertDoesNotThrow(() -> AllocationProbe.assertZeroAlloc(body, 20_000, 1_000));
    }

    @Test
    @DisplayName("given a body that allocates, when measuring, then the allocation is detected and the assertion fails")
    void givenAllocatingBody_whenMeasuring_thenAllocationDetected() {
        // given
        final Object[] sink = new Object[1];
        final Runnable body = () -> sink[0] = new byte[256];

        // when
        final long bytes = AllocationProbe.bytesAllocated(body, 1_000, 1_000);

        // then
        assertTrue(bytes >= 256 * 1_000);
        assertThrows(AssertionError.class, () -> AllocationProbe.assertZeroAlloc(body, 1_000, 1_000));
    }
}
