package dev.villani.jmh;

import dev.villani.server.memory.Region;
import dev.villani.server.memory.Regions;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/**
 * Cost of one "record access" (read servico+dataHora, write status) over the same 64-byte records layout as
 * AgendamentoTable, with a pseudo-random record order so it is not a pure streaming read.
 * Compares: Region(direct), Region(ffm), a raw ByteBuffer with manual offsets and a raw MemorySegment with a
 * StructLayout + static final VarHandles.
 * Run: ./run.sh jmh   (or java -cp ... org.openjdk.jmh.Main AccessBench)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 3, jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED", "-Xmx128m", "-Xms128m"})
@State(Scope.Thread)
public class AccessBench {

    static final int RECORD = 64;
    static final int O_STATUS = 4;
    static final int O_SERVICO = 8;
    static final int O_DATA_HORA = 16;

    static final StructLayout REC = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("id"),
            ValueLayout.JAVA_BYTE.withName("status"),
            ValueLayout.JAVA_BYTE.withName("nomeLen"),
            MemoryLayout.paddingLayout(2),
            ValueLayout.JAVA_INT.withName("servico"),
            ValueLayout.JAVA_INT.withName("next"),
            ValueLayout.JAVA_LONG.withName("dataHora"),
            MemoryLayout.sequenceLayout(40, ValueLayout.JAVA_BYTE).withName("nome"));
    static final VarHandle H_STATUS = REC.varHandle(PathElement.groupElement("status"));
    static final VarHandle H_SERVICO = REC.varHandle(PathElement.groupElement("servico"));
    static final VarHandle H_DATA_HORA = REC.varHandle(PathElement.groupElement("dataHora"));

    // 256 records (16 KB) stay in L1: isolates the access instructions; 200000 (12.8 MB) is memory-bound like the real table
    @Param({"256", "200000"})
    int records;

    @Param({"direct", "ffm"})
    String backend;

    Region region;
    ByteBuffer buffer;
    MemorySegment segment;
    int cursor;

    @Setup
    public void setup() {
        if (REC.byteSize() != RECORD) {
            throw new IllegalStateException("layout is " + REC.byteSize());
        }
        region = Regions.allocate((long) records * RECORD, backend.equals("ffm"));
        buffer = ByteBuffer.allocateDirect(records * RECORD).order(ByteOrder.nativeOrder());
        segment = Arena.global().allocate((long) records * RECORD, 64);
        for (int i = 0; i < records; i++) {
            final int off = i * RECORD;
            region.putInt(off + O_SERVICO, i);
            region.putLong(off + O_DATA_HORA, i * 31L);
            buffer.putInt(off + O_SERVICO, i);
            buffer.putLong(off + O_DATA_HORA, i * 31L);
            segment.set(ValueLayout.JAVA_INT_UNALIGNED, off + O_SERVICO, i);
            segment.set(ValueLayout.JAVA_LONG_UNALIGNED, off + O_DATA_HORA, i * 31L);
        }
    }

    private int next() {
        cursor = (cursor + 40_009) % records;
        return cursor * RECORD;
    }

    @Benchmark
    public long region() {
        final int off = next();
        final long v = region.getInt(off + O_SERVICO) + region.getLong(off + O_DATA_HORA);
        region.put(off + O_STATUS, (byte) 1);
        return v;
    }

    @Benchmark
    public long rawByteBuffer() {
        final int off = next();
        final long v = buffer.getInt(off + O_SERVICO) + buffer.getLong(off + O_DATA_HORA);
        buffer.put(off + O_STATUS, (byte) 1);
        return v;
    }

    @Benchmark
    public long rawSegmentManualOffsets() {
        final int off = next();
        final long v = segment.get(ValueLayout.JAVA_INT_UNALIGNED, off + O_SERVICO)
                + segment.get(ValueLayout.JAVA_LONG_UNALIGNED, off + O_DATA_HORA);
        segment.set(ValueLayout.JAVA_BYTE, off + O_STATUS, (byte) 1);
        return v;
    }

    @Benchmark
    public long rawSegmentStructLayout() {
        final long off = next();
        final long v = (int) H_SERVICO.get(segment, off) + (long) H_DATA_HORA.get(segment, off);
        H_STATUS.set(segment, off, (byte) 1);
        return v;
    }
}
