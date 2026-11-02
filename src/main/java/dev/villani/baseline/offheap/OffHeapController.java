package dev.villani.baseline.offheap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.villani.application.agendamento.AgendamentoDias;
import dev.villani.application.agendamento.AgendamentoRules;
import dev.villani.application.agendamento.AgendamentoTable;
import dev.villani.server.http.DelayPolicy;
import dev.villani.server.json.JsonBinder;
import dev.villani.server.json.JsonRecord;
import dev.villani.server.json.JsonSchema;
import dev.villani.server.json.JsonWriter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Same API as {@code HeapController} on the same JDK {@code HttpServer}, but request/response data lives in
 * pooled off-heap buffers, the JSON is bound/written without Strings, and the repository is the flat table.
 * What still allocates is the JDK transport itself (exchange, headers, streams).
 */
public final class OffHeapController implements HttpHandler {

    private static final String PATH = "/api/agendamentos";
    private static final String CANCELAR = "/cancelar";
    private static final String QUERY_KEY = "data=";
    private static final int MAX_ITEM_BYTES = 160;

    private static final byte[] ID_OPEN = "{\"id\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ID_CLOSE = ",\"status\":\"CONFIRMADO\"}".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SERVICO = ",\"servicoId\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DATA_HORA = ",\"dataHora\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS_CONFIRMADO = ",\"status\":\"CONFIRMADO\",\"nome\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS_CANCELADO = ",\"status\":\"CANCELADO\",\"nome\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END_ITEM = "\"}".getBytes(StandardCharsets.US_ASCII);

    private final SharedTable table;
    private final DelayPolicy delay;
    private final RequestContext.Pool contexts;
    private final int fNome;
    private final int fServico;
    private final int fDataHora;

    public OffHeapController(AgendamentoTable table, int contextPoolSize, DelayPolicy delay) {
        this.table = new SharedTable(table);
        this.delay = delay;
        final JsonSchema schema = new JsonSchema(3);
        fNome = schema.field("nome", JsonSchema.STRING, AgendamentoTable.MAX_NOME);
        fServico = schema.field("servicoId", JsonSchema.INT);
        fDataHora = schema.field("dataHora", JsonSchema.INT);
        this.contexts = new RequestContext.Pool(schema, contextPoolSize);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        final RequestContext ctx;
        try {
            ctx = contexts.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.close();
            return;
        }
        try (exchange) {
            final String path = exchange.getRequestURI().getRawPath();
            final String method = exchange.getRequestMethod();
            if (path.equals(PATH)) {
                if (method.equals("POST")) {
                    create(exchange, ctx);
                } else if (method.equals("GET")) {
                    list(exchange, ctx);
                } else {
                    send(exchange, 405, null);
                }
            } else if (path.length() > PATH.length() + 1 + CANCELAR.length() && path.startsWith(PATH)
                    && path.charAt(PATH.length()) == '/' && path.endsWith(CANCELAR)) {
                if (method.equals("PUT")) {
                    cancel(exchange, path);
                } else {
                    send(exchange, 405, null);
                }
            } else {
                send(exchange, 404, null);
            }
        } finally {
            contexts.release(ctx);
        }
    }

    private void create(HttpExchange exchange, RequestContext ctx) throws IOException {
        final int length = readBody(exchange.getRequestBody(), ctx);
        if (length > RequestContext.IN_BYTES) {
            send(exchange, 413, null);
            return;
        }
        final JsonRecord rec = ctx.record;
        final int code = ctx.binder.bind(ctx.ring, 0, length, rec);
        if (code != JsonBinder.OK || !rec.has(fNome) || !rec.has(fServico) || !rec.has(fDataHora)
                || !AgendamentoRules.isValid(rec.stringLength(fNome), rec.getInt(fServico), rec.getInt(fDataHora))) {
            send(exchange, 400, null);
            return;
        }
        final int delayMs = delay == null ? 0 : delay.nextDelayMs();
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(exchange, 503, null);
                return;
            }
        }
        rec.copyStringTo(fNome, ctx.nome);
        ctx.nome.flip();
        final int id = table.create((int) rec.getInt(fServico), rec.getInt(fDataHora), ctx.nome);
        if (id == AgendamentoTable.CONFLICT) {
            send(exchange, 409, null);
        } else if (id < 0) {
            send(exchange, 503, null);
        } else {
            JsonWriter.put(ctx.body, ID_OPEN);
            JsonWriter.putLong(ctx.body, id);
            JsonWriter.put(ctx.body, ID_CLOSE);
            send(exchange, 201, ctx);
        }
    }

    private void list(HttpExchange exchange, RequestContext ctx) throws IOException {
        if (!copyDateParam(exchange.getRequestURI().getRawQuery(), ctx.date)) {
            send(exchange, 400, null);
            return;
        }
        final long day = AgendamentoDias.parseIsoDate(ctx.date, 10);
        if (day == Long.MIN_VALUE) {
            send(exchange, 400, null);
            return;
        }
        final ByteBuffer body = ctx.body;
        body.put((byte) '[');
        boolean first = true;
        final AgendamentoTable t = table.unsafe();
        final long stamp = table.readLock();
        try {
            for (int id = t.firstOfDay((int) day); id != 0 && body.remaining() >= MAX_ITEM_BYTES; id = t.nextOfDay(id)) {
                if (!first) {
                    body.put((byte) ',');
                }
                first = false;
                JsonWriter.put(body, ID_OPEN);
                JsonWriter.putLong(body, id);
                JsonWriter.put(body, SERVICO);
                JsonWriter.putLong(body, t.servico(id));
                JsonWriter.put(body, DATA_HORA);
                JsonWriter.putLong(body, t.dataHora(id));
                JsonWriter.put(body, t.status(id) == AgendamentoTable.CONFIRMADO ? STATUS_CONFIRMADO : STATUS_CANCELADO);
                t.copyNome(id, body);
                JsonWriter.put(body, END_ITEM);
            }
        } finally {
            table.unlockRead(stamp);
        }
        body.put((byte) ']');
        send(exchange, 200, ctx);
    }

    private void cancel(HttpExchange exchange, String path) throws IOException {
        int id = 0;
        final int end = path.length() - CANCELAR.length();
        for (int i = PATH.length() + 1; i < end; i++) {
            final int d = path.charAt(i) - '0';
            if (d < 0 || d > 9 || id > 100_000_000) {
                send(exchange, 404, null);
                return;
            }
            id = id * 10 + d;
        }
        final int result = table.cancel(id);
        send(exchange, result == AgendamentoTable.OK ? 204 : result == AgendamentoTable.NOT_FOUND ? 404 : 409, null);
    }

    /** Reads the request body into the context ring; returns its length (IN_BYTES + 1 when too large). */
    private static int readBody(InputStream in, RequestContext ctx) throws IOException {
        int n = 0;
        while (n <= RequestContext.IN_BYTES) {
            final int r = in.read(ctx.inArray, n, ctx.inArray.length - n);
            if (r < 0) {
                break;
            }
            n += r;
        }
        if (n <= RequestContext.IN_BYTES) {
            ctx.inStaging.clear().limit(n);
            ctx.ring.put(ctx.inStaging);
        }
        return n;
    }

    /** Copies the 10 chars after {@code data=} (at the start or after '&') without creating Strings. */
    private static boolean copyDateParam(String rawQuery, byte[] out) {
        if (rawQuery == null) {
            return false;
        }
        final int len = rawQuery.length();
        for (int i = 0; i + QUERY_KEY.length() <= len; i++) {
            if ((i == 0 || rawQuery.charAt(i - 1) == '&') && rawQuery.startsWith(QUERY_KEY, i)) {
                final int from = i + QUERY_KEY.length();
                int to = from;
                while (to < len && rawQuery.charAt(to) != '&') {
                    to++;
                }
                if (to - from != 10) {
                    return false;
                }
                for (int k = 0; k < 10; k++) {
                    out[k] = (byte) rawQuery.charAt(from + k);
                }
                return true;
            }
        }
        return false;
    }

    private static void send(HttpExchange exchange, int status, RequestContext ctx) throws IOException {
        if (ctx == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        ctx.body.flip();
        final int len = ctx.body.remaining();
        ctx.body.get(ctx.outArray, 0, len);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, len);
        exchange.getResponseBody().write(ctx.outArray, 0, len);
    }
}
