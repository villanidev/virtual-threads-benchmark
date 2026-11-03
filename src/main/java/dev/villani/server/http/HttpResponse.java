package dev.villani.server.http;

import dev.villani.server.memory.Regions;

import java.nio.ByteBuffer;

/**
 * Reusable response holder: status, keep-alive and a pre-allocated off-heap body buffer.
 * Serialising it to the wire is the job of the response writer (step 8).
 */
public final class HttpResponse {

    /**
     * Guarda status, keep-alive e um buffer direct de body pré-alocado.
     */

    private final ByteBuffer body;
    private int status = 200;
    private boolean keepAlive = true;
    private int deferMs;

    public HttpResponse(int bodyCapacity) {
        this.body = Regions.allocateBuffer(bodyCapacity);
    }

    public void reset() {
        status = 200;
        keepAlive = true;
        deferMs = 0;
        body.clear();
    }

    public HttpResponse status(int status) {
        this.status = status;
        return this;
    }

    public int status() {
        return status;
    }

    public HttpResponse keepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
        return this;
    }

    public boolean keepAlive() {
        return keepAlive;
    }

    /** Body buffer in write mode; handlers append to it. */
    public ByteBuffer body() {
        return body;
    }

    /** Asks the server to hold the response for {@code ms} and call {@link AsyncHandler#complete} afterwards. */
    public HttpResponse deferMs(int ms) {
        this.deferMs = ms;
        return this;
    }

    public int deferMs() {
        return deferMs;
    }
}
