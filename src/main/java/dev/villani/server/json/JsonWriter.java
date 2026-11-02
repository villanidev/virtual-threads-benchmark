package dev.villani.server.json;

import java.nio.ByteBuffer;

/** Allocation-free helpers to append JSON fragments to a response body buffer. */
public final class JsonWriter {

    private JsonWriter() {
    }

    public static void put(ByteBuffer dst, byte[] constant) {
        dst.put(constant);
    }

    public static void putLong(ByteBuffer dst, long value) {
        long v = value;
        if (v < 0) {
            dst.put((byte) '-');
            v = -v;
        }
        int digits = 1;
        for (long t = v; t >= 10; t /= 10) {
            digits++;
        }
        final int base = dst.position();
        dst.position(base + digits);
        for (int i = digits - 1; i >= 0; i--) {
            dst.put(base + i, (byte) ('0' + v % 10));
            v /= 10;
        }
    }
}
