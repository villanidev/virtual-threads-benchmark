package dev.villani.server.http;

import dev.villani.server.memory.ByteRing;

/**
 * Allocation-free router. Routes are registered once at startup (patterns like
 * {@code /api/items/{id}/cancel}); matching walks the path bytes in the ring and
 * compares them against flat literal tables. A single {@code {id}} placeholder
 * (decimal digits) is extracted as an int into {@link #pathParam()}.
 * Single-threaded: the param is valid until the next {@link #match} call.
 */
public final class Router {

    /**
     * Registro: as rotas são registradas na inicialização, com padrões como /api/agendamentos/{id}/cancelar. Depois disso a tabela é plana (int[] e byte[]).
     * Matching: compara os bytes do path direto no ring. O {id} (até 9 dígitos) sai como int em pathParam(), sem criar String.
     * Resultados: devolve o id do handler, NOT_FOUND (404) ou METHOD_NOT_ALLOWED (405).
     */

    public static final int NOT_FOUND = -1;
    public static final int METHOD_NOT_ALLOWED = -2;

    private static final int PARAM = -1;
    private static final int MAX_DIGITS = 9;

    private final int maxRoutes;
    private final int maxSegments;
    private final int[] routeMethod;
    private final int[] routeHandler;
    private final int[] routeSegCount;
    // segment k of route r: segStart[r*maxSegments+k], segLen[...] (PARAM when placeholder) into literals
    private final int[] segStart;
    private final int[] segLen;
    private final byte[] literals;
    private int routes;
    private int literalsUsed;
    private int param;

    public Router(int maxRoutes, int maxSegments, int literalBytes) {
        this.maxRoutes = maxRoutes;
        this.maxSegments = maxSegments;
        routeMethod = new int[maxRoutes];
        routeHandler = new int[maxRoutes];
        routeSegCount = new int[maxRoutes];
        segStart = new int[maxRoutes * maxSegments];
        segLen = new int[maxRoutes * maxSegments];
        literals = new byte[literalBytes];
    }

    /** Startup only: may allocate (String parsing). */
    public Router route(int method, String pattern, int handlerId) {
        if (routes == maxRoutes) {
            throw new IllegalStateException("too many routes");
        }
        if (handlerId < 0 || pattern.isEmpty() || pattern.charAt(0) != '/') {
            throw new IllegalArgumentException("bad route " + pattern);
        }
        final int r = routes;
        int count = 0;
        boolean hasParam = false;
        int i = 1;
        while (i <= pattern.length() && !(i == pattern.length() && pattern.length() == 1)) {
            int end = pattern.indexOf('/', i);
            if (end < 0) {
                end = pattern.length();
            }
            if (count == maxSegments) {
                throw new IllegalArgumentException("too many segments " + pattern);
            }
            final String seg = pattern.substring(i, end);
            if (seg.isEmpty()) {
                throw new IllegalArgumentException("empty segment " + pattern);
            }
            final int idx = r * maxSegments + count;
            if (seg.equals("{id}")) {
                if (hasParam) {
                    throw new IllegalArgumentException("only one {id} allowed " + pattern);
                }
                hasParam = true;
                segLen[idx] = PARAM;
            } else {
                if (literalsUsed + seg.length() > literals.length) {
                    throw new IllegalStateException("literal table full");
                }
                segStart[idx] = literalsUsed;
                segLen[idx] = seg.length();
                for (int k = 0; k < seg.length(); k++) {
                    literals[literalsUsed++] = (byte) seg.charAt(k);
                }
            }
            count++;
            i = end + 1;
        }
        routeMethod[r] = method;
        routeHandler[r] = handlerId;
        routeSegCount[r] = count;
        routes++;
        return this;
    }

    /** Returns the handler id, {@link #NOT_FOUND} or {@link #METHOD_NOT_ALLOWED}. */
    public int match(int method, ByteRing ring, int pathStart, int pathLength) {
        boolean pathMatched = false;
        int found = NOT_FOUND;
        for (int r = 0; r < routes; r++) {
            if (!pathMatches(r, ring, pathStart, pathLength)) {
                continue;
            }
            if (routeMethod[r] == method) {
                return routeHandler[r];
            }
            pathMatched = true;
            found = METHOD_NOT_ALLOWED;
        }
        return pathMatched ? found : NOT_FOUND;
    }

    public int pathParam() {
        return param;
    }

    private boolean pathMatches(int r, ByteRing ring, int start, int length) {
        final int segs = routeSegCount[r];
        final int end = start + length;
        int pos = start;
        int parsedParam = -1;
        for (int s = 0; s < segs; s++) {
            if (pos >= end || ring.peek(pos) != '/') {
                return false;
            }
            pos++;
            int segEnd = pos;
            while (segEnd < end && ring.peek(segEnd) != '/') {
                segEnd++;
            }
            final int len = segEnd - pos;
            final int idx = r * maxSegments + s;
            if (segLen[idx] == PARAM) {
                if (len == 0 || len > MAX_DIGITS) {
                    return false;
                }
                int v = 0;
                for (int k = pos; k < segEnd; k++) {
                    final int d = ring.peek(k) - '0';
                    if (d < 0 || d > 9) {
                        return false;
                    }
                    v = v * 10 + d;
                }
                parsedParam = v;
            } else {
                if (len != segLen[idx]) {
                    return false;
                }
                final int lit = segStart[idx];
                for (int k = 0; k < len; k++) {
                    if (ring.peek(pos + k) != literals[lit + k]) {
                        return false;
                    }
                }
            }
            pos = segEnd;
        }
        if (pos != end) {
            return false;
        }
        if (parsedParam >= 0) {
            param = parsedParam;
        }
        return true;
    }
}
