package dev.villani.application.agendamento;

import dev.villani.server.http.DelayPolicy;
import dev.villani.server.net.Transport;
import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgendamentoServer (real sockets, async create)")
class AgendamentoServerTest {

    private static final long JAN_2 = LocalDate.of(2026, 1, 2).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    private static final int DELAY_MS = 20;

    private AgendamentoServer app;
    private Thread loop;
    private Socket client;

    private void start(int connections, int delayMs) throws IOException {
        app = new AgendamentoServer("127.0.0.1", 0, connections, DelayPolicy.fixed(delayMs),
                () -> System.nanoTime() / 1_000_000, Transport.fromProperty());
    }

    private void startLoop() {
        loop = new Thread(() -> {
            try {
                app.run();
            } catch (IOException ignored) {
                // closed by the test
            }
        });
        loop.start();
    }

    private Socket connect() throws IOException {
        final Socket s = new Socket("127.0.0.1", app.port());
        s.setSoTimeout(5_000);
        s.setTcpNoDelay(true);
        client = s;
        return s;
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (app != null) {
            app.stop();
            if (loop != null) {
                loop.join(2_000);
            }
            app.close();
        }
    }

    private static String post(String nome, int servico, long dataHora, String extraHeaders) {
        final String json = "{\"nome\":\"" + nome + "\",\"servicoId\":" + servico + ",\"dataHora\":" + dataHora + "}";
        return "POST /api/agendamentos HTTP/1.1\r\n" + extraHeaders + "Content-Length: " + json.length() + "\r\n\r\n" + json;
    }

    private static String post(String nome, int servico, long dataHora) {
        return post(nome, servico, dataHora, "");
    }

    private static void send(Socket s, String raw) throws IOException {
        s.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
    }

    /** Reads one HTTP response (head + Content-Length body). */
    private static String readResponse(Socket s) throws IOException {
        final InputStream in = s.getInputStream();
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            final int b = in.read();
            if (b < 0) {
                throw new IOException("closed before a full response head");
            }
            head.write(b);
        }
        final String text = head.toString(StandardCharsets.US_ASCII);
        final int i = text.indexOf("Content-Length: ");
        final int length = i < 0 ? 0 : Integer.parseInt(text.substring(i + 16, text.indexOf("\r\n", i)));
        final byte[] body = in.readNBytes(length);
        return text + new String(body, StandardCharsets.US_ASCII);
    }

    @Nested
    @DisplayName("deferred create")
    class DeferredCreate {

        @Test
        @DisplayName("given a 20 ms external call, when creating, then 201 arrives only after the delay")
        void givenExternalDelay_whenCreating_thenRespondsAfterDelay() throws Exception {
            // given
            start(8, DELAY_MS);
            startLoop();
            final Socket s = connect();

            // when
            final long t0 = System.nanoTime();
            send(s, post("Ana", 1, JAN_2 + 3600));
            final String res = readResponse(s);
            final long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            // then
            assertTrue(res.startsWith("HTTP/1.1 201"), res);
            assertTrue(res.endsWith("{\"id\":1,\"status\":\"CONFIRMADO\"}"), res);
            assertTrue(elapsedMs >= DELAY_MS - 1, "elapsed " + elapsedMs);
        }

        @Test
        @DisplayName("given two creates for the same slot of time, when both are in flight, then exactly one wins and the other gets 409")
        void givenSameSlot_whenTwoInFlight_thenOneConflicts() throws Exception {
            // given
            start(8, DELAY_MS);
            startLoop();
            final Socket a = connect();
            final Socket b = new Socket("127.0.0.1", app.port());
            b.setSoTimeout(5_000);

            // when
            send(a, post("Ana", 1, JAN_2 + 3600));
            send(b, post("Bia", 1, JAN_2 + 3600));
            final String first = readResponse(a);
            final String second = readResponse(b);
            b.close();

            // then
            final boolean firstWon = first.startsWith("HTTP/1.1 201");
            assertTrue(firstWon ? second.startsWith("HTTP/1.1 409") : second.startsWith("HTTP/1.1 201") && first.startsWith("HTTP/1.1 409"),
                    first + " / " + second);
        }

        @Test
        @DisplayName("given a created appointment, when listing that day, then it appears in the JSON array")
        void givenCreated_whenListing_thenAppears() throws Exception {
            // given
            start(8, DELAY_MS);
            startLoop();
            final Socket s = connect();
            send(s, post("Ana", 1, JAN_2 + 3600));
            readResponse(s);

            // when
            send(s, "GET /api/agendamentos?data=2026-01-02 HTTP/1.1\r\n\r\n");
            final String res = readResponse(s);

            // then
            assertTrue(res.startsWith("HTTP/1.1 200"), res);
            assertTrue(res.contains("Ana"), res);
        }
    }

    @Nested
    @DisplayName("ordering and connection lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("given a pipelined create followed by a list, when sent together, then responses keep request order")
        void givenPipelinedRequests_whenSentTogether_thenOrderKept() throws Exception {
            // given
            start(8, DELAY_MS);
            startLoop();
            final Socket s = connect();

            // when
            send(s, post("Ana", 1, JAN_2 + 3600) + "GET /api/agendamentos?data=2026-01-02 HTTP/1.1\r\n\r\n");
            final String first = readResponse(s);
            final String second = readResponse(s);

            // then
            assertTrue(first.startsWith("HTTP/1.1 201"), first);
            assertTrue(second.startsWith("HTTP/1.1 200") && second.contains("Ana"), second);
        }

        @Test
        @DisplayName("given Connection: close, when the deferred response is sent, then the server closes the socket")
        void givenConnectionClose_whenResponded_thenSocketClosed() throws Exception {
            // given
            start(8, DELAY_MS);
            startLoop();
            final Socket s = connect();

            // when
            send(s, post("Ana", 1, JAN_2 + 3600, "Connection: close\r\n"));
            final String res = readResponse(s);
            final int eof = s.getInputStream().read();

            // then
            assertTrue(res.startsWith("HTTP/1.1 201"), res);
            assertEquals(-1, eof);
        }

        @Test
        @DisplayName("given clients that disconnect mid-deferral, when many do so, then pooled records are released")
        void givenAbandonedCreates_whenManyDisconnect_thenRecordsReleased() throws Exception {
            // given
            start(2, 50);
            startLoop();
            for (int i = 0; i < 6; i++) {
                final Socket s = new Socket("127.0.0.1", app.port());
                send(s, post("Zed", 1, JAN_2 + 60L * i));
                s.close();
                final long deadline = System.nanoTime() + 2_000_000_000L;
                while (app.loop().activeConnections() > 0 && System.nanoTime() < deadline) {
                    Thread.sleep(2);
                }
            }

            // when
            final Socket s = connect();
            send(s, post("Ana", 2, JAN_2));
            final String res = readResponse(s);

            // then
            assertTrue(res.startsWith("HTTP/1.1 201"), res);
        }
    }

    @Nested
    @DisplayName("boot warm-up")
    class WarmUp {

        @Test
        @DisplayName("given an end-to-end warm-up, when it finishes, then the real server starts with no fake data")
        void givenWarmUp_whenFinished_thenRealServerIsPristine() throws Exception {
            // given
            AgendamentoServer.warmUp(300);
            start(8, DELAY_MS);
            startLoop();
            final Socket s = connect();

            // when
            send(s, post("Ana", 1, JAN_2 + 3600));
            final String created = readResponse(s);
            send(s, "GET /api/agendamentos?data=2026-01-02 HTTP/1.1\r\n\r\n");
            final String listed = readResponse(s);

            // then
            assertTrue(created.endsWith("{\"id\":1,\"status\":\"CONFIRMADO\"}"), created);
            assertFalse(listed.contains("Warm"), listed);
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given an established keep-alive connection, when serving creates and lists, then the loop allocates 0 bytes")
        void givenKeepAliveConnection_whenServing_thenZeroAlloc() throws Exception {
            // given
            start(8, 1);
            final Socket s = connect();
            final AtomicBoolean stop = new AtomicBoolean();
            final String create = post("Ana", 1, JAN_2 + 3600);
            final String list = "GET /api/agendamentos?data=2026-01-02 HTTP/1.1\r\n\r\n";
            final Thread load = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        send(s, create);
                        readResponse(s);
                        send(s, list);
                        readResponse(s);
                    }
                } catch (IOException ignored) {
                    // test over
                }
            });
            load.start();
            final Runnable iteration = () -> {
                try {
                    app.loop().runOnce(1);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            };

            // when
            final long bytes = AllocationProbe.bytesAllocated(iteration, 20_000, 20_000);
            stop.set(true);

            // then
            assertEquals(0, bytes);
        }
    }
}
