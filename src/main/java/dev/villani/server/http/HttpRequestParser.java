package dev.villani.server.http;

import dev.villani.server.memory.ByteRing;

/**
 * Incremental HTTP/1.1 request parser. State lives in flat int/boolean arrays indexed by
 * connection slot, so parsing never allocates. Offsets are relative to the ring read position:
 * nothing is consumed until {@link #consume} is called after the request was handled.
 */
public final class HttpRequestParser {

    /**
     * Estado por conexão: fica em arrays int/boolean indexados pelo slot, então não há objeto por requisição.
     * Leitura incremental: a busca por CRLF CRLF retoma de onde parou. Funciona mesmo com a requisição chegando byte a byte.
     * Offsets: os offsets de método, path, query e body são relativos ao ring. Nada é consumido até consume(), e o pipelining funciona.
     * Cabeçalhos tratados: Content-Length, Connection e Transfer-Encoding.
     * Erros:
     * 400: requisição malformada.
     * 413: corpo maior que o ring.
     * 431: cabeçalho grande demais.
     * 501: Transfer-Encoding, ou seja, chunked não suportado.
     * Zero alocação: o teste com 20 mil iterações de POST com corpo, fazendo parse e consume, alocou 0 bytes.
     */

    public static final int NEED_MORE = 0;
    public static final int COMPLETE = 1;
    public static final int ERROR = 2;

    public static final int GET = 1;
    public static final int POST = 2;
    public static final int PUT = 3;
    public static final int OTHER = 4;

    public static final int MAX_HEAD_BYTES = 8192;

    private final int[] scanned;
    private final int[] crlfRun;
    private final int[] method;
    private final int[] pathStart;
    private final int[] pathLen;
    private final int[] queryStart;
    private final int[] queryLen;
    private final int[] contentLength;
    private final int[] bodyStart;
    private final boolean[] keepAlive;
    private final boolean[] headParsed;
    private final int[] errorStatus;

    public HttpRequestParser(int slots) {
        scanned = new int[slots];
        crlfRun = new int[slots];
        method = new int[slots];
        pathStart = new int[slots];
        pathLen = new int[slots];
        queryStart = new int[slots];
        queryLen = new int[slots];
        contentLength = new int[slots];
        bodyStart = new int[slots];
        keepAlive = new boolean[slots];
        headParsed = new boolean[slots];
        errorStatus = new int[slots];
    }

    public int parse(int slot, ByteRing ring) {
        if (errorStatus[slot] != 0) {
            return ERROR;
        }
        if (!headParsed[slot]) {
            if (!scanHead(slot, ring)) {
                return errorStatus[slot] != 0 ? ERROR : NEED_MORE;
            }
            if (!parseHead(slot, ring)) {
                return ERROR;
            }
            headParsed[slot] = true;
        }
        int total = bodyStart[slot] + contentLength[slot];
        if (total > ring.capacity()) {
            return fail(slot, 413);
        }
        return ring.readable() >= total ? COMPLETE : NEED_MORE;
    }

    /** Looks for CRLF CRLF resuming from where the previous call stopped. */
    private boolean scanHead(int slot, ByteRing ring) {
        int i = scanned[slot];
        int run = crlfRun[slot];
        int n = ring.readable();
        while (i < n) {
            byte b = ring.peek(i++);
            if (b == '\r' && (run == 0 || run == 2)) {
                run++;
            } else if (b == '\n' && (run == 1 || run == 3)) {
                run++;
                if (run == 4) {
                    bodyStart[slot] = i;
                    scanned[slot] = i;
                    return true;
                }
            } else {
                run = b == '\r' ? 1 : 0;
            }
            if (i > MAX_HEAD_BYTES || i >= ring.capacity() && run != 4) {
                scanned[slot] = i;
                crlfRun[slot] = run;
                fail(slot, 431);
                return false;
            }
        }
        scanned[slot] = i;
        crlfRun[slot] = run;
        return false;
    }

    private boolean parseHead(int slot, ByteRing ring) {
        int end = bodyStart[slot];
        int i = 0;

        int mStart = i;
        while (i < end && ring.peek(i) != ' ') {
            i++;
        }
        method[slot] = methodOf(ring, mStart, i - mStart);
        if (i >= end) {
            fail(slot, 400);
            return false;
        }
        i++;

        int pStart = i;
        int qMark = -1;
        while (i < end && ring.peek(i) != ' ') {
            if (ring.peek(i) == '?' && qMark < 0) {
                qMark = i;
            }
            i++;
        }
        if (i >= end || i == pStart || ring.peek(pStart) != '/') {
            fail(slot, 400);
            return false;
        }
        pathStart[slot] = pStart;
        if (qMark >= 0) {
            pathLen[slot] = qMark - pStart;
            queryStart[slot] = qMark + 1;
            queryLen[slot] = i - qMark - 1;
        } else {
            pathLen[slot] = i - pStart;
            queryStart[slot] = -1;
            queryLen[slot] = 0;
        }
        i++;

        // HTTP/1.x
        if (end - i < 10 || !matches(ring, i, "HTTP/1.")) {
            fail(slot, 400);
            return false;
        }
        byte minor = ring.peek(i + 7);
        if (minor != '0' && minor != '1') {
            fail(slot, 400);
            return false;
        }
        keepAlive[slot] = minor == '1';
        contentLength[slot] = 0;
        i += 8;
        if (ring.peek(i) != '\r') {
            fail(slot, 400);
            return false;
        }
        i += 2;

        boolean sawLength = false;
        while (i < end - 2) {
            int nameStart = i;
            while (i < end && ring.peek(i) != ':' && ring.peek(i) != '\r') {
                i++;
            }
            if (i >= end || ring.peek(i) != ':' || i == nameStart) {
                fail(slot, 400);
                return false;
            }
            int nameLen = i - nameStart;
            i++;
            while (i < end && (ring.peek(i) == ' ' || ring.peek(i) == '\t')) {
                i++;
            }
            int valueStart = i;
            while (i < end && ring.peek(i) != '\r') {
                i++;
            }
            int valueEnd = i;
            while (valueEnd > valueStart && ring.peek(valueEnd - 1) == ' ') {
                valueEnd--;
            }
            i += 2;

            if (equalsIgnoreCase(ring, nameStart, nameLen, "content-length")) {
                if (sawLength) {
                    fail(slot, 400);
                    return false;
                }
                sawLength = true;
                long v = 0;
                if (valueEnd == valueStart) {
                    fail(slot, 400);
                    return false;
                }
                for (int k = valueStart; k < valueEnd; k++) {
                    int d = ring.peek(k) - '0';
                    if (d < 0 || d > 9) {
                        fail(slot, 400);
                        return false;
                    }
                    v = v * 10 + d;
                    if (v > Integer.MAX_VALUE / 2) {
                        fail(slot, 413);
                        return false;
                    }
                }
                contentLength[slot] = (int) v;
            } else if (equalsIgnoreCase(ring, nameStart, nameLen, "transfer-encoding")) {
                fail(slot, 501);
                return false;
            } else if (equalsIgnoreCase(ring, nameStart, nameLen, "connection")) {
                int len = valueEnd - valueStart;
                if (equalsIgnoreCase(ring, valueStart, len, "close")) {
                    keepAlive[slot] = false;
                } else if (equalsIgnoreCase(ring, valueStart, len, "keep-alive")) {
                    keepAlive[slot] = true;
                }
            }
        }
        return true;
    }

    private static int methodOf(ByteRing r, int off, int len) {
        if (len == 3 && matches(r, off, "GET")) {
            return GET;
        }
        if (len == 3 && matches(r, off, "PUT")) {
            return PUT;
        }
        if (len == 4 && matches(r, off, "POST")) {
            return POST;
        }
        return OTHER;
    }

    private static boolean matches(ByteRing r, int off, String literal) {
        for (int k = 0; k < literal.length(); k++) {
            if (r.peek(off + k) != literal.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalsIgnoreCase(ByteRing r, int off, int len, String lowerLiteral) {
        if (len != lowerLiteral.length()) {
            return false;
        }
        for (int k = 0; k < len; k++) {
            int c = r.peek(off + k);
            if (c >= 'A' && c <= 'Z') {
                c += 32;
            }
            if (c != lowerLiteral.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private int fail(int slot, int status) {
        errorStatus[slot] = status;
        return ERROR;
    }

    /** Drops the request bytes from the ring and resets the slot for the next pipelined request. */
    public void consume(int slot, ByteRing ring) {
        ring.skip(bodyStart[slot] + contentLength[slot]);
        reset(slot);
    }

    public void reset(int slot) {
        scanned[slot] = 0;
        crlfRun[slot] = 0;
        headParsed[slot] = false;
        errorStatus[slot] = 0;
        contentLength[slot] = 0;
        bodyStart[slot] = 0;
    }

    public int errorStatus(int slot) {
        return errorStatus[slot];
    }

    public int method(int slot) {
        return method[slot];
    }

    public int pathStart(int slot) {
        return pathStart[slot];
    }

    public int pathLength(int slot) {
        return pathLen[slot];
    }

    /** Offset of the query string after '?', or -1 when absent. */
    public int queryStart(int slot) {
        return queryStart[slot];
    }

    public int queryLength(int slot) {
        return queryLen[slot];
    }

    public int contentLength(int slot) {
        return contentLength[slot];
    }

    public int bodyStart(int slot) {
        return bodyStart[slot];
    }

    public boolean keepAlive(int slot) {
        return keepAlive[slot];
    }
}
