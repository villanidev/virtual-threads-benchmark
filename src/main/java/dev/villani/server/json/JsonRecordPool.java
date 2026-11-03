package dev.villani.server.json;

import dev.villani.server.memory.Region;
import dev.villani.server.memory.Regions;

/**
 * Fixed set of records preallocated at startup; acquire/release never allocate. All record states share one
 * off-heap slab (record i at offset i * recordSize); the Java objects are just flyweights over it.
 */
public final class JsonRecordPool {

    private final JsonRecord[] free;
    private int freeCount;

    public JsonRecordPool(JsonSchema schema, int size) {
        free = new JsonRecord[size];
        final JsonRecordLayout layout = schema.layout();
        final Region slab = Regions.allocate((long) size * layout.size());
        for (int i = 0; i < size; i++) {
            free[i] = schema.newRecord(slab, (long) i * layout.size(), layout);
        }
        freeCount = size;
    }

    /** Returns a cleared record, or null when the pool is exhausted. */
    public JsonRecord acquire() {
        if (freeCount == 0) {
            return null;
        }
        final JsonRecord r = free[--freeCount];
        free[freeCount] = null;
        r.inUse = true;
        return r;
    }

    public void release(JsonRecord record) {
        if (!record.inUse) {
            throw new IllegalStateException("record is not in use");
        }
        record.inUse = false;
        record.clear();
        free[freeCount++] = record;
    }

    public int available() {
        return freeCount;
    }
}
