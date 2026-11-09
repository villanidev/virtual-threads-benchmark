package dev.villani.server.uring;

import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link Kernel} inside a native image: {@code syscall(2)} is a direct {@code @CFunction} call (static musl image, no
 * FFM, no reachability metadata) and memory goes through the word API, lowered to plain loads and stores.
 * Only loaded inside a native image, see {@link Kernel#create()}.
 */
final class NativeKernel implements Kernel {

    /** libc's variadic syscall(2) is assembly on musl and glibc: calling it with fixed arguments is safe on x86_64. */
    @CFunction(value = "syscall", transition = CFunction.Transition.NO_TRANSITION)
    private static native long syscallFast(long nr, long a1, long a2, long a3, long a4, long a5, long a6);

    @CFunction("syscall")
    private static native long syscallSlow(long nr, long a1, long a2, long a3, long a4, long a5, long a6);

    @CFunction(value = "__errno_location", transition = CFunction.Transition.NO_TRANSITION)
    private static native Pointer errnoLocation();

    private final List<Pointer> allocations = new ArrayList<>();

    @Override
    public long syscall(long nr, long a1, long a2, long a3, long a4, long a5, long a6) {
        final long r = syscallFast(nr, a1, a2, a3, a4, a5, a6);
        return r == -1 ? -errnoLocation().readInt(0) : r;
    }

    @Override
    public long blockingSyscall(long nr, long a1, long a2, long a3, long a4, long a5, long a6) {
        final long r = syscallSlow(nr, a1, a2, a3, a4, a5, a6);
        return r == -1 ? -errnoLocation().readInt(0) : r;
    }

    @Override
    public long alloc(long bytes) {
        final Pointer p = UnmanagedMemory.calloc(WordFactory.unsigned(bytes));
        allocations.add(p);
        return p.rawValue();
    }

    @Override
    public byte getByte(long addr) {
        return WordFactory.<Pointer>pointer(addr).readByte(0);
    }

    @Override
    public void putByte(long addr, byte value) {
        WordFactory.<Pointer>pointer(addr).writeByte(0, value);
    }

    @Override
    public short getShort(long addr) {
        return WordFactory.<Pointer>pointer(addr).readShort(0);
    }

    @Override
    public void putShort(long addr, short value) {
        WordFactory.<Pointer>pointer(addr).writeShort(0, value);
    }

    @Override
    public int getInt(long addr) {
        return WordFactory.<Pointer>pointer(addr).readInt(0);
    }

    @Override
    public void putInt(long addr, int value) {
        WordFactory.<Pointer>pointer(addr).writeInt(0, value);
    }

    @Override
    public long getLong(long addr) {
        return WordFactory.<Pointer>pointer(addr).readLong(0);
    }

    @Override
    public void putLong(long addr, long value) {
        WordFactory.<Pointer>pointer(addr).writeLong(0, value);
    }

    @Override
    public void close() {
        for (Pointer p : allocations) {
            UnmanagedMemory.free(p);
        }
        allocations.clear();
    }
}
