package dev.villani.server.net;

/**
 * Protocol hook called by {@link NioServer} on the event-loop thread.
 * The handler reads from {@code server.readRing(slot)}, consumes every complete request it finds
 * (pipelined requests may be queued), writes the response into {@code server.writeBuffer(slot)}
 * (in write mode) and calls {@code server.flush(slot)}. It must not keep references to per-connection
 * state between calls other than through the slot index.
 */
public interface ConnectionHandler {

    /** New bytes are available in the slot's read ring. The ring may also be empty (spurious wake-up). */
    void onReadable(NioServer server, int slot);
}
