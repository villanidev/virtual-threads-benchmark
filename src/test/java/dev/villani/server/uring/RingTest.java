package dev.villani.server.uring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Ring (io_uring)")
class RingTest {

    private final Arena arena = Arena.ofShared();
    private Ring ring;

    @AfterEach
    void tearDown() {
        if (ring != null) {
            ring.close();
        }
        arena.close();
    }

    private Ring newRing(int entries) {
        ring = new Ring(new Sys(arena), arena, entries);
        return ring;
    }

    @Test
    @DisplayName("given a NOP submitted, when waiting, then a completion carries the same user_data and res 0")
    void givenNop_whenWaiting_thenCompletionRoundTripsUserData() {
        // given
        newRing(8).prepNop(0xCAFEL);

        // when
        ring.submitAndWait(1_000_000_000L);

        // then
        assertTrue(ring.cqePeek());
        assertEquals(0xCAFEL, ring.cqeUserData());
        assertEquals(0, ring.cqeRes());
        ring.cqeDone();
        assertFalse(ring.cqePeek());
    }

    @Test
    @DisplayName("given nothing submitted, when waiting with a timeout, then it returns without a completion")
    void givenNothingSubmitted_whenWaitingWithTimeout_thenNoCompletion() {
        // given
        newRing(8);
        final long start = System.nanoTime();

        // when
        ring.submitAndWait(20_000_000L);

        // then
        assertFalse(ring.cqePeek());
        assertTrue(System.nanoTime() - start >= 15_000_000L, "should have blocked until the timeout");
    }

    @Test
    @DisplayName("given several NOPs, when reaped, then every user_data is delivered exactly once")
    void givenManyNops_whenReaped_thenEachDeliveredOnce() {
        // given
        newRing(8);
        for (int i = 0; i < 8; i++) {
            ring.prepNop(100 + i);
        }

        // when
        ring.submitAndWait(1_000_000_000L);
        final Set<Long> seen = new HashSet<>();
        final long deadline = System.nanoTime() + 1_000_000_000L;
        while (seen.size() < 8 && System.nanoTime() < deadline) {
            while (ring.cqePeek()) {
                seen.add(ring.cqeUserData());
                ring.cqeDone();
            }
        }

        // then
        assertEquals(8, seen.size());
    }

    @Test
    @DisplayName("given the ring reused many times past its size, when cycling submit and reap, then indices wrap correctly")
    void givenRingCycledPastItsSize_whenSubmittingAndReaping_thenWrapsCorrectly() {
        // given
        newRing(4);

        // when / then
        for (long i = 0; i < 100; i++) {
            ring.prepNop(i);
            ring.submitAndWait(1_000_000_000L);
            assertTrue(ring.cqePeek(), "completion " + i);
            assertEquals(i, ring.cqeUserData());
            ring.cqeDone();
        }
    }
}
