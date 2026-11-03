package dev.villani.server.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** {@link Region} over a classic DirectByteBuffer (counts against -XX:MaxDirectMemorySize). */
final class DirectRegion implements Region {

    private final ByteBuffer buffer;

    DirectRegion(long bytes) {
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("region too large for a ByteBuffer: " + bytes);
        }
        this.buffer = ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder());
    }

    @Override
    public long size() {
        return buffer.capacity();
    }

    @Override
    public byte get(long offset) {
        return buffer.get((int) offset);
    }

    @Override
    public void put(long offset, byte value) {
        buffer.put((int) offset, value);
    }

    @Override
    public int getInt(long offset) {
        return buffer.getInt((int) offset);
    }

    @Override
    public void putInt(long offset, int value) {
        buffer.putInt((int) offset, value);
    }

    @Override
    public long getLong(long offset) {
        return buffer.getLong((int) offset);
    }

    @Override
    public void putLong(long offset, long value) {
        buffer.putLong((int) offset, value);
    }

    @Override
    public ByteBuffer asByteBuffer() {
        return buffer.duplicate().order(ByteOrder.nativeOrder());
    }
}
