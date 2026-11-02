package dev.villani.server.http;

import dev.villani.server.memory.ByteRing;

/**
 * Flyweight view over a parsed request: it owns no bytes, only a reference to the ring and the
 * offsets held by the parser. One instance is reused for every request (single-threaded loop),
 * so a view is only valid while the request is being handled.
 */
public final class HttpRequest {

    /**
     * flyweight sem bytes próprios, só uma view sobre o ring e os offsets do parser. Expõe method(), 
     * pathParam(), bodyLength(), bodyByte(i) e findQueryParam(nome) com queryValueLength(). 
     * Uma única instância é reutilizada.
     */

    private HttpRequestParser parser;
    private ByteRing ring;
    private int slot;
    private int pathParam;
    private int queryValueLength;

    HttpRequest bind(HttpRequestParser parser, ByteRing ring, int slot, int pathParam) {
        this.parser = parser;
        this.ring = ring;
        this.slot = slot;
        this.pathParam = pathParam;
        return this;
    }

    public int method() {
        return parser.method(slot);
    }

    /** Value of the {@code {id}} placeholder of the matched route. */
    public int pathParam() {
        return pathParam;
    }

    public boolean keepAlive() {
        return parser.keepAlive(slot);
    }

    public int bodyLength() {
        return parser.contentLength(slot);
    }

    public byte bodyByte(int index) {
        if (index < 0 || index >= parser.contentLength(slot)) {
            throw new IndexOutOfBoundsException(index);
        }
        return ring.peek(parser.bodyStart(slot) + index);
    }

    /** Finds {@code name=value} in the query string. Returns the value offset (see {@link #queryByte}) or -1. */
    public int findQueryParam(byte[] name) {
        final int qStart = parser.queryStart(slot);
        if (qStart < 0) {
            return -1;
        }
        final int qEnd = qStart + parser.queryLength(slot);
        int i = qStart;
        while (i < qEnd) {
            int k = 0;
            while (k < name.length && i + k < qEnd && ring.peek(i + k) == name[k]) {
                k++;
            }
            final int after = i + k;
            if (k == name.length && after < qEnd && ring.peek(after) == '=') {
                int v = after + 1;
                int e = v;
                while (e < qEnd && ring.peek(e) != '&') {
                    e++;
                }
                queryValueLength = e - v;
                return v;
            }
            while (i < qEnd && ring.peek(i) != '&') {
                i++;
            }
            i++;
        }
        return -1;
    }

    /** Length of the value found by the last successful {@link #findQueryParam}. */
    public int queryValueLength() {
        return queryValueLength;
    }

    /** Absolute ring offset, as returned by {@link #findQueryParam}. */
    public byte queryByte(int offset) {
        return ring.peek(offset);
    }
}
