package dev.villani.server.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.function.Consumer;

import dev.villani.server.memory.ByteRing;
import dev.villani.server.memory.ConnectionPool;
import dev.villani.server.memory.ReadyQueue;

/**
 * Single-threaded non-blocking TCP server (thread-per-core). Connection state lives in a {@link ConnectionPool}
 * slot; the selector is driven with {@code select(Consumer)} so no selected-key set or iterator is created.
 * <p>
 * Backpressure: when every slot is in use the server stops accepting (pending connections wait in the kernel
 * backlog) and resumes when a slot is released. A connection whose response cannot be fully written stops being
 * read until the response drains. A connection whose read ring is full after the handler ran is closed.
 * <p>
 * Allocation: only what the JDK allocates per connection (channel, selection key) and at start-up.
 */
public final class NioServer implements AutoCloseable {

    /**
     * Event loop: é single-thread, com Selector, accept, leitura, escrita e backpressure.
     * Zero alocação: o loop em regime estável, numa conexão keep-alive já estabelecida, aloca 0 bytes (medido em 20 mil iterações).
     * Custo por conexão: o JDK ainda aloca por conexão nova (SocketChannelImpl e SelectionKey). Isso não dá para evitar com NIO.
     */

    private final ConnectionPool pool;
    private final ConnectionHandler handler;
    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final SelectionKey serverKey;
    private final SocketChannel[] channels;
    private final SelectionKey[] keys;
    private final Integer[] slotIds;
    private final boolean[] writePending;
    private final boolean[] closeAfterWrite;
    private final ReadyQueue ready;
    private final Consumer<SelectionKey> onKey = this::onKey;
    private boolean acceptPaused;
    private volatile boolean running;

    public NioServer(String host, int port, ConnectionPool pool, ConnectionHandler handler) throws IOException {
        this.pool = pool;
        this.handler = handler;
        final int n = pool.capacity();
        this.channels = new SocketChannel[n];
        this.keys = new SelectionKey[n];
        this.slotIds = new Integer[n];
        this.writePending = new boolean[n];
        this.closeAfterWrite = new boolean[n];
        this.ready = new ReadyQueue(n);
        for (int i = 0; i < n; i++) {
            slotIds[i] = i;
        }
        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(host, port));
        this.serverKey = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    public int localPort() throws IOException {
        return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
    }

    public int activeConnections() {
        return pool.active();
    }

    public boolean isWritePending(int slot) {
        return writePending[slot];
    }

    public boolean isOpen(int slot) {
        return pool.isInUse(slot);
    }

    public ByteRing readRing(int slot) {
        return pool.readRing(slot);
    }

    /** Response buffer of the slot, always in write mode (position = bytes already staged). */
    public ByteBuffer writeBuffer(int slot) {
        return pool.writeBuffer(slot);
    }

    /** Runs the loop until {@link #stop()} is called. */
    public void run() throws IOException {
        running = true;
        while (running) {
            runOnce(1000);
        }
    }

    public void stop() {
        running = false;
        selector.wakeup();
    }

    /** One loop iteration: wait up to {@code timeoutMs} for I/O, handle it, then dispatch ready connections. */
    public void runOnce(long timeoutMs) throws IOException {
        final long wait = Math.min(timeoutMs, handler.maxWaitMs());
        if (wait <= 0) {
            selector.selectNow(onKey);
        } else {
            selector.select(onKey, wait);
        }
        dispatchReady();
        handler.onTick(this);
        dispatchReady();
    }

    /** Sends the staged response bytes and closes the connection once they have all been written. */
    public void flushAndClose(int slot) {
        flush(slot);
        if (!pool.isInUse(slot)) {
            return;
        }
        if (writePending[slot]) {
            closeAfterWrite[slot] = true;
        } else {
            close(slot);
        }
    }

    /** Sends the staged response bytes; if the socket cannot take them all, reading pauses until it drains. */
    public void flush(int slot) {
        final ByteBuffer out = pool.writeBuffer(slot);
        out.flip();
        try {
            channels[slot].write(out);
        } catch (IOException e) {
            close(slot);
            return;
        }
        if (out.hasRemaining()) {
            out.compact();
            writePending[slot] = true;
            keys[slot].interestOps(SelectionKey.OP_WRITE);
        } else {
            out.clear();
        }
    }

    public void close(int slot) {
        if (!pool.isInUse(slot)) {
            return;
        }
        handler.onClosed(slot);
        keys[slot].cancel();
        try {
            channels[slot].close();
        } catch (IOException ignored) {
            // closing anyway
        }
        channels[slot] = null;
        keys[slot] = null;
        writePending[slot] = false;
        closeAfterWrite[slot] = false;
        pool.release(slot);
        if (acceptPaused) {
            acceptPaused = false;
            serverKey.interestOps(SelectionKey.OP_ACCEPT);
        }
    }

    @Override
    public void close() throws IOException {
        for (int slot = 0; slot < channels.length; slot++) {
            close(slot);
        }
        serverChannel.close();
        selector.close();
    }

    private void onKey(SelectionKey key) {
        if (key == serverKey) {
            acceptAll();
            return;
        }
        final int slot = (Integer) key.attachment();
        try {
            if (key.isValid() && key.isReadable()) {
                onReadable(slot);
            } else if (key.isValid() && key.isWritable()) {
                onWritable(slot);
            }
        } catch (IOException e) {
            close(slot);
        }
    }

    private void acceptAll() {
        try {
            while (true) {
                if (pool.active() == pool.capacity()) {
                    acceptPaused = true;
                    serverKey.interestOps(0);
                    return;
                }
                final SocketChannel ch = serverChannel.accept();
                if (ch == null) {
                    return;
                }
                ch.configureBlocking(false);
                ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
                final int slot = pool.acquire();
                channels[slot] = ch;
                keys[slot] = ch.register(selector, SelectionKey.OP_READ, slotIds[slot]);
            }
        } catch (IOException e) {
            // failed accept: the listener stays up, the connection is dropped by the JDK
        }
    }

    private void onReadable(int slot) throws IOException {
        final int n = pool.readRing(slot).readFrom(channels[slot]);
        if (n < 0) {
            close(slot);
        } else if (n > 0) {
            ready.markReady(slot);
        }
    }

    private void onWritable(int slot) {
        final ByteBuffer out = pool.writeBuffer(slot);
        out.flip();
        try {
            channels[slot].write(out);
        } catch (IOException e) {
            close(slot);
            return;
        }
        if (out.hasRemaining()) {
            out.compact();
            return;
        }
        out.clear();
        writePending[slot] = false;
        if (closeAfterWrite[slot]) {
            close(slot);
            return;
        }
        keys[slot].interestOps(SelectionKey.OP_READ);
        if (pool.readRing(slot).readable() > 0) {
            ready.markReady(slot);
        }
    }

    private void dispatchReady() {
        int slot;
        while ((slot = ready.next()) != ReadyQueue.NONE) {
            if (!pool.isInUse(slot) || writePending[slot]) {
                continue;
            }
            handler.onReadable(this, slot);
            if (pool.isInUse(slot) && !writePending[slot] && pool.readRing(slot).writable() == 0) {
                close(slot);
            }
        }
    }
}
