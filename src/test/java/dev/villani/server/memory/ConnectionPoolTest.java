package dev.villani.server.memory;

import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConnectionPool")
class ConnectionPoolTest {

    @Nested
    @DisplayName("acquire")
    class Acquire {

        @Test
        @DisplayName("given a pool with free slots, when acquiring every slot, then each slot is distinct and in range")
        void givenFreeSlots_whenAcquiringAll_thenSlotsAreDistinct() {
            // given
            final ConnectionPool pool = new ConnectionPool(4, 64, 64);
            final boolean[] seen = new boolean[4];

            // when
            for (int i = 0; i < 4; i++) {
                final int slot = pool.acquire();

                // then
                assertTrue(slot >= 0 && slot < 4);
                assertFalse(seen[slot], "slot handed out twice: " + slot);
                seen[slot] = true;
            }
            assertEquals(4, pool.active());
        }

        @Test
        @DisplayName("given an exhausted pool, when acquiring again, then NONE is returned")
        void givenExhaustedPool_whenAcquiring_thenReturnsNone() {
            // given
            final ConnectionPool pool = new ConnectionPool(2, 8, 8);
            pool.acquire();
            pool.acquire();

            // when
            final int slot = pool.acquire();

            // then
            assertEquals(ConnectionPool.NONE, slot);
        }

        @Test
        @DisplayName("given a released slot that was written to, when re-acquired, then its buffers are cleared")
        void givenReleasedDirtySlot_whenReacquired_thenBuffersAreCleared() {
            // given
            final ConnectionPool pool = new ConnectionPool(1, 16, 16);
            final int slot = pool.acquire();
            pool.readBuffer(slot).put((byte) 7).put((byte) 8);
            pool.release(slot);

            // when
            final int again = pool.acquire();

            // then
            assertEquals(slot, again);
            assertEquals(0, pool.readBuffer(again).position());
            assertEquals(16, pool.readBuffer(again).limit());
        }
    }

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("given slots released in some order, when acquiring again, then they come back in FIFO order")
        void givenSlotsReleased_whenAcquiring_thenFifoOrder() {
            // given
            final ConnectionPool pool = new ConnectionPool(3, 8, 8);
            final int a = pool.acquire();
            final int b = pool.acquire();
            final int c = pool.acquire();
            pool.release(b);
            pool.release(a);
            pool.release(c);

            // when / then
            assertEquals(b, pool.acquire());
            assertEquals(a, pool.acquire());
            assertEquals(c, pool.acquire());
        }

        @Test
        @DisplayName("given a slot already released, when releasing it again, then it is rejected")
        void givenReleasedSlot_whenReleasingAgain_thenRejected() {
            // given
            final ConnectionPool pool = new ConnectionPool(2, 8, 8);
            final int slot = pool.acquire();
            pool.release(slot);

            // when / then
            assertThrows(IllegalStateException.class, () -> pool.release(slot));
            assertEquals(0, pool.active());
        }

        @Test
        @DisplayName("given an index outside the pool, when releasing it, then it is rejected")
        void givenInvalidIndex_whenReleasing_thenRejected() {
            // given
            final ConnectionPool pool = new ConnectionPool(2, 8, 8);

            // when / then
            assertThrows(IllegalStateException.class, () -> pool.release(99));
            assertThrows(IllegalStateException.class, () -> pool.release(-1));
        }
    }

    @Nested
    @DisplayName("memory layout")
    class MemoryLayout {

        @Test
        @DisplayName("given distinct bytes written to every buffer, when reading them back, then slots do not overlap")
        void givenWritesToEveryBuffer_whenReadingBack_thenNoOverlap() {
            // given
            final ConnectionPool pool = new ConnectionPool(3, 8, 8);
            final int[] slots = {pool.acquire(), pool.acquire(), pool.acquire()};

            // when
            for (int i = 0; i < 3; i++) {
                fill(pool.readBuffer(slots[i]), (byte) (10 + i));
                fill(pool.writeBuffer(slots[i]), (byte) (20 + i));
            }

            // then
            for (int i = 0; i < 3; i++) {
                assertFilledWith(pool.readBuffer(slots[i]), (byte) (10 + i));
                assertFilledWith(pool.writeBuffer(slots[i]), (byte) (20 + i));
            }
        }

        @Test
        @DisplayName("given invalid sizes, when creating a pool, then it is rejected")
        void givenInvalidSizes_whenCreating_thenRejected() {
            // when / then
            assertThrows(IllegalArgumentException.class, () -> new ConnectionPool(0, 8, 8));
            assertThrows(IllegalArgumentException.class, () -> new ConnectionPool(1, 0, 8));
            assertThrows(IllegalArgumentException.class, () -> new ConnectionPool(1, 8, 0));
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given a warmed-up pool, when acquiring and releasing all slots repeatedly, then nothing is allocated")
        void givenWarmPool_whenAcquireReleaseCycles_thenZeroAllocation() {
            // given
            final ConnectionPool pool = new ConnectionPool(64, 4096, 4096);
            final int[] held = new int[64];
            final Runnable cycle = () -> {
                for (int i = 0; i < 64; i++) {
                    held[i] = pool.acquire();
                    pool.readBuffer(held[i]).put((byte) 1);
                }
                for (int i = 0; i < 64; i++) {
                    pool.release(held[i]);
                }
            };

            // when / then
            AllocationProbe.assertZeroAlloc(cycle, 20_000, 2_000);
        }
    }

    private static void fill(ByteBuffer buffer, byte value) {
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(i, value);
        }
    }

    private static void assertFilledWith(ByteBuffer buffer, byte value) {
        for (int i = 0; i < buffer.capacity(); i++) {
            assertEquals(value, buffer.get(i));
        }
    }
}
