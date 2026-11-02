package dev.villani.application.agendamento;

import dev.villani.server.http.Dispatcher;
import dev.villani.server.http.HttpRequestParser;
import dev.villani.server.http.HttpResponse;
import dev.villani.server.http.ResponseWriter;
import dev.villani.server.memory.ByteRing;
import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Agendamento API (HTTP bytes in, HTTP bytes out)")
class AgendamentoApiTest {

    private static final long JAN_2 = LocalDate.of(2026, 1, 2).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

    private final ByteRing ring = new ByteRing(ByteBuffer.allocateDirect(4096));
    private final HttpResponse response = new HttpResponse(8192);
    private final ByteBuffer wire = ByteBuffer.allocateDirect(16384);
    private Dispatcher dispatcher;

    @BeforeEach
    void setUp() {
        final HttpRequestParser parser = new HttpRequestParser(1);
        dispatcher = new Dispatcher(parser, AgendamentoRoutes.newRouter(), AgendamentoRoutes.HANDLER_COUNT);
        AgendamentoRoutes.install(dispatcher, new AgendamentoTable(1000, 64), 4);
    }

    private String call(String raw) {
        ring.put(ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Dispatcher.RESPONSE_READY, dispatcher.process(0, ring, response));
        wire.clear();
        assertTrue(ResponseWriter.write(response, wire));
        final byte[] out = new byte[wire.position()];
        for (int i = 0; i < out.length; i++) {
            out[i] = wire.get(i);
        }
        return new String(out, StandardCharsets.US_ASCII);
    }

    private static String post(String json) {
        return "POST /api/agendamentos HTTP/1.1\r\nContent-Length: " + json.length() + "\r\n\r\n" + json;
    }

    private static String agendamento(String nome, int servico, long dataHora) {
        return "{\"nome\":\"" + nome + "\",\"servicoId\":" + servico + ",\"dataHora\":" + dataHora + "}";
    }

    @Nested
    @DisplayName("POST /api/agendamentos")
    class Create {

        @Test
        @DisplayName("given a valid body, when posting, then 201 with the new id")
        void givenValidBody_whenPosting_then201() {
            // given
            final String body = agendamento("Ana", 3, JAN_2 + 3600);

            // when
            final String res = call(post(body));

            // then
            assertTrue(res.startsWith("HTTP/1.1 201 Created\r\n"), res);
            assertTrue(res.endsWith("{\"id\":1,\"status\":\"CONFIRMADO\"}"), res);
        }

        @Test
        @DisplayName("given the same service and time twice, when posting, then the second is 409")
        void givenSameSlotTwice_whenPosting_then409() {
            // given
            final String body = agendamento("Ana", 3, JAN_2 + 3600);
            call(post(body));

            // when
            final String res = call(post(agendamento("Bia", 3, JAN_2 + 3600)));

            // then
            assertTrue(res.startsWith("HTTP/1.1 409 Conflict\r\n"), res);
        }

        @Test
        @DisplayName("given invalid bodies, when posting, then 400")
        void givenInvalidBodies_whenPosting_then400() {
            assertTrue(call(post("{}")).startsWith("HTTP/1.1 400"));
            assertTrue(call(post("{bad")).startsWith("HTTP/1.1 400"));
            assertTrue(call(post(agendamento("", 3, JAN_2))).startsWith("HTTP/1.1 400"));
            assertTrue(call(post(agendamento("A", 0, JAN_2))).startsWith("HTTP/1.1 400"));
            assertTrue(call(post(agendamento("A", 3, 5))).startsWith("HTTP/1.1 400"));
            assertTrue(call(post(agendamento("x".repeat(41), 3, JAN_2))).startsWith("HTTP/1.1 400"));
            assertTrue(call(post("{\"nome\":\"A\",\"servicoId\":\"3\",\"dataHora\":" + JAN_2 + "}")).startsWith("HTTP/1.1 400"));
        }
    }

    @Nested
    @DisplayName("GET /api/agendamentos?data=")
    class Listing {

        @Test
        @DisplayName("given appointments on a day, when listing it, then a JSON array of that day only is returned")
        void givenAppointments_whenListingDay_thenOnlyThatDay() {
            // given
            call(post(agendamento("Ana", 1, JAN_2 + 3600)));
            call(post(agendamento("Bia", 2, JAN_2 + 7200)));
            call(post(agendamento("Other", 1, JAN_2 + 86_400)));

            // when
            final String res = call("GET /api/agendamentos?data=2026-01-02 HTTP/1.1\r\n\r\n");

            // then
            assertTrue(res.startsWith("HTTP/1.1 200 OK\r\n"), res);
            assertTrue(res.endsWith("[{\"id\":2,\"servicoId\":2,\"dataHora\":" + (JAN_2 + 7200)
                    + ",\"status\":\"CONFIRMADO\",\"nome\":\"Bia\"},{\"id\":1,\"servicoId\":1,\"dataHora\":"
                    + (JAN_2 + 3600) + ",\"status\":\"CONFIRMADO\",\"nome\":\"Ana\"}]"), res);
        }

        @Test
        @DisplayName("given a day without appointments, when listing it, then an empty array")
        void givenEmptyDay_whenListing_thenEmptyArray() {
            // given / when
            final String res = call("GET /api/agendamentos?data=2026-02-01 HTTP/1.1\r\n\r\n");

            // then
            assertTrue(res.endsWith("\r\n\r\n[]"), res);
        }

        @Test
        @DisplayName("given a cancelled appointment, when listing, then its status is CANCELADO")
        void givenCancelled_whenListing_thenStatusShown() {
            // given
            call(post(agendamento("Ana", 1, JAN_2 + 3600)));
            call("PUT /api/agendamentos/1/cancelar HTTP/1.1\r\n\r\n");

            // when
            final String res = call("GET /api/agendamentos?data=2026-01-02 HTTP/1.1\r\n\r\n");

            // then
            assertTrue(res.contains("\"status\":\"CANCELADO\""), res);
        }

        @Test
        @DisplayName("given a missing or malformed date, when listing, then 400")
        void givenBadDate_whenListing_then400() {
            assertTrue(call("GET /api/agendamentos HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"));
            assertTrue(call("GET /api/agendamentos?data=2026-13-01 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"));
            assertTrue(call("GET /api/agendamentos?data=2026-02-30 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"));
            assertTrue(call("GET /api/agendamentos?data=abc HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"));
        }
    }

    @Nested
    @DisplayName("PUT /api/agendamentos/{id}/cancelar")
    class Cancel {

        @Test
        @DisplayName("given an appointment, when cancelling, then 204; again 409; unknown 404")
        void givenAppointment_whenCancelling_thenStatuses() {
            // given
            call(post(agendamento("Ana", 1, JAN_2 + 3600)));

            // when / then
            assertTrue(call("PUT /api/agendamentos/1/cancelar HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 204"));
            assertTrue(call("PUT /api/agendamentos/1/cancelar HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 409"));
            assertTrue(call("PUT /api/agendamentos/77/cancelar HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"));
        }

        @Test
        @DisplayName("given a cancelled slot, when booking the same time again, then it succeeds")
        void givenCancelledSlot_whenBookingAgain_thenCreated() {
            // given
            call(post(agendamento("Ana", 1, JAN_2 + 3600)));
            call("PUT /api/agendamentos/1/cancelar HTTP/1.1\r\n\r\n");

            // when
            final String res = call(post(agendamento("Bia", 1, JAN_2 + 3600)));

            // then
            assertTrue(res.startsWith("HTTP/1.1 201"), res);
        }
    }

    @Nested
    @DisplayName("routing")
    class Routing {

        @Test
        @DisplayName("given wrong method or path, when requesting, then 405 or 404")
        void givenWrongMethodOrPath_whenRequesting_then405Or404() {
            assertTrue(call("DELETE /api/agendamentos HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 405"));
            assertTrue(call("GET /api/outra HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"));
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given steady-state traffic, when creating, listing and cancelling, then zero bytes are allocated")
        void givenSteadyState_whenServing_thenZeroAlloc() {
            // given: a body whose last 6 digits of dataHora change on every iteration
            dispatcher = new Dispatcher(new HttpRequestParser(1), AgendamentoRoutes.newRouter(), AgendamentoRoutes.HANDLER_COUNT);
            AgendamentoRoutes.install(dispatcher, new AgendamentoTable(300_000, 64), 4);
            final byte[] json = agendamento("Ana", 1, 1_800_000_000L).getBytes(StandardCharsets.US_ASCII);
            final String head = "POST /api/agendamentos HTTP/1.1\r\nContent-Length: " + json.length + "\r\n\r\n";
            final byte[] headBytes = head.getBytes(StandardCharsets.US_ASCII);
            final byte[] create = new byte[headBytes.length + json.length];
            System.arraycopy(headBytes, 0, create, 0, headBytes.length);
            System.arraycopy(json, 0, create, headBytes.length, json.length);
            final int digitsEnd = create.length - 2; // before the closing "}" ... "dataHora":N}
            final ByteBuffer createBuf = ByteBuffer.wrap(create);
            final byte[] listRaw = "GET /api/agendamentos?data=2027-01-15 HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            final ByteBuffer listBuf = ByteBuffer.wrap(listRaw);
            final byte[] cancelRaw = "PUT /api/agendamentos/1/cancelar HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            final ByteBuffer cancelBuf = ByteBuffer.wrap(cancelRaw);
            final int[] counter = {0};

            final Runnable body = () -> {
                int c = counter[0]++ % 900_000;
                for (int i = 0; i < 6; i++) {
                    create[digitsEnd - i] = (byte) ('0' + c % 10);
                    c /= 10;
                }
                send(createBuf);
                wire.clear();
                ResponseWriter.write(response, wire);
                send(listBuf);
                wire.clear();
                ResponseWriter.write(response, wire);
                send(cancelBuf);
                wire.clear();
                ResponseWriter.write(response, wire);
            };

            // when / then
            AllocationProbe.assertZeroAlloc(body, 100_000, 100_000);
        }

        private void send(ByteBuffer raw) {
            raw.clear();
            ring.put(raw);
            if (dispatcher.process(0, ring, response) != Dispatcher.RESPONSE_READY) {
                throw new AssertionError();
            }
        }
    }
}
