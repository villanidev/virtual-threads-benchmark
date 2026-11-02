package dev.villani.server.http;

import dev.villani.server.memory.ByteRing;
import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HttpRequestParser")
class HttpRequestParserTest {

    private final ByteRing ring = new ByteRing(ByteBuffer.allocateDirect(4096));
    private final HttpRequestParser parser = new HttpRequestParser(2);

    private void feed(String text) {
        ring.put(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private String slice(int start, int len) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append((char) ring.peek(start + i));
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("complete requests")
    class CompleteRequests {

        @Test
        @DisplayName("given a GET with query, when parsing, then method, path and query offsets are exposed")
        void givenGetWithQuery_whenParsing_thenOffsets() {
            // given
            feed("GET /api/agendamentos?data=2026-01-02 HTTP/1.1\r\nHost: x\r\n\r\n");

            // when
            final int result = parser.parse(0, ring);

            // then
            assertEquals(HttpRequestParser.COMPLETE, result);
            assertEquals(HttpRequestParser.GET, parser.method(0));
            assertEquals("/api/agendamentos", slice(parser.pathStart(0), parser.pathLength(0)));
            assertEquals("data=2026-01-02", slice(parser.queryStart(0), parser.queryLength(0)));
            assertTrue(parser.keepAlive(0));
        }

        @Test
        @DisplayName("given a POST with body, when parsing, then the body offset and length are exposed")
        void givenPostWithBody_whenParsing_thenBodyExposed() {
            // given
            feed("POST /x HTTP/1.1\r\nContent-Length: 5\r\ncontent-type: application/json\r\n\r\nhello");

            // when
            final int result = parser.parse(0, ring);

            // then
            assertEquals(HttpRequestParser.COMPLETE, result);
            assertEquals(HttpRequestParser.POST, parser.method(0));
            assertEquals(-1, parser.queryStart(0));
            assertEquals("hello", slice(parser.bodyStart(0), parser.contentLength(0)));
        }

        @Test
        @DisplayName("given Connection: close or HTTP/1.0, when parsing, then keep-alive is false")
        void givenCloseOrHttp10_whenParsing_thenNoKeepAlive() {
            // given
            feed("GET / HTTP/1.1\r\nConnection: close\r\n\r\n");

            // when
            parser.parse(0, ring);

            // then
            assertFalse(parser.keepAlive(0));
        }

        @Test
        @DisplayName("given two pipelined requests, when consuming the first, then the second parses")
        void givenPipelined_whenConsuming_thenSecondParses() {
            // given
            feed("GET /a HTTP/1.1\r\n\r\nPUT /b/1/cancelar HTTP/1.1\r\n\r\n");
            assertEquals(HttpRequestParser.COMPLETE, parser.parse(0, ring));

            // when
            parser.consume(0, ring);
            final int result = parser.parse(0, ring);

            // then
            assertEquals(HttpRequestParser.COMPLETE, result);
            assertEquals(HttpRequestParser.PUT, parser.method(0));
            assertEquals("/b/1/cancelar", slice(parser.pathStart(0), parser.pathLength(0)));
        }
    }

    @Nested
    @DisplayName("partial input")
    class PartialInput {

        @Test
        @DisplayName("given a request arriving byte by byte, when parsing each time, then it completes only at the end")
        void givenByteByByte_whenParsing_thenCompletesAtEnd() {
            // given
            final String req = "POST /x HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc";

            // when / then
            for (int i = 0; i < req.length() - 1; i++) {
                feed(req.substring(i, i + 1));
                assertEquals(HttpRequestParser.NEED_MORE, parser.parse(0, ring), "at byte " + i);
            }
            feed("c");
            assertEquals(HttpRequestParser.COMPLETE, parser.parse(0, ring));
            assertEquals("abc", slice(parser.bodyStart(0), parser.contentLength(0)));
        }
    }

    @Nested
    @DisplayName("malformed or oversized input")
    class Errors {

        @Test
        @DisplayName("given a request line without version, when parsing, then 400")
        void givenBadRequestLine_whenParsing_then400() {
            // given
            feed("GET /x\r\n\r\n");

            // when
            final int result = parser.parse(0, ring);

            // then
            assertEquals(HttpRequestParser.ERROR, result);
            assertEquals(400, parser.errorStatus(0));
        }

        @Test
        @DisplayName("given a non-numeric Content-Length, when parsing, then 400")
        void givenBadContentLength_whenParsing_then400() {
            // given
            feed("POST /x HTTP/1.1\r\nContent-Length: abc\r\n\r\n");

            // when
            parser.parse(0, ring);

            // then
            assertEquals(400, parser.errorStatus(0));
        }

        @Test
        @DisplayName("given Transfer-Encoding, when parsing, then 501 because chunked is unsupported")
        void givenChunked_whenParsing_then501() {
            // given
            feed("POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n");

            // when
            parser.parse(0, ring);

            // then
            assertEquals(501, parser.errorStatus(0));
        }

        @Test
        @DisplayName("given a body larger than the ring, when parsing, then 413")
        void givenHugeBody_whenParsing_then413() {
            // given
            feed("POST /x HTTP/1.1\r\nContent-Length: 100000\r\n\r\n");

            // when
            parser.parse(0, ring);

            // then
            assertEquals(413, parser.errorStatus(0));
        }

        @Test
        @DisplayName("given headers that never end, when the ring fills, then 431")
        void givenEndlessHeaders_whenRingFills_then431() {
            // given
            feed("GET / HTTP/1.1\r\n");
            while (ring.writable() > 0) {
                feed("X-A: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\r\n");
            }

            // when
            parser.parse(0, ring);

            // then
            assertEquals(431, parser.errorStatus(0));
        }

        @Test
        @DisplayName("given an error on one slot, when parsing another slot, then it is unaffected")
        void givenErrorOnSlot_whenOtherSlot_thenIndependent() {
            // given
            feed("GET /x\r\n\r\n");
            parser.parse(0, ring);
            final ByteRing other = new ByteRing(ByteBuffer.allocateDirect(1024));
            other.put(ByteBuffer.wrap("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));

            // when
            final int result = parser.parse(1, other);

            // then
            assertEquals(HttpRequestParser.COMPLETE, result);
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given steady-state parsing of POST requests, when parsing and consuming, then zero bytes are allocated")
        void givenSteadyState_whenParsing_thenZeroAlloc() {
            // given
            final ByteBuffer req = ByteBuffer.wrap(
                    "POST /api/agendamentos HTTP/1.1\r\nHost: x\r\nContent-Length: 11\r\nConnection: keep-alive\r\n\r\nhello world"
                            .getBytes(StandardCharsets.US_ASCII));
            final Runnable body = () -> {
                req.clear();
                ring.put(req);
                if (parser.parse(0, ring) != HttpRequestParser.COMPLETE) {
                    throw new AssertionError();
                }
                parser.consume(0, ring);
            };

            // when / then
            AllocationProbe.assertZeroAlloc(body, 20_000, 20_000);
        }
    }
}
