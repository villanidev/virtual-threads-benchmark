package dev.villani.baseline;

import dev.villani.server.http.DelayPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Same scenarios against every implementation: the comparison is only meaningful while all of them
 * answer the same bytes. Add a constant to {@link Impl} to include a new variant.
 */
@DisplayName("API contract parity across implementations")
class AgendamentoContractTest {

    private static final long JAN_2 = LocalDate.of(2026, 1, 2).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

    enum Impl {
        VIRTUAL_HEAP,
        FIXED_HEAP,
        CACHED_HEAP,
        VIRTUAL_OFF,
        FIXED_OFF,
        CACHED_OFF
    }

    private interface Running extends AutoCloseable {
        int port() throws IOException;

        @Override
        void close() throws Exception;
    }

    private static Running start(Impl impl) throws IOException {
        final ConcurrencyModel model = ConcurrencyModel.valueOf(impl.name().substring(0, impl.name().indexOf('_')));
        final BaselineServer app = new BaselineServer("127.0.0.1", 0, model, impl.name().endsWith("_OFF") ? BaselineServer.Memory.OFFHEAP : BaselineServer.Memory.HEAP, 16,
                DelayPolicy.fixed(1));
        app.start();
        return new Running() {
            public int port() {
                return app.port();
            }

            public void close() {
                app.close();
            }
        };
    }

    private record Reply(int status, String body) {
    }

    private static Reply call(HttpClient client, int port, String method, String path, String body) throws Exception {
        final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(5));
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        final HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body());
    }

    private static String create(String nome, int servico, long dataHora) {
        return "{\"nome\":\"" + nome + "\",\"servicoId\":" + servico + ",\"dataHora\":" + dataHora + "}";
    }

    @ParameterizedTest
    @EnumSource(Impl.class)
    @DisplayName("create, conflict, validation, list order, cancel and re-create behave identically")
    void contract(Impl impl) throws Exception {
        final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        try (Running app = start(impl)) {
            final int port = app.port();
            final String api = "/api/agendamentos";

            // given a fresh server, when creating, then ids are sequential and the body is exact
            assertEquals(new Reply(201, "{\"id\":1,\"status\":\"CONFIRMADO\"}"),
                    call(client, port, "POST", api, create("Ana", 7, JAN_2 + 9 * 3600)));
            assertEquals(new Reply(201, "{\"id\":2,\"status\":\"CONFIRMADO\"}"),
                    call(client, port, "POST", api, create("Bob", 8, JAN_2 + 10 * 3600)));

            // when the same service and time are booked again, then it conflicts
            assertEquals(409, call(client, port, "POST", api, create("Cy", 7, JAN_2 + 9 * 3600)).status());

            // when the payload is invalid, then 400
            assertEquals(400, call(client, port, "POST", api, "{\"servicoId\":1,\"dataHora\":" + JAN_2 + "}").status());
            assertEquals(400, call(client, port, "POST", api, create("X", 0, JAN_2)).status());
            assertEquals(400, call(client, port, "POST", api, create("X", 1, 5)).status());
            assertEquals(400, call(client, port, "POST", api, "{not json").status());
            assertEquals(400, call(client, port, "POST", api, create("", 1, JAN_2)).status());

            // when listing the day, then newest first with the exact JSON layout
            assertEquals(new Reply(200,
                    "[{\"id\":2,\"servicoId\":8,\"dataHora\":" + (JAN_2 + 10 * 3600)
                            + ",\"status\":\"CONFIRMADO\",\"nome\":\"Bob\"},"
                            + "{\"id\":1,\"servicoId\":7,\"dataHora\":" + (JAN_2 + 9 * 3600)
                            + ",\"status\":\"CONFIRMADO\",\"nome\":\"Ana\"}]"),
                    call(client, port, "GET", api + "?data=2026-01-02", null));
            assertEquals(new Reply(200, "[]"), call(client, port, "GET", api + "?data=2026-01-03", null));
            assertEquals(400, call(client, port, "GET", api + "?data=2026-13-40", null).status());
            assertEquals(400, call(client, port, "GET", api, null).status());

            // when cancelling, then 204, then 409, unknown id is 404
            assertEquals(204, call(client, port, "PUT", api + "/1/cancelar", null).status());
            assertEquals(409, call(client, port, "PUT", api + "/1/cancelar", null).status());
            assertEquals(404, call(client, port, "PUT", api + "/99/cancelar", null).status());

            // then the cancelled slot can be booked again and the listing shows the status
            assertEquals(new Reply(201, "{\"id\":3,\"status\":\"CONFIRMADO\"}"),
                    call(client, port, "POST", api, create("Cy", 7, JAN_2 + 9 * 3600)));
            final String listed = call(client, port, "GET", api + "?data=2026-01-02", null).body();
            assertEquals(true, listed.contains("\"id\":1,\"servicoId\":7,\"dataHora\":" + (JAN_2 + 9 * 3600)
                    + ",\"status\":\"CANCELADO\",\"nome\":\"Ana\""));

            // routing
            assertEquals(404, call(client, port, "GET", "/nope", null).status());
            assertEquals(405, call(client, port, "DELETE", api, null).status());
        }
    }

    @ParameterizedTest
    @EnumSource(Impl.class)
    @DisplayName("concurrent creates get unique sequential ids and none is lost")
    void concurrentCreates(Impl impl) throws Exception {
        final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        final int threads = 8;
        final int each = 50;
        try (Running app = start(impl)) {
            final int port = app.port();
            final java.util.Set<String> bodies = java.util.concurrent.ConcurrentHashMap.newKeySet();
            final java.util.List<Thread> workers = new java.util.ArrayList<>();
            final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
            for (int t = 0; t < threads; t++) {
                final int base = t * each;
                final Thread w = Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < each; i++) {
                        try {
                            final Reply r = call(client, port, "POST", "/api/agendamentos",
                                    create("N" + i, 1 + (base + i) % 1000, JAN_2 + 9 * 3600 + (base + i) / 1000 * 86_400L));
                            if (r.status() != 201 || !bodies.add(r.body())) {
                                failures.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    }
                });
                workers.add(w);
            }
            for (Thread w : workers) {
                w.join();
            }
            // then every create succeeded with a distinct id (so ids are exactly 1..N)
            assertEquals(0, failures.get());
            assertEquals(threads * each, bodies.size());
            assertEquals(true, bodies.contains("{\"id\":" + threads * each + ",\"status\":\"CONFIRMADO\"}"));
        }
    }
}
