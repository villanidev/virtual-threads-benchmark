package dev.villani.server.http;

import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResponseWriter")
class ResponseWriterTest {

    private final HttpResponse response = new HttpResponse(256);
    private final ByteBuffer out = ByteBuffer.allocateDirect(512);

    private String written() {
        final int pos = out.position();
        final byte[] bytes = new byte[pos];
        for (int i = 0; i < pos; i++) {
            bytes[i] = out.get(i);
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private void body(String text) {
        response.body().put(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Nested
    @DisplayName("serialisation")
    class Serialisation {

        @Test
        @DisplayName("given a 201 with JSON body, when writing, then status line, headers and body are on the wire")
        void givenBody_whenWriting_thenFullResponse() {
            // given
            response.status(201);
            body("{\"id\":1}");

            // when
            final boolean ok = ResponseWriter.write(response, out);

            // then
            assertTrue(ok);
            assertEquals("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: 8\r\n"
                    + "Connection: keep-alive\r\n\r\n{\"id\":1}", written());
        }

        @Test
        @DisplayName("given a 404 without body, when writing, then Content-Length is 0 and no Content-Type")
        void givenNoBody_whenWriting_thenZeroLength() {
            // given
            response.status(404);

            // when
            ResponseWriter.write(response, out);

            // then
            assertEquals("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n", written());
        }

        @Test
        @DisplayName("given keep-alive off, when writing, then Connection: close")
        void givenNoKeepAlive_whenWriting_thenClose() {
            // given
            response.status(400).keepAlive(false);

            // when
            ResponseWriter.write(response, out);

            // then
            assertTrue(written().endsWith("Connection: close\r\n\r\n"));
        }

        @Test
        @DisplayName("given a 204 that has body bytes, when writing, then the body is omitted")
        void given204WithBody_whenWriting_thenBodyOmitted() {
            // given
            response.status(204);
            body("ignored");

            // when
            ResponseWriter.write(response, out);

            // then
            assertEquals("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n", written());
        }

        @Test
        @DisplayName("given an unmapped status, when writing, then it falls back to 500")
        void givenUnknownStatus_whenWriting_then500() {
            // given
            response.status(418);

            // when
            ResponseWriter.write(response, out);

            // then
            assertTrue(written().startsWith("HTTP/1.1 500 Internal Server Error\r\n"));
        }

        @Test
        @DisplayName("given a multi-digit body length, when writing, then Content-Length has all digits")
        void givenLongBody_whenWriting_thenAllDigits() {
            // given
            body("x".repeat(123));

            // when
            ResponseWriter.write(response, out);

            // then
            assertTrue(written().contains("Content-Length: 123\r\n"));
        }
    }

    @Nested
    @DisplayName("capacity")
    class Capacity {

        @Test
        @DisplayName("given a destination that is too small, when writing, then false and nothing is written")
        void givenSmallDestination_whenWriting_thenFalse() {
            // given
            body("hello");
            final ByteBuffer tiny = ByteBuffer.allocateDirect(20);

            // when
            final boolean ok = ResponseWriter.write(response, tiny);

            // then
            assertFalse(ok);
            assertEquals(0, tiny.position());
        }

        @Test
        @DisplayName("given a write, when finished, then the response body is left intact for a retry")
        void givenWrite_whenFinished_thenBodyIntact() {
            // given
            body("hello");

            // when
            ResponseWriter.write(response, out);
            out.clear();
            ResponseWriter.write(response, out);

            // then
            assertTrue(written().endsWith("\r\n\r\nhello"));
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given steady-state writes, when serialising, then zero bytes are allocated")
        void givenSteadyState_whenWriting_thenZeroAlloc() {
            // given
            response.status(201);
            body("{\"id\":12345,\"status\":\"CONFIRMADO\"}");
            final Runnable body = () -> {
                out.clear();
                if (!ResponseWriter.write(response, out)) {
                    throw new AssertionError();
                }
            };

            // when / then
            AllocationProbe.assertZeroAlloc(body, 200_000, 20_000);
        }
    }
}
