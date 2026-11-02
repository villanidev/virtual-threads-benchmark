package dev.villani.baseline.heap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Idiomatic on-heap repository: one object per appointment, indexed by id, slot and day. */
public final class HeapStore {

    public static final int CONFLICT = -1;
    public static final int FULL = -2;
    public static final int DAYS_FULL = -3;

    public static final int OK = 0;
    public static final int NOT_FOUND = -1;
    public static final int ALREADY_CANCELLED = -2;

        private static final long SECONDS_PER_DAY = 86_400L;

    static final class Agendamento {
        final int id;
        final int servicoId;
        final long dataHora;
        final String nome;
        volatile boolean cancelado;

        String status() {
            return cancelado ? "CANCELADO" : "CONFIRMADO";
        }

        Agendamento(int id, int servicoId, long dataHora, String nome) {
            this.id = id;
            this.servicoId = servicoId;
            this.dataHora = dataHora;
            this.nome = nome;
        }
    }

    private record Slot(int servicoId, long dataHora) {
    }

    private final int capacity;
    private final int maxDays;
    private final Map<Integer, Agendamento> byId = new ConcurrentHashMap<>();
    private final Map<Slot, Agendamento> bySlot = new ConcurrentHashMap<>();
    private final Map<Long, ConcurrentLinkedDeque<Agendamento>> byDay = new ConcurrentHashMap<>();
    private int count;

    public HeapStore(int capacity, int maxDays) {
        this.capacity = capacity;
        this.maxDays = maxDays;
    }

    /** Returns the new id, or {@link #CONFLICT}, {@link #FULL}, {@link #DAYS_FULL}. */
    public synchronized int create(int servicoId, long dataHora, String nome) {
        if (count == capacity) {
            return FULL;
        }
        final Slot slot = new Slot(servicoId, dataHora);
        final Agendamento existing = bySlot.get(slot);
        if (existing != null && !existing.cancelado) {
            return CONFLICT;
        }
        final long day = Math.floorDiv(dataHora, SECONDS_PER_DAY);
        if (!byDay.containsKey(day) && byDay.size() >= maxDays) {
            return DAYS_FULL;
        }
        final Agendamento created = new Agendamento(++count, servicoId, dataHora, nome);
        byId.put(created.id, created);
        bySlot.put(slot, created);
        byDay.computeIfAbsent(day, d -> new ConcurrentLinkedDeque<>()).addFirst(created);
        return created.id;
    }

    public synchronized int cancel(int id) {
        final Agendamento a = byId.get(id);
        if (a == null) {
            return NOT_FOUND;
        }
        if (a.cancelado) {
            return ALREADY_CANCELLED;
        }
        a.cancelado = true;
        return OK;
    }

    /** Newest first; empty when the day has no appointments. */
    Iterable<Agendamento> ofDay(long epochDay) {
        final ConcurrentLinkedDeque<Agendamento> list = byDay.get(epochDay);
        return list == null ? java.util.List.of() : list;
    }
}
