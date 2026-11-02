package dev.villani.server.time;

import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TimerWheel")
class TimerWheelTest {

    private final TimerWheel wheel = new TimerWheel(64, 1, 8, 1_000);

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("given a scheduled timer, when time passes its deadline, then its token is polled once")
        void givenTimer_whenDeadlinePasses_thenTokenPolledOnce() {
            // given
            wheel.schedule(1_000, 20, 7);

            // when
            wheel.advanceTo(1_019);
            final int early = wheel.pollExpired();
            wheel.advanceTo(1_020);
            final int due = wheel.pollExpired();
            final int again = wheel.pollExpired();

            // then
            assertEquals(TimerWheel.NONE, early);
            assertEquals(7, due);
            assertEquals(TimerWheel.NONE, again);
        }

        @Test
        @DisplayName("given several timers, when time jumps past them all, then every token expires")
        void givenSeveralTimers_whenTimeJumps_thenAllExpire() {
            // given
            wheel.schedule(1_000, 5, 1);
            wheel.schedule(1_000, 10, 2);
            wheel.schedule(1_000, 30, 3);

            // when
            wheel.advanceTo(5_000);

            // then
            int sum = 0;
            for (int t = wheel.pollExpired(); t != TimerWheel.NONE; t = wheel.pollExpired()) {
                sum += t;
            }
            assertEquals(6, sum);
            assertEquals(0, wheel.pending());
        }

        @Test
        @DisplayName("given a zero delay, when the next tick arrives, then it expires")
        void givenZeroDelay_whenNextTick_thenExpires() {
            // given
            wheel.schedule(1_000, 0, 9);

            // when
            wheel.advanceTo(1_001);

            // then
            assertEquals(9, wheel.pollExpired());
        }
    }

    @Nested
    @DisplayName("cancelling")
    class Cancelling {

        @Test
        @DisplayName("given a scheduled timer, when cancelled, then it never expires")
        void givenTimer_whenCancelled_thenNeverExpires() {
            // given
            final int e = wheel.schedule(1_000, 10, 4);

            // when
            wheel.cancel(e);
            wheel.advanceTo(2_000);

            // then
            assertEquals(TimerWheel.NONE, wheel.pollExpired());
            assertEquals(0, wheel.pending());
        }

        @Test
        @DisplayName("given an already expired timer, when cancelled before polling, then it is skipped")
        void givenExpiredTimer_whenCancelled_thenSkipped() {
            // given
            final int e = wheel.schedule(1_000, 10, 4);
            wheel.advanceTo(1_010);

            // when
            wheel.cancel(e);

            // then
            assertEquals(TimerWheel.NONE, wheel.pollExpired());
        }

        @Test
        @DisplayName("given timers in the same slot, when cancelling the middle one, then the others still expire")
        void givenSameSlot_whenCancellingMiddle_thenOthersExpire() {
            // given
            wheel.schedule(1_000, 10, 1);
            final int mid = wheel.schedule(1_000, 10, 2);
            wheel.schedule(1_000, 10, 3);

            // when
            wheel.cancel(mid);
            wheel.advanceTo(1_010);

            // then
            int sum = 0;
            for (int t = wheel.pollExpired(); t != TimerWheel.NONE; t = wheel.pollExpired()) {
                sum += t;
            }
            assertEquals(4, sum);
        }
    }

    @Nested
    @DisplayName("limits and hints")
    class Limits {

        @Test
        @DisplayName("given a full wheel, when scheduling, then NONE; a delay beyond the horizon is rejected")
        void givenFullWheel_whenScheduling_thenNone() {
            // given
            for (int i = 0; i < 8; i++) {
                assertNotEquals(TimerWheel.NONE, wheel.schedule(1_000, 5, i));
            }

            // when / then
            assertEquals(TimerWheel.NONE, wheel.schedule(1_000, 5, 99));
            assertThrows(IllegalArgumentException.class, () -> new TimerWheel(64, 1, 8, 0).schedule(0, 64, 1));
        }

        @Test
        @DisplayName("given a pending timer, when asking for the next deadline, then the wait is bounded by it")
        void givenPendingTimer_whenAskingNextDue_thenBounded() {
            // given
            assertEquals(Long.MAX_VALUE, wheel.nextDueInMs(1_000));
            wheel.schedule(1_000, 20, 1);

            // when
            final long wait = wheel.nextDueInMs(1_000);

            // then
            assertEquals(20, wait);
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given steady-state use, when scheduling, cancelling and expiring, then zero bytes are allocated")
        void givenSteadyState_whenUsing_thenZeroAlloc() {
            // given
            final long[] now = {1_000};
            final Runnable body = () -> {
                final int a = wheel.schedule(now[0], 20, 1);
                final int b = wheel.schedule(now[0], 5, 2);
                wheel.cancel(b);
                wheel.nextDueInMs(now[0]);
                now[0] += 21;
                wheel.advanceTo(now[0]);
                if (wheel.pollExpired() != 1 || a < 0) {
                    throw new AssertionError();
                }
            };

            // when / then
            final long bytes = AllocationProbe.bytesAllocated(body, 100_000, 100_000);
            assertEquals(0, bytes);
        }
    }
}
