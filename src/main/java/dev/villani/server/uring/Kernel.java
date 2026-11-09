package dev.villani.server.uring;

import dev.villani.server.memory.Regions;

/**
 * What the io_uring transport needs from the OS: raw Linux syscalls (x86_64 numbers) and absolute-address memory
 * access (the rings are mmap'd kernel memory). Two backends: {@link FfmKernel} on the JVM (FFM downcall) and
 * {@link NativeKernel} inside a native image ({@code @CFunction} + Native Image word API, where FFM accessors are
 * ~35x slower). Syscalls return the kernel's result or {@code -errno}. One instance belongs to one event-loop thread.
 */
interface Kernel extends AutoCloseable {

    long SYS_CLOSE = 3;
    long SYS_MMAP = 9;
    long SYS_MUNMAP = 11;
    long SYS_SOCKET = 41;
    long SYS_SHUTDOWN = 48;
    long SYS_BIND = 49;
    long SYS_LISTEN = 50;
    long SYS_GETSOCKNAME = 51;
    long SYS_SETSOCKOPT = 54;
    long SYS_IO_URING_SETUP = 425;
    long SYS_IO_URING_ENTER = 426;

    static Kernel create() {
        return Regions.inNativeImage() ? new NativeKernel() : new FfmKernel();
    }

    /** A syscall that returns promptly (no thread-state transition in a native image). */
    long syscall(long nr, long a1, long a2, long a3, long a4, long a5, long a6);

    /** A syscall that may block for a long time (keeps the VM safepoint-safe in a native image). */
    long blockingSyscall(long nr, long a1, long a2, long a3, long a4, long a5, long a6);

    default long syscall(long nr, long a1, long a2, long a3) {
        return syscall(nr, a1, a2, a3, 0, 0, 0);
    }

    /** Zeroed, 8-byte aligned off-heap memory, released by {@link #close()}. */
    long alloc(long bytes);

    byte getByte(long addr);

    void putByte(long addr, byte value);

    short getShort(long addr);

    void putShort(long addr, short value);

    int getInt(long addr);

    void putInt(long addr, int value);

    long getLong(long addr);

    void putLong(long addr, long value);

    @Override
    void close();
}
