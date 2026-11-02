package dev.villani.server.json;

import java.nio.charset.StandardCharsets;

/**
 * Startup-time description of a flat JSON object: field names, types and string capacities.
 * Everything is converted to flat arrays here so binding never touches a String.
 */
public final class JsonSchema {

    public static final int INT = 1;
    public static final int STRING = 2;
    public static final int BOOL = 3;

    private final int maxFields;
    final byte[][] names;
    final int[] types;
    final int[] stringOffset;
    final int[] stringCapacity;
    private int fields;
    private int stringBytes;
    private int maxNameLength;

    public JsonSchema(int maxFields) {
        this.maxFields = maxFields;
        names = new byte[maxFields][];
        types = new int[maxFields];
        stringOffset = new int[maxFields];
        stringCapacity = new int[maxFields];
    }

    /** Registers a field and returns its index. */
    public int field(String name, int type, int stringCapacity) {
        if (fields == maxFields) {
            throw new IllegalStateException("too many fields");
        }
        if (type == STRING && stringCapacity <= 0) {
            throw new IllegalArgumentException("string field needs a capacity");
        }
        final int f = fields++;
        names[f] = name.getBytes(StandardCharsets.UTF_8);
        types[f] = type;
        if (type == STRING) {
            stringOffset[f] = stringBytes;
            this.stringCapacity[f] = stringCapacity;
            stringBytes += stringCapacity;
        }
        maxNameLength = Math.max(maxNameLength, names[f].length);
        return f;
    }

    public int field(String name, int type) {
        return field(name, type, 0);
    }

    public int fieldCount() {
        return fields;
    }

    int stringBytes() {
        return stringBytes;
    }

    int maxNameLength() {
        return maxNameLength;
    }

    public JsonRecord newRecord() {
        return new JsonRecord(this);
    }
}
