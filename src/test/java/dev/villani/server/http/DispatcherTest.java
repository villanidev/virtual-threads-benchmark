package dev.villani.server.http;

import dev.villani.server.memory.ByteRing;
import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Dispatcher and request flyweight")
class DispatcherTest {

    private static final int ECHO = 0;
    private static final int CANCEL = 1;
    private static final byte[] DATA = "data".getBytes(StandardCharsets.US_ASCII);

    private final ByteRing ring = new ByteRing(ByteBuffer.allocateDirect(4096));
    private final HttpResponse response = new HttpResponse(256);
    private final HttpRequestParser parser = new HttpRequestParser(1);
    private int lastParam;
    private int lastBodyLength;
    private int lastQueryLength;
    private Dispatcher dispatcher;

    @BeforeEach
    void setUp() {
        final Router router = new Router(4, 4, 64)
                .route(HttpRequestParser.POST, "/echo", ECHO)
                .route(HttpRequestParser.PUT, "/items/{id}/cancel", CANCEL);
        dispatcher = new Dispatcher(parser, router, 4)
                .register(ECHO, (req, res) -> {
                    lastBodyLength = req.bodyLength();
                    final ByteBuffer out = res.body();
                    for (int i = 0; i < req.bodyLength(); i++) {
                        out.put(req.bodyByte(i));
                    }
                    final int v = req.findQueryParam(DATA);
                    lastQueryLength = v < 0 ? -1 : req.queryValueLength();
                    res.status(201);
                })
                .register(CANCEL, (req, res) -> {
                    lastParam = req.pathParam();
                    res.status(204);
                });
    }

    private int send(String raw) {
        ring.put(ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII)));
        return dispatcher.process(0, ring, response);
    }

    @Nested
    @DisplayName("dispatching")
    class Dispatching {

        @Test
        @DisplayName("given a POST with body, when dispatching, then the handler sees the body and fills the response")
        void givenPost_whenDispatching_thenHandlerRuns() {
            // given / when
            final int result = send("POST /echo?x=1&data=2026-01-02 HTTP/1.1\r\nContent-Length: 4\r\n\r\nabcd");

            // then
            assertEquals(Dispatcher.RESPONSE_READY, result);
            assertEquals(201, response.status());
            assertEquals(4, lastBodyLength);
            assertEquals(10, lastQueryLength);
            assertEquals('a', response.body().get(0));
            assertEquals(0, ring.readable(), "request must be consumed");
        }

        @Test
        @DisplayName("given a route with {id}, when dispatching, then the handler gets the param")
        void givenIdRoute_whenDispatching_thenParam() {
            // given / when
            send("PUT /items/42/cancel HTTP/1.1\r\n\r\n");

            // then
            assertEquals(42, lastParam);
            assertEquals(204, response.status());
        }

        @Test
        @DisplayName("given an incomplete request, when dispatching, then NEED_MORE and nothing is consumed")
        void givenIncomplete_whenDispatching_thenNeedMore() {
            // given / when
            final int result = send("POST /echo HTTP/1.1\r\nContent-Length: 4\r\n\r\nab");

            // then
            assertEquals(Dispatcher.NEED_MORE, result);
            assertTrue(ring.readable() > 0);
        }
    }

    @Nested
    @DisplayName("routing and protocol errors")
    class Errors {

        @Test
        @DisplayName("given an unknown path or wrong method, when dispatching, then 404 or 405 and keep-alive stays")
        void givenUnroutable_whenDispatching_then404or405() {
            // given / when / then
            send("GET /nope HTTP/1.1\r\n\r\n");
            assertEquals(404, response.status());
            assertTrue(response.keepAlive());

            send("GET /echo HTTP/1.1\r\n\r\n");
            assertEquals(405, response.status());
        }

        @Test
        @DisplayName("given a malformed request, when dispatching, then the parser status is returned and the connection closes")
        void givenMalformed_whenDispatching_thenErrorAndClose() {
            // given / when
            final int result = send("GARBAGE\r\n\r\n");

            // then
            assertEquals(Dispatcher.RESPONSE_READY, result);
            assertEquals(400, response.status());
            assertFalse(response.keepAlive());
        }

        @Test
        @DisplayName("given Connection: close, when dispatching, then the response is marked to close")
        void givenConnectionClose_whenDispatching_thenNoKeepAlive() {
            // given / when
            send("GET /nope HTTP/1.1\r\nConnection: close\r\n\r\n");

            // then
            assertFalse(response.keepAlive());
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given steady-state requests, when dispatching, then zero bytes are allocated")
        void givenSteadyState_whenDispatching_thenZeroAlloc() {
            // given
            final ByteBuffer req = ByteBuffer.wrap(
                    "POST /echo?data=2026-01-02 HTTP/1.1\r\nContent-Length: 4\r\n\r\nabcd".getBytes(StandardCharsets.US_ASCII));
            final Runnable body = () -> {
                req.clear();
                ring.put(req);
                if (dispatcher.process(0, ring, response) != Dispatcher.RESPONSE_READY || response.status() != 201) {
                    throw new AssertionError();
                }
            };

            // when / then
            AllocationProbe.assertZeroAlloc(body, 200_000, 20_000);
        }
    }
}
