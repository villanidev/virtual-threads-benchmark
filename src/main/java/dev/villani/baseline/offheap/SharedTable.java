package dev.villani.baseline.offheap;

import dev.villani.application.agendamento.AgendamentoTable;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.StampedLock;

/**
 * The flat {@link AgendamentoTable} (single-threaded by design) made safe for concurrent request threads:
 * one {@link StampedLock}, exclusive for writes and shared for the listing walk.
 */
final class SharedTable {

    private final AgendamentoTable table;
    private final StampedLock lock = new StampedLock();

    SharedTable(AgendamentoTable table) {
        this.table = table;
    }

    int create(int servico, long dataHora, ByteBuffer nome) {
        final long stamp = lock.writeLock();
        try {
            return table.create(servico, dataHora, nome);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    int cancel(int id) {
        final long stamp = lock.writeLock();
        try {
            return table.cancel(id);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    long readLock() {
        return lock.readLock();
    }

    void unlockRead(long stamp) {
        lock.unlockRead(stamp);
    }

    /** Callers must hold the read lock. */
    AgendamentoTable unsafe() {
        return table;
    }
}
