package dev.villani.server.net;

import dev.villani.server.memory.ByteRing;
import dev.villani.server.memory.ConnectionPool;
import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NioServer")
class NioServerTest {

    /** Echoes every readable byte back. */
    private static final ConnectionHandler ECHO = (server, slot) -> {
        final ByteRing in = server.readRing(slot);
        final ByteBuffer out = server.writeBuffer(slot);
        if (in.readable() == 0) {
            return;
        }
        while (in.readable() > 0 && out.hasRemaining()) {
            out.put(in.get());
        }
        server.flush(slot);
    };

    /** Replies "OK\n" only once a full line (terminated by \n) has arrived, consuming that line. */
    private static final ConnectionHandler LINE_ACK = (server, slot) -> {
        final ByteRing in = server.readRing(slot);
        for (int i = 0; i < in.readable(); i++) {
            if (in.peek(i) == '\n') {
                in.skip(i + 1);
                server.writeBuffer(slot).put((byte) 'O').put((byte) 'K').put((byte) '\n');
                server.flush(slot);
                return;
            }
        }
    };

    private final List<SocketChannel> clients = new ArrayList<>();
    private NioServer server;

    @AfterEach
    void tearDown() throws IOException {
        for (SocketChannel c : clients) {
            c.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private NioServer startServer(int slots, ConnectionHandler handler) throws IOException {
        server = new NioServer("127.0.0.1", 0, new ConnectionPool(slots, 4096, 4096), handler);
        return server;
    }

    private SocketChannel connect() throws IOException {
        final SocketChannel c = SocketChannel.open(new InetSocketAddress("127.0.0.1", server.localPort()));
        c.configureBlocking(false);
        clients.add(c);
        return c;
    }

    private static void send(SocketChannel c, String text) throws IOException {
        final ByteBuffer b = ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
        while (b.hasRemaining()) {
            c.write(b);
        }
    }

    private String receive(SocketChannel c, int bytes) throws IOException {
        final ByteBuffer b = ByteBuffer.allocate(bytes);
        final long deadline = System.nanoTime() + 3_000_000_000L;
        while (b.hasRemaining() && System.nanoTime() < deadline) {
            server.runOnce(5);
            c.read(b);
        }
        return new String(b.array(), 0, b.position(), StandardCharsets.US_ASCII);
    }

    private void pumpUntil(java.util.function.BooleanSupplier condition) throws IOException {
        final long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            server.runOnce(5);
        }
    }

    @Nested
    @DisplayName("connections")
    class Connections {

        @Test
        @DisplayName("given a running server, when a client connects, then it is accepted into a pool slot")
        void givenServer_whenClientConnects_thenAcceptedIntoSlot() throws IOException {
            // given
            startServer(4, ECHO);

            // when
            connect();
            pumpUntil(() -> server.activeConnections() == 1);

            // then
            assertEquals(1, server.activeConnections());
        }

        @Test
        @DisplayName("given a connected client, when it closes, then its slot is released")
        void givenConnectedClient_whenItCloses_thenSlotReleased() throws IOException {
            // given
            startServer(4, ECHO);
            final SocketChannel client = connect();
            pumpUntil(() -> server.activeConnections() == 1);

            // when
            client.close();
            pumpUntil(() -> server.activeConnections() == 0);

            // then
            assertEquals(0, server.activeConnections());
        }
    }

    @Nested
    @DisplayName("request handling")
    class RequestHandling {

        @Test
        @DisplayName("given a connected client, when it sends bytes, then the handler's response is received")
        void givenClient_whenSendingBytes_thenResponseReceived() throws IOException {
            // given
            startServer(4, ECHO);
            final SocketChannel client = connect();

            // when
            send(client, "hello flat world");

            // then
            assertEquals("hello flat world", receive(client, 16));
        }

        @Test
        @DisplayName("given a request split in two packets, when both arrive, then the handler answers once with the full request")
        void givenSplitRequest_whenBothPartsArrive_thenSingleAnswer() throws IOException {
            // given
            startServer(4, LINE_ACK);
            final SocketChannel client = connect();
            send(client, "partial");
            pumpUntil(() -> server.readRing(0).readable() == 7);
            assertEquals(7, server.readRing(0).readable());

            // when
            send(client, " line\n");

            // then
            assertEquals("OK\n", receive(client, 3));
            assertEquals(0, server.readRing(0).readable());
        }

        @Test
        @DisplayName("given two pipelined requests in one packet, when the handler runs, then both are answered")
        void givenPipelinedRequests_whenHandled_thenBothAnswered() throws IOException {
            // given
            final ConnectionHandler allLines = (srv, slot) -> {
                final ByteRing in = srv.readRing(slot);
                boolean any = false;
                int i = 0;
                while (i < in.readable()) {
                    if (in.peek(i) == '\n') {
                        in.skip(i + 1);
                        srv.writeBuffer(slot).put((byte) 'K');
                        any = true;
                        i = 0;
                    } else {
                        i++;
                    }
                }
                if (any) {
                    srv.flush(slot);
                }
            };
            startServer(4, allLines);
            final SocketChannel client = connect();

            // when
            send(client, "a\nb\n");

            // then
            assertEquals("KK", receive(client, 2));
        }

        @Test
        @DisplayName("given a client sending more than the read ring holds without a complete request, when handled, then it is disconnected")
        void givenOversizedRequest_whenRingFills_thenDisconnected() throws IOException {
            // given
            startServer(4, LINE_ACK);
            final SocketChannel client = connect();
            pumpUntil(() -> server.activeConnections() == 1);

            // when
            send(client, "x".repeat(4096));
            pumpUntil(() -> server.activeConnections() == 0);

            // then
            assertEquals(0, server.activeConnections());
        }
    }

    @Nested
    @DisplayName("backpressure")
    class Backpressure {

        @Test
        @DisplayName("given every slot in use, when another client connects, then it is served only after a slot is freed")
        void givenPoolExhausted_whenAnotherClientConnects_thenServedAfterSlotFreed() throws IOException {
            // given
            startServer(1, ECHO);
            final SocketChannel first = connect();
            pumpUntil(() -> server.activeConnections() == 1);
            final SocketChannel second = connect();
            send(second, "late");

            // when
            final String whileFull = receive(second, 4).isEmpty() ? "" : "served";
            first.close();

            // then
            assertEquals("", whileFull);
            assertEquals("late", receive(second, 4));
        }

        @Test
        @DisplayName("given a client that does not read its responses, when the socket buffer fills, then writing is paused and resumes once it drains")
        void givenSlowReader_whenSocketBufferFills_thenWritePausedThenResumes() throws IOException {
            // given
            startServer(2, ECHO);
            final SocketChannel client = connect();
            final byte[] chunk = new byte[4096];
            java.util.Arrays.fill(chunk, (byte) 'z');
            long sent = 0;

            // when
            final long deadline = System.nanoTime() + 10_000_000_000L;
            while (!server.isWritePending(0) && System.nanoTime() < deadline) {
                final ByteBuffer b = ByteBuffer.wrap(chunk);
                sent += client.write(b);
                server.runOnce(1);
            }

            // then
            assertTrue(server.isWritePending(0), "server should have paused writing");
            final ByteBuffer sink = ByteBuffer.allocate(1 << 16);
            long received = 0;
            final long drainDeadline = System.nanoTime() + 10_000_000_000L;
            while (received < sent && System.nanoTime() < drainDeadline) {
                server.runOnce(1);
                sink.clear();
                final int n = client.read(sink);
                if (n > 0) {
                    received += n;
                }
            }
            assertEquals(sent, received);
            assertFalse(server.isWritePending(0));
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given an established keep-alive connection, when requests are served steadily, then the event loop allocates nothing")
        void givenKeepAliveConnection_whenServingSteadily_thenLoopAllocatesNothing() throws Exception {
            // given
            startServer(4, ECHO);
            final SocketChannel setup = connect();
            setup.configureBlocking(true);
            final AtomicBoolean stop = new AtomicBoolean();
            final AtomicLong roundTrips = new AtomicLong();
            final Thread loadThread = Thread.ofPlatform().daemon().start(() -> {
                final ByteBuffer req = ByteBuffer.allocateDirect(100);
                final ByteBuffer resp = ByteBuffer.allocateDirect(100);
                try {
                    while (!stop.get()) {
                        req.clear().limit(100);
                        setup.write(req);
                        resp.clear();
                        while (resp.hasRemaining()) {
                            if (setup.read(resp) < 0) {
                                return;
                            }
                        }
                        roundTrips.incrementAndGet();
                    }
                } catch (IOException ignored) {
                    // test finished
                }
            });
            final Runnable loopOnce = () -> {
                try {
                    server.runOnce(1);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };

            // when
            final long bytes;
            try {
                bytes = AllocationProbe.bytesAllocated(loopOnce, 20_000, 20_000);
            } finally {
                stop.set(true);
            }

            // then
            assertTrue(roundTrips.get() > 1_000, "load thread should have driven requests: " + roundTrips.get());
            assertEquals(0, bytes, "event loop allocated " + bytes + " bytes");
            loadThread.join(2_000);
        }
    }
}
