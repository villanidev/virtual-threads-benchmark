package dev.villani.application.agendamento;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Off-heap repository of appointments. Fixed 64-byte records in one direct buffer, plus two
 * open-addressing indexes (also off-heap): (servico, dataHora) for conflict detection and
 * day -> newest record for listing (records of the same day are chained via nextInDay).
 * Everything is sized at construction; no operation allocates.
 *
 * <pre>
 * record: 0 id:int | 4 status:byte | 5 nomeLen:byte | 8 servico:int | 12 nextInDay:int
 *         16 dataHora:long (epoch seconds) | 24 nome[40]
 * </pre>
 */
public final class AgendamentoTable {

    public static final int CONFLICT = -1;
    public static final int FULL = -2;
    public static final int DAYS_FULL = -3;

    public static final int OK = 0;
    public static final int NOT_FOUND = -1;
    public static final int ALREADY_CANCELLED = -2;

    public static final byte CONFIRMADO = 1;
    public static final byte CANCELADO = 2;

    public static final int MAX_NOME = 40;
    private static final int RECORD = 64;
    private static final int O_STATUS = 4;
    private static final int O_NOME_LEN = 5;
    private static final int O_SERVICO = 8;
    private static final int O_NEXT = 12;
    private static final int O_DATA_HORA = 16;
    private static final int O_NOME = 24;
    private static final long SECONDS_PER_DAY = 86_400L;

    private final ByteBuffer records;
    private final ByteBuffer hash;
    private final ByteBuffer days;
    private final int capacity;
    private final int hashMask;
    private final int dayMask;
    private int count;
    private int daysUsed;

    /** @param capacity max records; @param daySlots max distinct days (both rounded up to powers of two for indexes) */
    public AgendamentoTable(int capacity, int daySlots) {
        this.capacity = capacity;
        final int hashSlots = Integer.highestOneBit(Math.max(2, capacity * 2 - 1)) << 1;
        final int daySize = Integer.highestOneBit(Math.max(2, daySlots - 1)) << 1;
        hashMask = hashSlots - 1;
        dayMask = daySize - 1;
        records = ByteBuffer.allocateDirect(capacity * RECORD).order(ByteOrder.nativeOrder());
        hash = ByteBuffer.allocateDirect(hashSlots * 4).order(ByteOrder.nativeOrder());
        days = ByteBuffer.allocateDirect(daySize * 8).order(ByteOrder.nativeOrder());
    }

    /**
     * Creates a confirmed appointment. {@code nome} is read from position to limit (max 40 bytes).
     * Returns the new id (>0), {@link #CONFLICT}, {@link #FULL} or {@link #DAYS_FULL}.
     */
    public int create(int servico, long dataHora, ByteBuffer nome) {
        final int nomeLen = nome.remaining();
        if (nomeLen > MAX_NOME) {
            throw new IllegalArgumentException("nome too long");
        }
        if (count == capacity) {
            return FULL;
        }

        int slot = hashOf(servico, dataHora) & hashMask;
        int reusable = -1;
        while (true) {
            final int id = hash.getInt(slot << 2);
            if (id == 0) {
                if (reusable < 0) {
                    reusable = slot;
                }
                break;
            }
            final int off = (id - 1) * RECORD;
            if (records.getInt(off + O_SERVICO) == servico && records.getLong(off + O_DATA_HORA) == dataHora) {
                if (records.get(off + O_STATUS) == CONFIRMADO) {
                    return CONFLICT;
                }
                if (reusable < 0) {
                    reusable = slot;
                }
            }
            slot = (slot + 1) & hashMask;
        }

        final int day = (int) Math.floorDiv(dataHora, SECONDS_PER_DAY);
        final int daySlot = findDaySlot(day);
        if (daySlot < 0) {
            return DAYS_FULL;
        }
        final boolean newDay = days.getInt((daySlot << 3) + 4) == 0;

        final int id = ++count;
        final int off = (id - 1) * RECORD;
        records.putInt(off, id);
        records.put(off + O_STATUS, CONFIRMADO);
        records.put(off + O_NOME_LEN, (byte) nomeLen);
        records.putInt(off + O_SERVICO, servico);
        records.putLong(off + O_DATA_HORA, dataHora);
        for (int i = 0; i < nomeLen; i++) {
            records.put(off + O_NOME + i, nome.get(nome.position() + i));
        }
        records.putInt(off + O_NEXT, days.getInt((daySlot << 3) + 4));
        days.putInt(daySlot << 3, day);
        days.putInt((daySlot << 3) + 4, id);
        if (newDay) {
            daysUsed++;
        }
        hash.putInt(reusable << 2, id);
        return id;
    }

    /** Slot of {@code day}, an empty slot to claim it, or -1 when the day index is full. */
    private int findDaySlot(int day) {
        int slot = mix(day) & dayMask;
        for (int probes = 0; probes <= dayMask; probes++) {
            if (days.getInt((slot << 3) + 4) == 0) {
                return daysUsed > dayMask ? -1 : slot;
            }
            if (days.getInt(slot << 3) == day) {
                return slot;
            }
            slot = (slot + 1) & dayMask;
        }
        return -1;
    }

    public int cancel(int id) {
        if (!exists(id)) {
            return NOT_FOUND;
        }
        final int off = (id - 1) * RECORD;
        if (records.get(off + O_STATUS) == CANCELADO) {
            return ALREADY_CANCELLED;
        }
        records.put(off + O_STATUS, CANCELADO);
        return OK;
    }

    public boolean exists(int id) {
        return id >= 1 && id <= count;
    }

    public int count() {
        return count;
    }

    /** Newest appointment id of the given epoch day, or 0 when none. */
    public int firstOfDay(int day) {
        int slot = mix(day) & dayMask;
        for (int probes = 0; probes <= dayMask; probes++) {
            final int head = days.getInt((slot << 3) + 4);
            if (head == 0) {
                return 0;
            }
            if (days.getInt(slot << 3) == day) {
                return head;
            }
            slot = (slot + 1) & dayMask;
        }
        return 0;
    }

    /** Next (older) appointment id in the same day, or 0 at the end of the chain. */
    public int nextOfDay(int id) {
        return records.getInt((id - 1) * RECORD + O_NEXT);
    }

    public byte status(int id) {
        return records.get((id - 1) * RECORD + O_STATUS);
    }

    public int servico(int id) {
        return records.getInt((id - 1) * RECORD + O_SERVICO);
    }

    public long dataHora(int id) {
        return records.getLong((id - 1) * RECORD + O_DATA_HORA);
    }

    /** Appends the name to {@code dst} (relative put). */
    public void copyNome(int id, ByteBuffer dst) {
        final int off = (id - 1) * RECORD;
        final int len = records.get(off + O_NOME_LEN);
        for (int i = 0; i < len; i++) {
            dst.put(records.get(off + O_NOME + i));
        }
    }

    private static int hashOf(int servico, long dataHora) {
        return mix(servico) ^ mix((int) (dataHora ^ (dataHora >>> 32)));
    }

    private static int mix(int x) {
        int h = x * 0x9E3779B1;
        return h ^ (h >>> 15);
    }
}
