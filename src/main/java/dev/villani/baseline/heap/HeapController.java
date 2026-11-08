package dev.villani.baseline.heap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.villani.application.agendamento.AgendamentoRules;
import dev.villani.application.agendamento.AgendamentoServer;
import dev.villani.server.http.DelayPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * The appointment API written the usual way: Jackson binding, records, Strings, a blocking
 * {@link Thread#sleep} for the simulated external call. Same contract as the flat server.
 */
public final class HeapController implements HttpHandler {

    private static final String PATH = "/api/agendamentos";
    private static final int MAX_ITEM_BYTES = 160;

    record CriarRequest(String nome, Integer servicoId, Long dataHora) {
    }

    record Criado(int id, String status) {
    }

    record Item(int id, int servicoId, long dataHora, String status, String nome) {
    }

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

    private final HeapStore store;
    private final DelayPolicy delay;

    public HeapController(HeapStore store, DelayPolicy delay) {
        this.store = store;
        this.delay = delay;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            final String path = exchange.getRequestURI().getPath();
            final String method = exchange.getRequestMethod();
            if (path.equals(PATH)) {
                switch (method) {
                    case "POST" -> create(exchange);
                    case "GET" -> list(exchange);
                    default -> send(exchange, 405, null);
                }
            } else if (path.startsWith(PATH + "/") && path.endsWith("/cancelar")) {
                if (method.equals("PUT")) {
                    cancel(exchange, path);
                } else {
                    send(exchange, 405, null);
                }
            } else {
                send(exchange, 404, null);
            }
        }
    }

    private void create(HttpExchange exchange) throws IOException {
        final CriarRequest req;
        try {
            req = mapper.readValue(exchange.getRequestBody().readAllBytes(), CriarRequest.class);
        } catch (IOException e) {
            send(exchange, 400, null);
            return;
        }
        if (req == null || req.nome() == null || req.servicoId() == null || req.dataHora() == null
                || !AgendamentoRules.isValid(req.nome().getBytes(StandardCharsets.UTF_8).length,
                        req.servicoId(), req.dataHora())) {
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
        final int id = store.create(req.servicoId(), req.dataHora(), req.nome());
        if (id == HeapStore.CONFLICT) {
            send(exchange, 409, null);
        } else if (id < 0) {
            send(exchange, 503, null);
        } else {
            send(exchange, 201, mapper.writeValueAsBytes(new Criado(id, "CONFIRMADO")));
        }
    }

    private void list(HttpExchange exchange) throws IOException {
        final String data = queryParam(exchange.getRequestURI().getRawQuery(), "data");
        final long day;
        try {
            if (data == null || data.length() != 10) {
                send(exchange, 400, null);
                return;
            }
            day = LocalDate.parse(data).atStartOfDay(ZoneOffset.UTC).toEpochSecond() / 86_400L;
        } catch (DateTimeParseException e) {
            send(exchange, 400, null);
            return;
        }
        // same truncation rule as the flat server: stop when the next item might not fit the body budget
        final ByteArrayOutputStream out = new ByteArrayOutputStream(AgendamentoServer.RESPONSE_BODY_BYTES);
        out.write('[');
        boolean first = true;
        for (HeapStore.Agendamento a : store.ofDay(day)) {
            if (AgendamentoServer.RESPONSE_BODY_BYTES - out.size() < MAX_ITEM_BYTES) {
                break;
            }
            if (!first) {
                out.write(',');
            }
            first = false;
            out.write(mapper.writeValueAsBytes(new Item(a.id, a.servicoId, a.dataHora, a.status(), a.nome)));
        }
        out.write(']');
        send(exchange, 200, out.toByteArray());
    }

    private void cancel(HttpExchange exchange, String path) throws IOException {
        final String idText = path.substring(PATH.length() + 1, path.length() - "/cancelar".length());
        final int id;
        try {
            id = Integer.parseInt(idText);
        } catch (NumberFormatException e) {
            send(exchange, 404, null);
            return;
        }
        final int result = store.cancel(id);
        send(exchange, result == HeapStore.OK ? 204 : result == HeapStore.NOT_FOUND ? 404 : 409, null);
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
