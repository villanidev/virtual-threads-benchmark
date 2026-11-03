package dev.villani.server.memory;

import java.nio.ByteBuffer;

/**
 * A fixed block of off-heap memory with absolute, native-order accessors. Two backends exist:
 * {@link DirectRegion} (classic DirectByteBuffer) and {@link FfmRegion} (java.lang.foreign MemorySegment),
 * chosen once by {@link Regions}. Data structures use the accessors; NIO I/O uses {@link #asByteBuffer()},
 * a view over the same memory (no copy).
 */
public interface Region {

    long size();

    byte get(long offset);

    void put(long offset, byte value);

    int getInt(long offset);

    void putInt(long offset, int value);

    long getLong(long offset);

    void putLong(long offset, long value);

    /** A native-order ByteBuffer over the whole region (shares the memory). */
    ByteBuffer asByteBuffer();
}
