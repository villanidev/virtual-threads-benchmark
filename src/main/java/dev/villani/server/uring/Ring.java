package dev.villani.server.uring;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Minimal io_uring: the SQ/CQ rings and the SQE array are mapped once and driven with plain memory accesses, so
 * submitting and reaping operations allocates nothing. Single issuer, no SQPOLL. Offsets follow
 * {@code struct io_uring_params}, {@code io_uring_sqe} and {@code io_uring_cqe} of the Linux uapi (x86_64).
 */
final class Ring implements AutoCloseable {

    static final int OP_NOP = 0;
    static final int OP_ACCEPT = 13;
    static final int OP_SEND = 26;
    static final int OP_RECV = 27;

    private static final int PARAMS_BYTES = 120;
    private static final int SQE_BYTES = 64;
    private static final int CQE_BYTES = 16;
    private static final long OFF_SQ_RING = 0L;
    private static final long OFF_SQES = 0x10000000L;
    private static final int FEAT_SINGLE_MMAP = 1;
    private static final int ENTER_GETEVENTS = 1;
    private static final int ENTER_EXT_ARG = 1 << 3;
    private static final int PROT_READ_WRITE = 3;
    private static final int MAP_SHARED_POPULATE = 0x01 | 0x8000;
    private static final int EINTR = 4;
    private static final int ETIME = 62;
    private static final int MSG_NOSIGNAL = 0x4000;
    private static final int SOCK_CLOEXEC = 0x80000;

    private final Sys sys;
    private final int fd;
    private final int setupFlags;
    private final int sqEntries;
    private final long cqMask;
    private final MemorySegment sqRing;
    private final MemorySegment cqRing;
    private final MemorySegment sqes;
    private final long sqHeadOff;
    private final long sqTailOff;
    private final long cqHeadOff;
    private final long cqTailOff;
    private final long cqesOff;
    private final long mapSqBytes;
    private final long mapCqBytes;
    private final long mapSqeBytes;
    private final MemorySegment enterArg;
    private int sqTail;
    private int submitted;
    private boolean closed;

    static final int SETUP_COOP_TASKRUN = 1 << 8;
    static final int SETUP_SINGLE_ISSUER = 1 << 12;
    static final int SETUP_DEFER_TASKRUN = 1 << 13;
    private static final int EINVAL = 22;

    Ring(Sys sys, Arena arena, int entries) {
        this(sys, arena, entries, 0);
    }

    /** {@code setupFlags} are best effort: kernels that reject them (EINVAL) get a plain ring. */
    Ring(Sys sys, Arena arena, int entries, int setupFlags) {
        this.sys = sys;
        final MemorySegment p = arena.allocate(PARAMS_BYTES, 8);
        long r = -EINVAL;
        if (setupFlags != 0) {
            p.set(ValueLayout.JAVA_INT, 8, setupFlags);
            r = sys.call(Sys.SYS_IO_URING_SETUP, entries, p.address(), 0);
        }
        if (r == -EINVAL) {
            p.fill((byte) 0);
            r = sys.call(Sys.SYS_IO_URING_SETUP, entries, p.address(), 0);
        }
        if (r < 0) {
            throw new IllegalStateException("io_uring_setup failed, errno " + -r);
        }
        this.fd = (int) r;
        this.setupFlags = p.get(ValueLayout.JAVA_INT, 8);
        this.sqEntries = p.get(ValueLayout.JAVA_INT, 0);
        final int cqEntries = p.get(ValueLayout.JAVA_INT, 4);
        final int features = p.get(ValueLayout.JAVA_INT, 20);
        this.sqHeadOff = p.get(ValueLayout.JAVA_INT, 40);
        this.sqTailOff = p.get(ValueLayout.JAVA_INT, 44);
        final long sqArrayOff = p.get(ValueLayout.JAVA_INT, 64);
        this.cqHeadOff = p.get(ValueLayout.JAVA_INT, 80);
        this.cqTailOff = p.get(ValueLayout.JAVA_INT, 84);
        final long cqMaskOff = p.get(ValueLayout.JAVA_INT, 88);
        this.cqesOff = p.get(ValueLayout.JAVA_INT, 100);

        long sqBytes = sqArrayOff + (long) sqEntries * 4;
        long cqBytes = cqesOff + (long) cqEntries * CQE_BYTES;
        if ((features & FEAT_SINGLE_MMAP) != 0) {
            sqBytes = Math.max(sqBytes, cqBytes);
            cqBytes = sqBytes;
        }
        this.mapSqBytes = sqBytes;
        this.mapCqBytes = cqBytes;
        this.mapSqeBytes = (long) sqEntries * SQE_BYTES;
        this.sqRing = map(sqBytes, OFF_SQ_RING);
        this.cqRing = (features & FEAT_SINGLE_MMAP) != 0 ? sqRing : map(cqBytes, 0x8000000L);
        this.sqes = map(mapSqeBytes, OFF_SQES);
        this.cqMask = cqRing.get(ValueLayout.JAVA_INT, cqMaskOff) & 0xFFFFFFFFL;
        // identity SQ index array: SQE i is always slot i
        for (int i = 0; i < sqEntries; i++) {
            sqRing.set(ValueLayout.JAVA_INT, sqArrayOff + (long) i * 4, i);
        }
        this.sqTail = sqRing.get(ValueLayout.JAVA_INT, sqTailOff);
        this.submitted = sqTail;
        this.enterArg = arena.allocate(40, 8);
        // take the timeout branch once, so the JIT profile never treats it as untaken (a deopt there would allocate)
        submitAndWait(1_000_000L);
    }

    private MemorySegment map(long bytes, long offset) {
        final long addr = sys.call(Sys.SYS_MMAP, 0, bytes, PROT_READ_WRITE, MAP_SHARED_POPULATE, fd, offset);
        if (addr < 0 && addr > -4096) {
            throw new IllegalStateException("mmap of io_uring failed, errno " + -addr);
        }
        return MemorySegment.ofAddress(addr).reinterpret(bytes);
    }

    int sqEntries() {
        return sqEntries;
    }

    /** Setup flags the kernel actually accepted. */
    int setupFlags() {
        return setupFlags;
    }

    /** SQEs queued but not yet handed to the kernel. */
    int pending() {
        return sqTail - submitted;
    }

    private long nextSqe() {
        if (sqTail - sqRing.get(ValueLayout.JAVA_INT, sqHeadOff) >= sqEntries) {
            enter(0, 0, 0);
        }
        final long off = (long) (sqTail & (sqEntries - 1)) * SQE_BYTES;
        for (int i = 0; i < SQE_BYTES; i += 8) {
            sqes.set(ValueLayout.JAVA_LONG, off + i, 0L);
        }
        return off;
    }

    private void publish() {
        sqTail++;
        VarHandle.releaseFence();
        sqRing.set(ValueLayout.JAVA_INT, sqTailOff, sqTail);
    }

    private void prep(int opcode, int fd, long addr, int len, int opFlags, long userData) {
        final long off = nextSqe();
        sqes.set(ValueLayout.JAVA_BYTE, off, (byte) opcode);
        sqes.set(ValueLayout.JAVA_INT, off + 4, fd);
        sqes.set(ValueLayout.JAVA_LONG, off + 16, addr);
        sqes.set(ValueLayout.JAVA_INT, off + 24, len);
        sqes.set(ValueLayout.JAVA_INT, off + 28, opFlags);
        sqes.set(ValueLayout.JAVA_LONG, off + 32, userData);
        publish();
    }

    void prepNop(long userData) {
        prep(OP_NOP, -1, 0, 0, 0, userData);
    }

    void prepAccept(int listenFd, long userData) {
        prep(OP_ACCEPT, listenFd, 0, 0, SOCK_CLOEXEC, userData);
    }

    void prepRecv(int sockFd, long addr, int len, long userData) {
        prep(OP_RECV, sockFd, addr, len, 0, userData);
    }

    void prepSend(int sockFd, long addr, int len, long userData) {
        prep(OP_SEND, sockFd, addr, len, MSG_NOSIGNAL, userData);
    }

    /** Submits the queued SQEs and, if {@code waitNanos > 0}, blocks until a completion arrives or the time elapses. */
    void submitAndWait(long waitNanos) {
        if (waitNanos > 0 && cqReady() == 0) {
            enterArg.set(ValueLayout.JAVA_LONG, 0, 0L);
            enterArg.set(ValueLayout.JAVA_INT, 8, 0);
            enterArg.set(ValueLayout.JAVA_INT, 12, 0);
            enterArg.set(ValueLayout.JAVA_LONG, 16, enterArg.address() + 24);
            enterArg.set(ValueLayout.JAVA_LONG, 24, waitNanos / 1_000_000_000L);
            enterArg.set(ValueLayout.JAVA_LONG, 32, waitNanos % 1_000_000_000L);
            enter(1, ENTER_GETEVENTS | ENTER_EXT_ARG, 24);
        } else if (pending() > 0 || cqReady() == 0) {
            // submit what is queued; with nothing ready this also lets the kernel run its pending completion work
            enter(0, ENTER_GETEVENTS, 0);
        }
    }

    private void enter(int minComplete, int flags, int argSize) {
        final int toSubmit = sqTail - submitted;
        final long arg = argSize == 0 ? 0 : enterArg.address();
        final long r = sys.call(Sys.SYS_IO_URING_ENTER, fd, toSubmit, minComplete, flags, arg, argSize);
        if (r >= 0) {
            submitted += (int) r;
        } else if (r != -EINTR && r != -ETIME) {
            throw new IllegalStateException("io_uring_enter failed, errno " + -r);
        }
    }

    private int cqReady() {
        final int tail = cqRing.get(ValueLayout.JAVA_INT, cqTailOff);
        VarHandle.acquireFence();
        return tail - cqRing.get(ValueLayout.JAVA_INT, cqHeadOff);
    }

    /** True when a completion is available; read it with the {@code cqe*} accessors, then {@link #cqeDone()}. */
    boolean cqePeek() {
        return cqReady() != 0;
    }

    private long cqeOff() {
        return cqesOff + (cqRing.get(ValueLayout.JAVA_INT, cqHeadOff) & cqMask) * CQE_BYTES;
    }

    long cqeUserData() {
        return cqRing.get(ValueLayout.JAVA_LONG, cqeOff());
    }

    int cqeRes() {
        return cqRing.get(ValueLayout.JAVA_INT, cqeOff() + 8);
    }

    void cqeDone() {
        VarHandle.releaseFence();
        cqRing.set(ValueLayout.JAVA_INT, cqHeadOff, cqRing.get(ValueLayout.JAVA_INT, cqHeadOff) + 1);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        sys.call(Sys.SYS_MUNMAP, sqes.address(), mapSqeBytes, 0);
        if (cqRing.address() != sqRing.address()) {
            sys.call(Sys.SYS_MUNMAP, cqRing.address(), mapCqBytes, 0);
        }
        sys.call(Sys.SYS_MUNMAP, sqRing.address(), mapSqBytes, 0);
        sys.call(Sys.SYS_CLOSE, fd, 0, 0);
    }
}
