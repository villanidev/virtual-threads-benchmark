package dev.villani.server.json;

/** Fixed set of records preallocated at startup; acquire/release never allocate. */
public final class JsonRecordPool {

    private final JsonRecord[] free;
    private int freeCount;

    public JsonRecordPool(JsonSchema schema, int size) {
        free = new JsonRecord[size];
        for (int i = 0; i < size; i++) {
            free[i] = schema.newRecord();
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
