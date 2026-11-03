package dev.villani.server.memory;

import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@link Region} over C memory (calloc) accessed through the Native Image SDK word API ({@link Pointer}), which the
 * compiler lowers to plain loads/stores. Only usable inside a native image (-Dflat.memory=native): the standard FFM
 * MemorySegment accessors are ~35x slower there (see README, Ciclo 3). Never freed (regions live for the process).
 */
final class NativeRegion implements Region {

    private final Pointer base;
    private final int size;

    NativeRegion(long bytes) {
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("native region limited to 2 GB: " + bytes);
        }
        this.size = (int) bytes;
        this.base = UnmanagedMemory.calloc(WordFactory.unsigned(bytes));
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public byte get(long offset) {
        return base.readByte((int) offset);
    }

    @Override
    public void put(long offset, byte value) {
        base.writeByte((int) offset, value);
    }

    @Override
    public int getInt(long offset) {
        return base.readInt((int) offset);
    }

    @Override
    public void putInt(long offset, int value) {
        base.writeInt((int) offset, value);
    }

    @Override
    public long getLong(long offset) {
        return base.readLong((int) offset);
    }

    @Override
    public void putLong(long offset, long value) {
        base.writeLong((int) offset, value);
    }

    @Override
    public ByteBuffer asByteBuffer() {
        return CTypeConversion.asByteBuffer(base, size).order(ByteOrder.nativeOrder());
    }
}
