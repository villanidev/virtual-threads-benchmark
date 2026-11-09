package dev.villani.server.uring;

import java.lang.invoke.VarHandle;

/**
 * Minimal io_uring: the SQ/CQ rings and the SQE array are mapped once and driven with plain memory accesses through
 * {@link Kernel}, so submitting and reaping operations allocates nothing. Single issuer, no SQPOLL. Offsets follow
 * {@code struct io_uring_params}, {@code io_uring_sqe} and {@code io_uring_cqe} of the Linux uapi (x86_64).
 */
final class Ring implements AutoCloseable {

    static final int OP_NOP = 0;
    static final int OP_ACCEPT = 13;
    static final int OP_SEND = 26;
    static final int OP_RECV = 27;

    static final int SETUP_COOP_TASKRUN = 1 << 8;
    static final int SETUP_SINGLE_ISSUER = 1 << 12;
    static final int SETUP_DEFER_TASKRUN = 1 << 13;

    private static final int PARAMS_BYTES = 120;
    private static final int SQE_BYTES = 64;
    private static final int CQE_BYTES = 16;
    private static final long OFF_SQ_RING = 0L;
    private static final long OFF_CQ_RING = 0x8000000L;
    private static final long OFF_SQES = 0x10000000L;
    private static final int FEAT_SINGLE_MMAP = 1;
    private static final int ENTER_GETEVENTS = 1;
    private static final int ENTER_EXT_ARG = 1 << 3;
    private static final int PROT_READ_WRITE = 3;
    private static final int MAP_SHARED_POPULATE = 0x01 | 0x8000;
    private static final int EINTR = 4;
    private static final int EINVAL = 22;
    private static final int ETIME = 62;
    private static final int MSG_NOSIGNAL = 0x4000;
    private static final int SOCK_CLOEXEC = 0x80000;

    private final Kernel k;
    private final int fd;
    private final int setupFlags;
    private final int sqEntries;
    private final long cqMask;
    private final long sqRing;
    private final long cqRing;
    private final long sqes;
    private final long sqHeadAddr;
    private final long sqTailAddr;
    private final long cqHeadAddr;
    private final long cqTailAddr;
    private final long cqesAddr;
    private final long mapSqBytes;
    private final long mapCqBytes;
    private final long mapSqeBytes;
    private final long enterArg;
    private int sqTail;
    private int submitted;
    private boolean closed;

    Ring(Kernel kernel, int entries) {
        this(kernel, entries, 0);
    }

    /** {@code setupFlags} are best effort: kernels that reject them (EINVAL) get a plain ring. */
    Ring(Kernel kernel, int entries, int setupFlags) {
        this.k = kernel;
        final long p = k.alloc(PARAMS_BYTES);
        long r = -EINVAL;
        if (setupFlags != 0) {
            k.putInt(p + 8, setupFlags);
            r = k.syscall(Kernel.SYS_IO_URING_SETUP, entries, p, 0);
        }
        if (r == -EINVAL) {
            for (int i = 0; i < PARAMS_BYTES; i += 8) {
                k.putLong(p + i, 0L);
            }
            r = k.syscall(Kernel.SYS_IO_URING_SETUP, entries, p, 0);
        }
        if (r < 0) {
            throw new IllegalStateException("io_uring_setup failed, errno " + -r);
        }
        this.fd = (int) r;
        this.setupFlags = k.getInt(p + 8);
        this.sqEntries = k.getInt(p);
        final int cqEntries = k.getInt(p + 4);
        final int features = k.getInt(p + 20);
        final long sqHeadOff = k.getInt(p + 40);
        final long sqTailOff = k.getInt(p + 44);
        final long sqArrayOff = k.getInt(p + 64);
        final long cqHeadOff = k.getInt(p + 80);
        final long cqTailOff = k.getInt(p + 84);
        final long cqMaskOff = k.getInt(p + 88);
        final long cqesOff = k.getInt(p + 100);

        long sqBytes = sqArrayOff + (long) sqEntries * 4;
        long cqBytes = cqesOff + (long) cqEntries * CQE_BYTES;
        final boolean single = (features & FEAT_SINGLE_MMAP) != 0;
        if (single) {
            sqBytes = Math.max(sqBytes, cqBytes);
            cqBytes = sqBytes;
        }
        this.mapSqBytes = sqBytes;
        this.mapCqBytes = cqBytes;
        this.mapSqeBytes = (long) sqEntries * SQE_BYTES;
        this.sqRing = map(sqBytes, OFF_SQ_RING);
        this.cqRing = single ? sqRing : map(cqBytes, OFF_CQ_RING);
        this.sqes = map(mapSqeBytes, OFF_SQES);
        this.sqHeadAddr = sqRing + sqHeadOff;
        this.sqTailAddr = sqRing + sqTailOff;
        this.cqHeadAddr = cqRing + cqHeadOff;
        this.cqTailAddr = cqRing + cqTailOff;
        this.cqesAddr = cqRing + cqesOff;
        this.cqMask = k.getInt(cqRing + cqMaskOff) & 0xFFFFFFFFL;
        // identity SQ index array: SQE i is always slot i
        for (int i = 0; i < sqEntries; i++) {
            k.putInt(sqRing + sqArrayOff + (long) i * 4, i);
        }
        this.sqTail = k.getInt(sqTailAddr);
        this.submitted = sqTail;
        this.enterArg = k.alloc(40);
        // take the timeout branch once, so the JIT profile never treats it as untaken (a deopt there would allocate)
        submitAndWait(1_000_000L);
    }

    private long map(long bytes, long offset) {
        final long addr = k.syscall(Kernel.SYS_MMAP, 0, bytes, PROT_READ_WRITE, MAP_SHARED_POPULATE, fd, offset);
        if (addr < 0 && addr > -4096) {
            throw new IllegalStateException("mmap of io_uring failed, errno " + -addr);
        }
        return addr;
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
        if (sqTail - k.getInt(sqHeadAddr) >= sqEntries) {
            enter(0, 0, 0, false);
        }
        final long addr = sqes + (long) (sqTail & (sqEntries - 1)) * SQE_BYTES;
        for (int i = 0; i < SQE_BYTES; i += 8) {
            k.putLong(addr + i, 0L);
        }
        return addr;
    }

    private void publish() {
        sqTail++;
        VarHandle.releaseFence();
        k.putInt(sqTailAddr, sqTail);
    }

    private void prep(int opcode, int sockFd, long addr, int len, int opFlags, long userData) {
        final long sqe = nextSqe();
        k.putByte(sqe, (byte) opcode);
        k.putInt(sqe + 4, sockFd);
        k.putLong(sqe + 16, addr);
        k.putInt(sqe + 24, len);
        k.putInt(sqe + 28, opFlags);
        k.putLong(sqe + 32, userData);
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
            k.putLong(enterArg, 0L);
            k.putInt(enterArg + 8, 0);
            k.putInt(enterArg + 12, 0);
            k.putLong(enterArg + 16, enterArg + 24);
            k.putLong(enterArg + 24, waitNanos / 1_000_000_000L);
            k.putLong(enterArg + 32, waitNanos % 1_000_000_000L);
            enter(1, ENTER_GETEVENTS | ENTER_EXT_ARG, 24, true);
        } else if (pending() > 0 || cqReady() == 0) {
            // submit what is queued; with nothing ready this also lets the kernel run its pending completion work
            enter(0, ENTER_GETEVENTS, 0, false);
        }
    }

    private void enter(int minComplete, int flags, int argSize, boolean blocking) {
        final int toSubmit = sqTail - submitted;
        final long arg = argSize == 0 ? 0 : enterArg;
        final long r = blocking
                ? k.blockingSyscall(Kernel.SYS_IO_URING_ENTER, fd, toSubmit, minComplete, flags, arg, argSize)
                : k.syscall(Kernel.SYS_IO_URING_ENTER, fd, toSubmit, minComplete, flags, arg, argSize);
        if (r >= 0) {
            submitted += (int) r;
        } else if (r != -EINTR && r != -ETIME) {
            throw new IllegalStateException("io_uring_enter failed, errno " + -r);
        }
    }

    private int cqReady() {
        final int tail = k.getInt(cqTailAddr);
        VarHandle.acquireFence();
        return tail - k.getInt(cqHeadAddr);
    }

    /** True when a completion is available; read it with the {@code cqe*} accessors, then {@link #cqeDone()}. */
    boolean cqePeek() {
        return cqReady() != 0;
    }

    private long cqeAddr() {
        return cqesAddr + (k.getInt(cqHeadAddr) & cqMask) * CQE_BYTES;
    }

    long cqeUserData() {
        return k.getLong(cqeAddr());
    }

    int cqeRes() {
        return k.getInt(cqeAddr() + 8);
    }

    void cqeDone() {
        VarHandle.releaseFence();
        k.putInt(cqHeadAddr, k.getInt(cqHeadAddr) + 1);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        k.syscall(Kernel.SYS_MUNMAP, sqes, mapSqeBytes, 0);
        if (cqRing != sqRing) {
            k.syscall(Kernel.SYS_MUNMAP, cqRing, mapCqBytes, 0);
        }
        k.syscall(Kernel.SYS_MUNMAP, sqRing, mapSqBytes, 0);
        k.syscall(Kernel.SYS_CLOSE, fd, 0, 0);
    }
}
