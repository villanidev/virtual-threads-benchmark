package dev.villani.server.json;

import dev.villani.server.memory.Region;

import java.nio.ByteBuffer;

/**
 * Pooled, flat target of a {@link JsonBinder}. The state of one record lives in a {@link Region} slice (a slab shared
 * by the whole pool, see {@link JsonRecordPool}) laid out by {@link JsonRecordLayout}: values, lengths, presence flags
 * and string bytes, all indexed by schema field. The Java object is only a flyweight (schema, region, base offset).
 */
public final class JsonRecord {

    private final JsonSchema schema;
    private final JsonRecordLayout layout;
    private final Region data;
    private final long base;
    private final int fields;
    boolean inUse;

    JsonRecord(JsonSchema schema, JsonRecordLayout layout, Region data, long base) {
        this.schema = schema;
        this.layout = layout;
        this.data = data;
        this.base = base;
        this.fields = schema.fieldCount();
    }

    public void clear() {
        for (int i = 0; i < fields; i++) {
            data.put(base + layout.presentOffset(i), (byte) 0);
            data.putInt(base + layout.lengthOffset(i), 0);
            data.putLong(base + layout.valueOffset(i), 0L);
        }
    }

    public boolean has(int field) {
        return data.get(base + layout.presentOffset(field)) != 0;
    }

    public long getInt(int field) {
        return data.getLong(base + layout.valueOffset(field));
    }

    public boolean getBool(int field) {
        return getInt(field) != 0;
    }

    public int stringLength(int field) {
        return data.getInt(base + layout.lengthOffset(field));
    }

    public byte stringByte(int field, int index) {
        return data.get(base + layout.stringsOffset() + schema.stringOffset[field] + index);
    }

    /** Copies the string bytes into {@code dst} (relative put, advances its position). */
    public void copyStringTo(int field, ByteBuffer dst) {
        final int len = stringLength(field);
        for (int i = 0; i < len; i++) {
            dst.put(stringByte(field, i));
        }
    }

    void setInt(int field, long value) {
        data.putLong(base + layout.valueOffset(field), value);
        data.put(base + layout.presentOffset(field), (byte) 1);
    }

    void markPresent(int field) {
        data.put(base + layout.presentOffset(field), (byte) 1);
    }

    /** Returns false when the string would exceed the field capacity. */
    boolean appendStringByte(int field, byte b) {
        final int len = stringLength(field);
        if (len == schema.stringCapacity[field]) {
            return false;
        }
        data.put(base + layout.stringsOffset() + schema.stringOffset[field] + len, b);
        data.putInt(base + layout.lengthOffset(field), len + 1);
        return true;
    }
}
