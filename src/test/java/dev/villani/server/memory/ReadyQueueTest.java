package dev.villani.server.memory;

import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReadyQueue")
class ReadyQueueTest {

    @Test
    @DisplayName("given slots marked ready, when taking the next, then they come out in arrival order")
    void givenSlotsMarked_whenTakingNext_thenArrivalOrder() {
        // given
        final ReadyQueue queue = new ReadyQueue(8);
        queue.markReady(5);
        queue.markReady(2);
        queue.markReady(7);

        // when / then
        assertEquals(5, queue.next());
        assertEquals(2, queue.next());
        assertEquals(7, queue.next());
        assertEquals(ReadyQueue.NONE, queue.next());
    }

    @Test
    @DisplayName("given a slot already queued, when marking it again, then it is not duplicated")
    void givenQueuedSlot_whenMarkingAgain_thenNotDuplicated() {
        // given
        final ReadyQueue queue = new ReadyQueue(4);
        assertTrue(queue.markReady(1));

        // when
        final boolean second = queue.markReady(1);

        // then
        assertFalse(second);
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("given a slot already taken, when marking it again, then it is queued again")
    void givenTakenSlot_whenMarkingAgain_thenQueuedAgain() {
        // given
        final ReadyQueue queue = new ReadyQueue(4);
        queue.markReady(3);
        queue.next();

        // when
        final boolean again = queue.markReady(3);

        // then
        assertTrue(again);
        assertEquals(3, queue.next());
    }

    @Test
    @DisplayName("given every slot marked many times, when taking all, then the queue never overflows")
    void givenEverySlotMarkedRepeatedly_whenTakingAll_thenNoOverflow() {
        // given
        final ReadyQueue queue = new ReadyQueue(4);

        // when
        for (int round = 0; round < 3; round++) {
            for (int slot = 0; slot < 4; slot++) {
                queue.markReady(slot);
            }
        }

        // then
        assertEquals(4, queue.size());
    }

    @Test
    @DisplayName("given a slot out of range, when marking it, then it is rejected")
    void givenSlotOutOfRange_whenMarking_thenRejected() {
        // given
        final ReadyQueue queue = new ReadyQueue(4);

        // when / then
        assertThrows(IllegalArgumentException.class, () -> queue.markReady(4));
        assertThrows(IllegalArgumentException.class, () -> queue.markReady(-1));
    }

    @Test
    @DisplayName("given a warmed-up queue, when marking and taking repeatedly, then nothing is allocated")
    void givenWarmQueue_whenCycling_thenZeroAllocation() {
        // given
        final ReadyQueue queue = new ReadyQueue(64);
        final int[] sink = new int[1];
        final Runnable cycle = () -> {
            for (int i = 0; i < 64; i++) {
                queue.markReady(i);
                queue.markReady(i);
            }
            int slot;
            while ((slot = queue.next()) != ReadyQueue.NONE) {
                sink[0] += slot;
            }
        };

        // when / then
        AllocationProbe.assertZeroAlloc(cycle, 20_000, 2_000);
    }
}
