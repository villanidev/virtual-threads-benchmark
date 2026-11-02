package dev.villani.server.memory;

import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IntRing")
class IntRingTest {

    @Test
    @DisplayName("given a full ring refilled over many rounds, when polling, then values come out FIFO across wrap-around")
    void givenRingFilledRepeatedly_whenPolling_thenFifoAcrossWrapAround() {
        // given
        final IntRing ring = new IntRing(4);

        for (int round = 0; round < 10; round++) {
            // when
            for (int i = 0; i < 4; i++) {
                assertTrue(ring.offer(round * 10 + i));
            }

            // then
            assertFalse(ring.offer(99), "ring should be full");
            for (int i = 0; i < 4; i++) {
                assertEquals(round * 10 + i, ring.poll());
            }
            assertEquals(-1, ring.poll(), "ring should be empty");
        }
    }

    @Test
    @DisplayName("given a requested capacity, when creating the ring, then it is rounded up to a power of two")
    void givenRequestedCapacity_whenCreating_thenRoundedUpToPowerOfTwo() {
        // when / then
        assertEquals(8, new IntRing(5).capacity());
        assertEquals(8, new IntRing(8).capacity());
        assertEquals(2, new IntRing(1).capacity());
    }

    @Test
    @DisplayName("given a warmed-up ring, when offering and polling repeatedly, then nothing is allocated")
    void givenWarmRing_whenOfferPollCycles_thenZeroAllocation() {
        // given
        final IntRing ring = new IntRing(128);
        final int[] sink = new int[1];
        final Runnable cycle = () -> {
            for (int i = 0; i < 100; i++) {
                ring.offer(i);
            }
            for (int i = 0; i < 100; i++) {
                sink[0] += ring.poll();
            }
        };

        // when / then
        AllocationProbe.assertZeroAlloc(cycle, 20_000, 2_000);
    }
}
