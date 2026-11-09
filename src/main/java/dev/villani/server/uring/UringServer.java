package dev.villani.server.uring;

import dev.villani.server.memory.ByteRing;
import dev.villani.server.memory.ConnectionPool;
import dev.villani.server.memory.ReadyQueue;
import dev.villani.server.net.ConnectionHandler;
import dev.villani.server.net.ConnectionIo;
import dev.villani.server.net.ServerLoop;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * Single-threaded TCP server over io_uring (same contract as {@code NioServer}, so the same
 * {@link ConnectionHandler} runs on both). One ring does accept, recv and send; completions are reaped from shared
 * memory and one {@code io_uring_enter} per loop iteration submits and waits, with the timer-wheel deadline as the
 * wait timeout. Receives land directly in the connection {@link ByteRing} (no copy) and sends read straight from the
 * connection write buffer.
 * <p>
 * Backpressure is the NIO one: accept is only armed while a slot is free, a connection is not read while a send
 * is in flight, and a connection whose ring is full after the handler ran is closed. A slot goes back to the pool
 * only after every operation it had in flight completed, because the kernel still owns its buffers until then.
 * <p>
 * Allocation: none per request or per connection (no channel or selection key objects exist); only start-up.
 */
public final class UringServer implements ConnectionIo, ServerLoop {

    private static final int OP_ACCEPT = 1;
    private static final int OP_RECV = 2;
    private static final int OP_SEND = 3;
    private static final int RING_ENTRIES = 1024;
    private static final long NANOS_PER_MS = 1_000_000L;

    private final ConnectionPool pool;
    private final ConnectionHandler handler;
    private final Arena arena = Arena.ofShared();
    private final Sys sys = new Sys(arena);
    private final Ring ring;
    private final int listenFd;
    private final int localPort;
    private final int[] fd;
    private final int[] sendLen;
    private final byte[] inflight;
    private final boolean[] recvArmed;
    private final boolean[] writePending;
    private final boolean[] closeAfterWrite;
    private final boolean[] closing;
    private final long[] ringAddr;
    private final long[] writeAddr;
    private final ReadyQueue ready;
    private boolean acceptArmed;
    private volatile boolean running;

    public UringServer(String host, int port, ConnectionPool pool, ConnectionHandler handler) throws IOException {
        this.pool = pool;
        this.handler = handler;
        final int n = pool.capacity();
        if (2 * n + 1 > RING_ENTRIES) {
            throw new IllegalArgumentException("too many connections for the ring: " + n);
        }
        this.fd = new int[n];
        this.sendLen = new int[n];
        this.inflight = new byte[n];
        this.recvArmed = new boolean[n];
        this.writePending = new boolean[n];
        this.closeAfterWrite = new boolean[n];
        this.closing = new boolean[n];
        this.ringAddr = new long[n];
        this.writeAddr = new long[n];
        this.ready = new ReadyQueue(n);
        for (int i = 0; i < n; i++) {
            ringAddr[i] = MemorySegment.ofBuffer(pool.readRing(i).backing()).address();
            writeAddr[i] = MemorySegment.ofBuffer(pool.writeBuffer(i)).address();
        }
        this.ring = new Ring(sys, arena, RING_ENTRIES);
        this.listenFd = listen(host, port);
        this.localPort = boundPort();
    }

    private int listen(String host, int port) throws IOException {
        final MemorySegment tmp = arena.allocate(16, 4);
        final int s = (int) check(sys.call(Sys.SYS_SOCKET, 2, 1 | 0x80000, 0), "socket");
        tmp.set(ValueLayout.JAVA_INT, 0, 1);
        check(sys.call(Sys.SYS_SETSOCKOPT, s, 1, 2, tmp.address(), 4, 0), "SO_REUSEADDR");
        // TCP_NODELAY on the listener is inherited by every accepted socket
        check(sys.call(Sys.SYS_SETSOCKOPT, s, 6, 1, tmp.address(), 4, 0), "TCP_NODELAY");
        final byte[] ip = InetAddress.getByName(host).getAddress();
        tmp.fill((byte) 0);
        tmp.set(ValueLayout.JAVA_SHORT, 0, (short) 2); // AF_INET
        tmp.set(ValueLayout.JAVA_SHORT, 2, Short.reverseBytes((short) port));
        for (int i = 0; i < 4; i++) {
            tmp.set(ValueLayout.JAVA_BYTE, 4 + i, ip[i]);
        }
        check(sys.call(Sys.SYS_BIND, s, tmp.address(), 16), "bind");
        check(sys.call(Sys.SYS_LISTEN, s, 1024, 0), "listen");
        return s;
    }

    private int boundPort() throws IOException {
        final MemorySegment tmp = arena.allocate(24, 4);
        tmp.set(ValueLayout.JAVA_INT, 16, 16);
        check(sys.call(Sys.SYS_GETSOCKNAME, listenFd, tmp.address(), tmp.address() + 16), "getsockname");
        return Short.reverseBytes(tmp.get(ValueLayout.JAVA_SHORT, 2)) & 0xFFFF;
    }

    private static long check(long r, String what) throws IOException {
        if (r < 0) {
            throw new IOException(what + " failed, errno " + -r);
        }
        return r;
    }

    public int localPort() {
        return localPort;
    }

    public int activeConnections() {
        return pool.active();
    }

    @Override
    public ByteRing readRing(int slot) {
        return pool.readRing(slot);
    }

    @Override
    public ByteBuffer writeBuffer(int slot) {
        return pool.writeBuffer(slot);
    }

    @Override
    public boolean isWritePending(int slot) {
        return writePending[slot];
    }

    @Override
    public boolean isOpen(int slot) {
        return pool.isInUse(slot) && !closing[slot];
    }

    /** Runs the loop until {@link #stop()} is called (observed within 100 ms). */
    public void run() {
        running = true;
        while (running) {
            runOnce(100);
        }
    }

    public void stop() {
        running = false;
    }

    /** One loop iteration: submit, wait up to {@code timeoutMs} for completions, handle them, then dispatch. */
    public void runOnce(long timeoutMs) {
        armAccept();
        final long wait = Math.min(timeoutMs, handler.maxWaitMs());
        ring.submitAndWait(Math.max(wait, 0) * NANOS_PER_MS);
        reap();
        dispatchReady();
        handler.onTick(this);
        dispatchReady();
    }

    private static long userData(int op, int slot) {
        return op | ((long) slot << 4);
    }

    private void armAccept() {
        if (!acceptArmed && pool.active() < pool.capacity()) {
            ring.prepAccept(listenFd, userData(OP_ACCEPT, 0));
            acceptArmed = true;
        }
    }

    private void armRecv(int slot) {
        final ByteRing in = pool.readRing(slot);
        final int len = in.contiguousWritable();
        if (len == 0 || recvArmed[slot]) {
            return;
        }
        ring.prepRecv(fd[slot], ringAddr[slot] + in.tail(), len, userData(OP_RECV, slot));
        recvArmed[slot] = true;
        inflight[slot]++;
    }

    private void armSend(int slot, int len) {
        sendLen[slot] = len;
        ring.prepSend(fd[slot], writeAddr[slot], len, userData(OP_SEND, slot));
        writePending[slot] = true;
        inflight[slot]++;
    }

    @Override
    public void flush(int slot) {
        if (!isOpen(slot) || writePending[slot]) {
            return;
        }
        final int staged = pool.writeBuffer(slot).position();
        if (staged > 0) {
            armSend(slot, staged);
        }
    }

    @Override
    public void flushAndClose(int slot) {
        flush(slot);
        if (!isOpen(slot)) {
            return;
        }
        if (writePending[slot]) {
            closeAfterWrite[slot] = true;
        } else {
            close(slot);
        }
    }

    @Override
    public void close(int slot) {
        if (!isOpen(slot)) {
            return;
        }
        closing[slot] = true;
        handler.onClosed(slot);
        // shutdown completes any recv/send still in flight; the file reference they hold keeps the socket alive until then
        sys.call(Sys.SYS_SHUTDOWN, fd[slot], 2, 0);
        sys.call(Sys.SYS_CLOSE, fd[slot], 0, 0);
        fd[slot] = -1;
        writePending[slot] = false;
        closeAfterWrite[slot] = false;
        releaseIfIdle(slot);
    }

    private void releaseIfIdle(int slot) {
        if (closing[slot] && inflight[slot] == 0) {
            closing[slot] = false;
            recvArmed[slot] = false;
            pool.release(slot);
        }
    }

    @Override
    public void close() {
        for (int slot = 0; slot < fd.length; slot++) {
            if (pool.isInUse(slot) && fd[slot] >= 0) {
                sys.call(Sys.SYS_CLOSE, fd[slot], 0, 0);
                fd[slot] = -1;
            }
        }
        sys.call(Sys.SYS_CLOSE, listenFd, 0, 0);
        ring.close();
        arena.close();
    }

    private void reap() {
        while (ring.cqePeek()) {
            final long ud = ring.cqeUserData();
            final int res = ring.cqeRes();
            ring.cqeDone();
            final int slot = (int) (ud >>> 4);
            switch ((int) (ud & 0xF)) {
                case OP_ACCEPT -> onAccept(res);
                case OP_RECV -> onRecv(slot, res);
                case OP_SEND -> onSend(slot, res);
                default -> {
                }
            }
        }
    }

    private void onAccept(int res) {
        acceptArmed = false;
        if (res < 0) {
            return;
        }
        final int slot = pool.acquire();
        if (slot == ConnectionPool.NONE) {
            sys.call(Sys.SYS_CLOSE, res, 0, 0);
            return;
        }
        fd[slot] = res;
        inflight[slot] = 0;
        recvArmed[slot] = false;
        writePending[slot] = false;
        closeAfterWrite[slot] = false;
        closing[slot] = false;
        armRecv(slot);
    }

    private void onRecv(int slot, int res) {
        inflight[slot]--;
        recvArmed[slot] = false;
        if (closing[slot]) {
            releaseIfIdle(slot);
        } else if (res > 0) {
            pool.readRing(slot).commit(res);
            ready.markReady(slot);
        } else {
            close(slot);
        }
    }

    private void onSend(int slot, int res) {
        inflight[slot]--;
        if (closing[slot]) {
            releaseIfIdle(slot);
            return;
        }
        if (res <= 0) {
            close(slot);
            return;
        }
        // keep what was not sent plus anything staged while the send was in flight
        final ByteBuffer out = pool.writeBuffer(slot);
        out.flip();
        out.position(res);
        out.compact();
        if (out.position() > 0) {
            armSend(slot, out.position());
            return;
        }
        writePending[slot] = false;
        if (closeAfterWrite[slot]) {
            close(slot);
            return;
        }
        if (pool.readRing(slot).readable() > 0) {
            ready.markReady(slot);
        }
        armRecv(slot);
    }

    private void dispatchReady() {
        int slot;
        while ((slot = ready.next()) != ReadyQueue.NONE) {
            if (!isOpen(slot) || writePending[slot]) {
                continue;
            }
            handler.onReadable(this, slot);
            if (isOpen(slot) && !writePending[slot]) {
                if (pool.readRing(slot).writable() == 0) {
                    close(slot);
                } else {
                    armRecv(slot);
                }
            }
        }
    }
}
