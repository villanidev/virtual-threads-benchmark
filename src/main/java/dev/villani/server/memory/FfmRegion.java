package dev.villani.server.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@link Region} over a FFM MemorySegment. Allocated from the global arena (never freed: regions live as long as the
 * process, sized once at boot). Not counted by -XX:MaxDirectMemorySize; it shows up in NMT "Other" and in the RSS.
 */
final class FfmRegion implements Region {

    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED;

    private final MemorySegment segment;

    FfmRegion(long bytes) {
        this.segment = Arena.global().allocate(bytes, 64);
    }

    @Override
    public long size() {
        return segment.byteSize();
    }

    @Override
    public byte get(long offset) {
        return segment.get(ValueLayout.JAVA_BYTE, offset);
    }

    @Override
    public void put(long offset, byte value) {
        segment.set(ValueLayout.JAVA_BYTE, offset, value);
    }

    @Override
    public int getInt(long offset) {
        return segment.get(INT, offset);
    }

    @Override
    public void putInt(long offset, int value) {
        segment.set(INT, offset, value);
    }

    @Override
    public long getLong(long offset) {
        return segment.get(LONG, offset);
    }

    @Override
    public void putLong(long offset, long value) {
        segment.set(LONG, offset, value);
    }

    @Override
    public ByteBuffer asByteBuffer() {
        return segment.asByteBuffer().order(ByteOrder.nativeOrder());
    }
}
