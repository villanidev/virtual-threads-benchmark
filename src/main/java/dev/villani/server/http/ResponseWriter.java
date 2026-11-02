package dev.villani.server.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Serialises an {@link HttpResponse} into a connection write buffer using pre-encoded constant
 * byte arrays and a digit loop for Content-Length: no String, no formatting, no allocation.
 */
public final class ResponseWriter {

    /**
     * Serialização: escreve a resposta no ByteBuffer de escrita da conexão. Status lines e cabeçalhos 
     * são byte[] pré-codificados, e o Content-Length sai de um loop de dígitos. Não usa String nem formatação.
     * Falta de espaço: retorna false e não escreve nada. O NioServer pode então fazer flush e tentar de novo.
     * Reuso: o body da HttpResponse fica intacto depois da escrita.
     */

    private static final int[] CODES = {200, 201, 204, 400, 404, 405, 409, 413, 431, 500, 501, 503};
    private static final String[] REASONS = {
            "OK", "Created", "No Content", "Bad Request", "Not Found", "Method Not Allowed",
            "Conflict", "Payload Too Large", "Request Header Fields Too Large",
            "Internal Server Error", "Not Implemented", "Service Unavailable"};
    private static final int FALLBACK = 9; // 500

    private static final byte[][] STATUS_LINES = new byte[CODES.length][];
    private static final byte[] CONTENT_TYPE = ascii("Content-Type: application/json\r\n");
    private static final byte[] CONTENT_LENGTH = ascii("Content-Length: ");
    private static final byte[] CONN_KEEP = ascii("\r\nConnection: keep-alive\r\n\r\n");
    private static final byte[] CONN_CLOSE = ascii("\r\nConnection: close\r\n\r\n");

    static {
        for (int i = 0; i < CODES.length; i++) {
            STATUS_LINES[i] = ascii("HTTP/1.1 " + CODES[i] + " " + REASONS[i] + "\r\n");
        }
    }

    private ResponseWriter() {
    }

    /**
     * Appends the full response to {@code dst}. Returns false, leaving {@code dst} untouched,
     * when it does not fit.
     */
    public static boolean write(HttpResponse response, ByteBuffer dst) {
        final ByteBuffer body = response.body();
        final int bodyLen = body.position();
        final int bodyBytes = response.status() == 204 ? 0 : bodyLen;
        final byte[] statusLine = STATUS_LINES[indexOf(response.status())];
        final byte[] connection = response.keepAlive() ? CONN_KEEP : CONN_CLOSE;
        final int digits = digits(bodyBytes);

        final int total = statusLine.length
                + (bodyBytes > 0 ? CONTENT_TYPE.length : 0)
                + CONTENT_LENGTH.length + digits
                + connection.length
                + bodyBytes;
        if (dst.remaining() < total) {
            return false;
        }

        dst.put(statusLine);
        if (bodyBytes > 0) {
            dst.put(CONTENT_TYPE);
        }
        dst.put(CONTENT_LENGTH);
        putDigits(dst, bodyBytes, digits);
        dst.put(connection);
        if (bodyBytes > 0) {
            body.flip();
            dst.put(body);
            body.limit(body.capacity());
            body.position(bodyLen);
        }
        return true;
    }

    private static int indexOf(int status) {
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i] == status) {
                return i;
            }
        }
        return FALLBACK;
    }

    private static int digits(int n) {
        int d = 1;
        while (n >= 10) {
            n /= 10;
            d++;
        }
        return d;
    }

    private static void putDigits(ByteBuffer dst, int n, int digits) {
        final int base = dst.position();
        dst.position(base + digits);
        for (int i = digits - 1; i >= 0; i--) {
            dst.put(base + i, (byte) ('0' + n % 10));
            n /= 10;
        }
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
