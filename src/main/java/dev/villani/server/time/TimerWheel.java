package dev.villani.server.time;

/**
 * Flat hashed timer wheel (single-threaded). Entries live in preallocated int arrays and are
 * chained per wheel slot with a doubly-linked list, so schedule, cancel and expiry are O(1) and
 * allocation-free. Delays must be shorter than {@code wheelSlots * tickMs}, so each slot only ever
 * holds entries due on the same revolution.
 */
public final class TimerWheel {

    public static final int NONE = -1;

    private static final byte FREE = 0;
    private static final byte SCHEDULED = 1;
    private static final byte EXPIRED = 2;
    private static final byte CANCELLED = 3;

    private final int tickMs;
    private final int mask;
    private final int[] heads;
    private final int[] next;
    private final int[] prev;
    private final int[] token;
    private final int[] wheelSlot;
    private final byte[] state;
    private final int[] freeStack;
    private int freeCount;
    private final int[] expired;
    private int expiredHead;
    private int expiredSize;
    private long currentTick;
    private int scheduled;

    public TimerWheel(int wheelSlots, int tickMs, int capacity, long nowMs) {
        if (Integer.bitCount(wheelSlots) != 1) {
            throw new IllegalArgumentException("wheelSlots must be a power of two");
        }
        this.tickMs = tickMs;
        this.mask = wheelSlots - 1;
        heads = new int[wheelSlots];
        java.util.Arrays.fill(heads, NONE);
        next = new int[capacity];
        prev = new int[capacity];
        token = new int[capacity];
        wheelSlot = new int[capacity];
        state = new byte[capacity];
        freeStack = new int[capacity];
        expired = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            freeStack[i] = capacity - 1 - i;
        }
        freeCount = capacity;
        currentTick = nowMs / tickMs;
    }

    public long maxDelayMs() {
        return (long) mask * tickMs;
    }

    /** Schedules {@code tokenValue} after {@code delayMs}. Returns an entry handle, or NONE when full. */
    public int schedule(long nowMs, long delayMs, int tokenValue) {
        if (delayMs < 0 || delayMs > maxDelayMs()) {
            throw new IllegalArgumentException("delay out of range: " + delayMs);
        }
        if (freeCount == 0) {
            return NONE;
        }
        final long due = Math.max(currentTick + 1, (nowMs + delayMs + tickMs - 1) / tickMs);
        final int e = freeStack[--freeCount];
        final int slot = (int) (due & mask);
        token[e] = tokenValue;
        wheelSlot[e] = slot;
        state[e] = SCHEDULED;
        prev[e] = NONE;
        next[e] = heads[slot];
        if (heads[slot] != NONE) {
            prev[heads[slot]] = e;
        }
        heads[slot] = e;
        scheduled++;
        return e;
    }

    public void cancel(int entry) {
        if (entry < 0) {
            return;
        }
        if (state[entry] == SCHEDULED) {
            unlink(entry);
            scheduled--;
            release(entry);
        } else if (state[entry] == EXPIRED) {
            state[entry] = CANCELLED; // already queued: dropped when polled
        }
    }

    /** Moves every entry due up to {@code nowMs} into the expired queue. */
    public void advanceTo(long nowMs) {
        final long target = nowMs / tickMs;
        if (target <= currentTick) {
            return;
        }
        final long steps = Math.min(target - currentTick, mask + 1);
        for (long i = 1; i <= steps; i++) {
            final int slot = (int) ((currentTick + i) & mask);
            int e = heads[slot];
            heads[slot] = NONE;
            while (e != NONE) {
                final int following = next[e];
                state[e] = EXPIRED;
                expired[(expiredHead + expiredSize++) % expired.length] = e;
                scheduled--;
                e = following;
            }
        }
        currentTick = target;
    }

    /** Next expired token, or NONE. The entry is recycled. */
    public int pollExpired() {
        while (expiredSize > 0) {
            final int e = expired[expiredHead];
            expiredHead = (expiredHead + 1) % expired.length;
            expiredSize--;
            final boolean cancelled = state[e] == CANCELLED;
            final int t = token[e];
            release(e);
            if (!cancelled) {
                return t;
            }
        }
        return NONE;
    }

    /** Milliseconds until the next entry is due: 0 if one is already expired, Long.MAX_VALUE if none. */
    public long nextDueInMs(long nowMs) {
        if (expiredSize > 0) {
            return 0;
        }
        if (scheduled == 0) {
            return Long.MAX_VALUE;
        }
        for (int i = 1; i <= mask + 1; i++) {
            if (heads[(int) ((currentTick + i) & mask)] != NONE) {
                return Math.max(0, (currentTick + i) * tickMs - nowMs);
            }
        }
        return Long.MAX_VALUE;
    }

    public int pending() {
        return scheduled;
    }

    private void unlink(int e) {
        if (prev[e] != NONE) {
            next[prev[e]] = next[e];
        } else {
            heads[wheelSlot[e]] = next[e];
        }
        if (next[e] != NONE) {
            prev[next[e]] = prev[e];
        }
    }

    private void release(int e) {
        state[e] = FREE;
        freeStack[freeCount++] = e;
    }
}
