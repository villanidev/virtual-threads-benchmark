package dev.villani.server.http;

import dev.villani.server.memory.ByteRing;
import dev.villani.server.net.ConnectionHandler;
import dev.villani.server.net.NioServer;
import dev.villani.server.time.TimerWheel;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Bridges the NIO loop and the HTTP {@link Dispatcher}: parses pipelined requests, stages responses
 * in the connection write buffer and handles deferred (async) responses with a {@link TimerWheel}.
 * While a response is deferred the connection is not read further, which preserves response order.
 * Limit: a response that does not fit the write buffer even after a flush closes the connection.
 */
public final class HttpConnectionHandler implements ConnectionHandler {

    private final Dispatcher dispatcher;
    private final HttpResponse response;
    private final TimerWheel wheel;
    private final LongSupplier clockMs;
    private final boolean[] deferred;
    private final int[] timerEntry;
    private final int[] deferredHandler;
    private final boolean[] deferredKeepAlive;

    public HttpConnectionHandler(Dispatcher dispatcher, HttpResponse response, TimerWheel wheel,
                                 LongSupplier clockMs, int maxConnections) {
        this.dispatcher = dispatcher;
        this.response = response;
        this.wheel = wheel;
        this.clockMs = clockMs;
        this.deferred = new boolean[maxConnections];
        this.timerEntry = new int[maxConnections];
        this.deferredHandler = new int[maxConnections];
        this.deferredKeepAlive = new boolean[maxConnections];
        Arrays.fill(timerEntry, TimerWheel.NONE);
    }

    @Override
    public void onReadable(NioServer server, int slot) {
        if (deferred[slot]) {
            return;
        }
        pump(server, slot);
    }

    @Override
    public void onTick(NioServer server) {
        wheel.advanceTo(clockMs.getAsLong());
        int slot;
        while ((slot = wheel.pollExpired()) != TimerWheel.NONE) {
            if (!deferred[slot]) {
                continue;
            }
            deferred[slot] = false;
            timerEntry[slot] = TimerWheel.NONE;
            dispatcher.complete(slot, deferredHandler[slot], deferredKeepAlive[slot], response);
            if (!stage(server, slot)) {
                continue;
            }
            if (!response.keepAlive()) {
                server.flushAndClose(slot);
            } else {
                pump(server, slot);
            }
        }
    }

    @Override
    public void onClosed(int slot) {
        if (deferred[slot]) {
            deferred[slot] = false;
            wheel.cancel(timerEntry[slot]);
            timerEntry[slot] = TimerWheel.NONE;
            dispatcher.abort(slot, deferredHandler[slot]);
        }
    }

    @Override
    public long maxWaitMs() {
        return wheel.nextDueInMs(clockMs.getAsLong());
    }

    private void pump(NioServer server, int slot) {
        final ByteRing ring = server.readRing(slot);
        while (true) {
            final int result = dispatcher.process(slot, ring, response);
            if (result == Dispatcher.NEED_MORE) {
                break;
            }
            if (result == Dispatcher.DEFERRED && defer(slot)) {
                break;
            }
            if (!stage(server, slot)) {
                return;
            }
            if (!response.keepAlive()) {
                server.flushAndClose(slot);
                return;
            }
        }
        final ByteBuffer out = server.writeBuffer(slot);
        if (out.position() > 0 && !server.isWritePending(slot)) {
            server.flush(slot);
        }
    }

    /** Schedules the completion; if the wheel is full the handler is aborted and a 503 replaces the response. */
    private boolean defer(int slot) {
        final int handler = dispatcher.deferredHandler();
        final boolean keepAlive = dispatcher.deferredKeepAlive();
        final int delay = (int) Math.min(response.deferMs(), wheel.maxDelayMs());
        final int entry = wheel.schedule(clockMs.getAsLong(), delay, slot);
        if (entry == TimerWheel.NONE) {
            dispatcher.abort(slot, handler);
            response.reset();
            response.status(503).keepAlive(keepAlive);
            return false;
        }
        deferred[slot] = true;
        timerEntry[slot] = entry;
        deferredHandler[slot] = handler;
        deferredKeepAlive[slot] = keepAlive;
        return true;
    }

    /** Serialises the response into the slot buffer. Returns false if the connection had to be closed. */
    private boolean stage(NioServer server, int slot) {
        final ByteBuffer out = server.writeBuffer(slot);
        if (ResponseWriter.write(response, out)) {
            return true;
        }
        server.flush(slot);
        if (server.isOpen(slot) && ResponseWriter.write(response, out)) {
            return true;
        }
        server.close(slot);
        return false;
    }
}
