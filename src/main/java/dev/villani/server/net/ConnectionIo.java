package dev.villani.server.net;

import dev.villani.server.memory.ByteRing;

import java.nio.ByteBuffer;

/**
 * What a protocol handler may do with a connection slot, independent of the transport underneath
 * ({@link NioServer} with a Selector, or the io_uring server).
 */
public interface ConnectionIo {

    /** Unparsed incoming bytes of the slot. */
    ByteRing readRing(int slot);

    /** Response buffer of the slot, always in write mode (position = bytes already staged). */
    ByteBuffer writeBuffer(int slot);

    /** True while staged bytes are still being sent; the handler must not be called for the slot until it drains. */
    boolean isWritePending(int slot);

    boolean isOpen(int slot);

    /** Sends the staged response bytes; if the socket cannot take them all, reading pauses until it drains. */
    void flush(int slot);

    /** Sends the staged response bytes and closes the connection once they have all been written. */
    void flushAndClose(int slot);

    void close(int slot);
}
