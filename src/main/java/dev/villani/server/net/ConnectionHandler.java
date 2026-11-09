package dev.villani.server.net;

/**
 * Protocol hook called by the transport ({@link NioServer} or the io_uring server) on the event-loop thread.
 * The handler reads from {@code server.readRing(slot)}, consumes every complete request it finds
 * (pipelined requests may be queued), writes the response into {@code server.writeBuffer(slot)}
 * (in write mode) and calls {@code server.flush(slot)}. It must not keep references to per-connection
 * state between calls other than through the slot index.
 */
public interface ConnectionHandler {

    /** New bytes are available in the slot's read ring. The ring may also be empty (spurious wake-up). */
    void onReadable(ConnectionIo server, int slot);

    /** Called when a connection slot is closed, before it is released to the pool. */
    default void onClosed(int slot) {
    }

    /** Called once per event-loop iteration, after I/O was handled (timers, deferred work). */
    default void onTick(ConnectionIo server) {
    }

    /** Upper bound in milliseconds the loop may block waiting for I/O (0 = poll). */
    default long maxWaitMs() {
        return Long.MAX_VALUE;
    }
}
