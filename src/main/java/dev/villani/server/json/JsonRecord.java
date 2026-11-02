package dev.villani.server.json;

import java.nio.ByteBuffer;

/** Pooled, flat target of a {@link JsonBinder}: primitive arrays indexed by schema field. */
public final class JsonRecord {

    private final JsonSchema schema;
    private final long[] ints;
    private final boolean[] present;
    private final byte[] strings;
    private final int[] lengths;
    boolean inUse;

    JsonRecord(JsonSchema schema) {
        this.schema = schema;
        int n = schema.fieldCount();
        ints = new long[n];
        present = new boolean[n];
        lengths = new int[n];
        strings = new byte[schema.stringBytes()];
    }

    public void clear() {
        for (int i = 0; i < present.length; i++) {
            present[i] = false;
            lengths[i] = 0;
            ints[i] = 0;
        }
    }

    public boolean has(int field) {
        return present[field];
    }

    public long getInt(int field) {
        return ints[field];
    }

    public boolean getBool(int field) {
        return ints[field] != 0;
    }

    public int stringLength(int field) {
        return lengths[field];
    }

    public byte stringByte(int field, int index) {
        return strings[schema.stringOffset[field] + index];
    }

    /** Copies the string bytes into {@code dst} (relative put, advances its position). */
    public void copyStringTo(int field, ByteBuffer dst) {
        dst.put(strings, schema.stringOffset[field], lengths[field]);
    }

    void setInt(int field, long value) {
        ints[field] = value;
        present[field] = true;
    }

    void markPresent(int field) {
        present[field] = true;
    }

    /** Returns false when the string would exceed the field capacity. */
    boolean appendStringByte(int field, byte b) {
        final int len = lengths[field];
        if (len == schema.stringCapacity[field]) {
            return false;
        }
        strings[schema.stringOffset[field] + len] = b;
        lengths[field] = len + 1;
        return true;
    }
}
