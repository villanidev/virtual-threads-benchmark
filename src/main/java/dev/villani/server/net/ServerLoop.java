package dev.villani.server.net;

import java.io.IOException;

/** The event loop of a transport ({@link NioServer} or the io_uring server), as seen by the application. */
public interface ServerLoop extends AutoCloseable {

    int localPort() throws IOException;

    int activeConnections();

    /** One loop iteration, waiting up to {@code timeoutMs} for I/O. */
    void runOnce(long timeoutMs) throws IOException;

    /** Runs until {@link #stop()}. */
    void run() throws IOException;

    void stop();

    @Override
    void close() throws IOException;
}
