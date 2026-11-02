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

@DisplayName("Router")
class RouterTest {

    private static final int CREATE = 10;
    private static final int LIST = 11;
    private static final int CANCEL = 12;

    private final ByteRing ring = new ByteRing(ByteBuffer.allocateDirect(256));
    private Router router;

    @BeforeEach
    void setUp() {
        router = new Router(8, 4, 128)
                .route(HttpRequestParser.POST, "/api/agendamentos", CREATE)
                .route(HttpRequestParser.GET, "/api/agendamentos", LIST)
                .route(HttpRequestParser.PUT, "/api/agendamentos/{id}/cancelar", CANCEL);
    }

    private int match(int method, String path) {
        ring.clear();
        ring.put(ByteBuffer.wrap(path.getBytes(StandardCharsets.US_ASCII)));
        return router.match(method, ring, 0, path.length());
    }

    @Nested
    @DisplayName("matching")
    class Matching {

        @Test
        @DisplayName("given a literal route, when matching method and path, then the handler id is returned")
        void givenLiteralRoute_whenMatching_thenHandler() {
            // given routes from setUp

            // when
            final int post = match(HttpRequestParser.POST, "/api/agendamentos");
            final int get = match(HttpRequestParser.GET, "/api/agendamentos");

            // then
            assertEquals(CREATE, post);
            assertEquals(LIST, get);
        }

        @Test
        @DisplayName("given a route with {id}, when matching, then the numeric param is extracted")
        void givenParamRoute_whenMatching_thenParamExtracted() {
            // given routes from setUp

            // when
            final int handler = match(HttpRequestParser.PUT, "/api/agendamentos/12345/cancelar");

            // then
            assertEquals(CANCEL, handler);
            assertEquals(12345, router.pathParam());
        }
    }

    @Nested
    @DisplayName("rejections")
    class Rejections {

        @Test
        @DisplayName("given an unknown path, when matching, then NOT_FOUND")
        void givenUnknownPath_whenMatching_thenNotFound() {
            // when / then
            assertEquals(Router.NOT_FOUND, match(HttpRequestParser.GET, "/nope"));
            assertEquals(Router.NOT_FOUND, match(HttpRequestParser.GET, "/api/agendamentos/"));
            assertEquals(Router.NOT_FOUND, match(HttpRequestParser.PUT, "/api/agendamentos/abc/cancelar"));
            assertEquals(Router.NOT_FOUND, match(HttpRequestParser.PUT, "/api/agendamentos/1/cancelar/x"));
            assertEquals(Router.NOT_FOUND, match(HttpRequestParser.PUT, "/api/agendamentos/1234567890/cancelar"));
        }

        @Test
        @DisplayName("given a known path with the wrong method, when matching, then METHOD_NOT_ALLOWED")
        void givenWrongMethod_whenMatching_then405() {
            // when / then
            assertEquals(Router.METHOD_NOT_ALLOWED, match(HttpRequestParser.PUT, "/api/agendamentos"));
            assertEquals(Router.METHOD_NOT_ALLOWED, match(HttpRequestParser.GET, "/api/agendamentos/1/cancelar"));
        }

        @Test
        @DisplayName("given too many routes, when registering, then it fails at startup")
        void givenTooManyRoutes_whenRegistering_thenFails() {
            // given
            final Router small = new Router(1, 2, 32).route(HttpRequestParser.GET, "/a", 1);

            // when / then
            assertThrows(IllegalStateException.class, () -> small.route(HttpRequestParser.GET, "/b", 2));
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given steady-state matching, when routing, then zero bytes are allocated")
        void givenSteadyState_whenRouting_thenZeroAlloc() {
            // given
            final String path = "/api/agendamentos/777/cancelar";
            ring.put(ByteBuffer.wrap(path.getBytes(StandardCharsets.US_ASCII)));
            final int len = path.length();
            final Runnable body = () -> {
                if (router.match(HttpRequestParser.PUT, ring, 0, len) != CANCEL || router.pathParam() != 777) {
                    throw new AssertionError();
                }
            };

            // when / then
            AllocationProbe.assertZeroAlloc(body, 200_000, 20_000);
        }
    }
}
