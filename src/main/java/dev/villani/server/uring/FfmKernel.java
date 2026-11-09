package dev.villani.server.uring;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/** {@link Kernel} on the JVM: one FFM downcall to libc {@code syscall(2)} (no JNI, no liburing) and FFM memory access. */
final class FfmKernel implements Kernel {

    private static final MethodHandle SYSCALL;
    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    /** The whole address space as one segment: the rings are raw kernel memory addressed by plain longs. */
    private static final MemorySegment ALL = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

    static {
        final Linker linker = Linker.nativeLinker();
        final MemorySegment addr = linker.defaultLookup().find("syscall").orElseThrow();
        // syscall(long number, ...): the arguments after the number are variadic
        SYSCALL = linker.downcallHandle(addr, FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                Linker.Option.firstVariadicArg(1), Linker.Option.captureCallState("errno"));
    }

    private final Arena arena = Arena.ofShared();
    private final MemorySegment capture = arena.allocate(CAPTURE);

    @Override
    public long syscall(long nr, long a1, long a2, long a3, long a4, long a5, long a6) {
        try {
            final long r = (long) SYSCALL.invokeExact(capture, nr, a1, a2, a3, a4, a5, a6);
            // Branchless on purpose: an error path first taken inside a measured window would deoptimize and allocate.
            // syscall() returns -1 only on failure and never a negative success value (user addresses are positive).
            final long failed = r >> 63;
            return (r & ~failed) | (-(long) (int) ERRNO.get(capture, 0L) & failed);
        } catch (Throwable t) {
            throw new IllegalStateException("syscall " + nr + " failed", t);
        }
    }

    @Override
    public long blockingSyscall(long nr, long a1, long a2, long a3, long a4, long a5, long a6) {
        return syscall(nr, a1, a2, a3, a4, a5, a6);
    }

    @Override
    public long alloc(long bytes) {
        return arena.allocate(bytes, 8).address();
    }

    @Override
    public byte getByte(long addr) {
        return ALL.get(ValueLayout.JAVA_BYTE, addr);
    }

    @Override
    public void putByte(long addr, byte value) {
        ALL.set(ValueLayout.JAVA_BYTE, addr, value);
    }

    @Override
    public short getShort(long addr) {
        return ALL.get(ValueLayout.JAVA_SHORT_UNALIGNED, addr);
    }

    @Override
    public void putShort(long addr, short value) {
        ALL.set(ValueLayout.JAVA_SHORT_UNALIGNED, addr, value);
    }

    @Override
    public int getInt(long addr) {
        return ALL.get(ValueLayout.JAVA_INT_UNALIGNED, addr);
    }

    @Override
    public void putInt(long addr, int value) {
        ALL.set(ValueLayout.JAVA_INT_UNALIGNED, addr, value);
    }

    @Override
    public long getLong(long addr) {
        return ALL.get(ValueLayout.JAVA_LONG_UNALIGNED, addr);
    }

    @Override
    public void putLong(long addr, long value) {
        ALL.set(ValueLayout.JAVA_LONG_UNALIGNED, addr, value);
    }

    @Override
    public void close() {
        arena.close();
    }
}
