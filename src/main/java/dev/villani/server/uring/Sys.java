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

/**
 * Raw Linux syscalls (x86_64 numbers) through one FFM downcall to libc {@code syscall(2)}: no JNI, no liburing.
 * The result is the kernel's return value or {@code -errno}. Not thread-safe: the errno capture slot is shared,
 * so one instance belongs to one event-loop thread.
 */
final class Sys {

    static final long SYS_CLOSE = 3;
    static final long SYS_MMAP = 9;
    static final long SYS_MUNMAP = 11;
    static final long SYS_SOCKET = 41;
    static final long SYS_SHUTDOWN = 48;
    static final long SYS_BIND = 49;
    static final long SYS_LISTEN = 50;
    static final long SYS_GETSOCKNAME = 51;
    static final long SYS_SETSOCKOPT = 54;
    static final long SYS_IO_URING_SETUP = 425;
    static final long SYS_IO_URING_ENTER = 426;

    private static final MethodHandle SYSCALL;
    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    static {
        final Linker linker = Linker.nativeLinker();
        final MemorySegment addr = linker.defaultLookup().find("syscall").orElseThrow();
        // syscall(long number, ...): the arguments after the number are variadic
        SYSCALL = linker.downcallHandle(addr, FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                Linker.Option.firstVariadicArg(1), Linker.Option.captureCallState("errno"));
    }

    private final MemorySegment capture;

    Sys(Arena arena) {
        this.capture = arena.allocate(CAPTURE);
    }

    long call(long nr, long a1, long a2, long a3, long a4, long a5, long a6) {
        try {
            final long r = (long) SYSCALL.invokeExact(capture, nr, a1, a2, a3, a4, a5, a6);
            return r == -1 ? -(int) ERRNO.get(capture, 0L) : r;
        } catch (Throwable t) {
            throw new IllegalStateException("syscall " + nr + " failed", t);
        }
    }

    long call(long nr, long a1, long a2, long a3) {
        return call(nr, a1, a2, a3, 0, 0, 0);
    }
}
