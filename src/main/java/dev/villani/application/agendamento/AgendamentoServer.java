package dev.villani.application.agendamento;

import dev.villani.server.http.DelayPolicy;
import dev.villani.server.http.Dispatcher;
import dev.villani.server.http.HttpConnectionHandler;
import dev.villani.server.http.HttpRequestParser;
import dev.villani.server.http.HttpResponse;
import dev.villani.server.http.ResponseWriter;
import dev.villani.server.memory.ByteRing;
import dev.villani.server.memory.ConnectionPool;
import dev.villani.server.net.NioServer;
import dev.villani.server.time.TimerWheel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * Assembles the whole flat stack: pooled connections, parser, router, handlers, timer wheel and the
 * NIO loop. Everything is sized and allocated here, at start-up.
 */
public final class AgendamentoServer implements AutoCloseable {

    public static final int DEFAULT_CONNECTIONS = 256;
    public static final int READ_BYTES = 4096;
    public static final int WRITE_BYTES = 16384;
    public static final int RESPONSE_BODY_BYTES = 8192;
    public static final int WHEEL_SLOTS = 256;
    public static final int RECORDS = 100_000;
    public static final int DAYS = 4096;

    private final NioServer server;

    public AgendamentoServer(String host, int port, int maxConnections, DelayPolicy createDelay,
                             LongSupplier clockMs) throws IOException {
        final ConnectionPool pool = new ConnectionPool(maxConnections, READ_BYTES, WRITE_BYTES);
        final Dispatcher dispatcher = newDispatcher(new AgendamentoTable(RECORDS, DAYS), maxConnections, createDelay);
        final TimerWheel wheel = new TimerWheel(WHEEL_SLOTS, 1, maxConnections, clockMs.getAsLong());
        final HttpConnectionHandler handler = new HttpConnectionHandler(
                dispatcher, new HttpResponse(RESPONSE_BODY_BYTES), wheel, clockMs, maxConnections);
        this.server = new NioServer(host, port, pool, handler);
    }

    private static Dispatcher newDispatcher(AgendamentoTable table, int maxConnections, DelayPolicy createDelay) {
        final Dispatcher dispatcher = new Dispatcher(
                new HttpRequestParser(maxConnections), AgendamentoRoutes.newRouter(), AgendamentoRoutes.HANDLER_COUNT);
        AgendamentoRoutes.install(dispatcher, table, maxConnections, maxConnections, createDelay);
        return dispatcher;
    }

    public NioServer nio() {
        return server;
    }

    public int port() throws IOException {
        return server.localPort();
    }

    public void run() throws IOException {
        server.run();
    }

    public void stop() {
        server.stop();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    /**
     * Optional boot warm-up: pushes {@code iterations} synthetic create/list/cancel requests through a
     * throwaway stack built from the same classes, so the JIT compiles the request path before the port
     * takes real traffic. It touches no state of this server. Boot-time cost only, never needed for correctness.
     */
    public static void warmUp(int iterations) {
        final Dispatcher dispatcher = newDispatcher(new AgendamentoTable(1024, 64), 1, null);
        final ByteRing ring = new ByteRing(ByteBuffer.allocateDirect(READ_BYTES));
        final HttpResponse response = new HttpResponse(RESPONSE_BODY_BYTES);
        final ByteBuffer wire = ByteBuffer.allocateDirect(WRITE_BYTES);
        final long day = 1_767_225_600L; // 2026-01-01T00:00:00Z
        for (int i = 0; i < iterations; i++) {
            final String json = "{\"nome\":\"warm\",\"servicoId\":" + (i & 3) + ",\"dataHora\":" + (day + (i % 900) * 60L) + "}";
            warmCall(dispatcher, ring, response, wire, "POST /api/agendamentos HTTP/1.1\r\nContent-Length: "
                    + json.length() + "\r\n\r\n" + json);
            warmCall(dispatcher, ring, response, wire, "GET /api/agendamentos?data=2026-01-01 HTTP/1.1\r\n\r\n");
            warmCall(dispatcher, ring, response, wire, "PUT /api/agendamentos/" + (i % 900 + 1) + "/cancelar HTTP/1.1\r\n\r\n");
        }
    }

    private static void warmCall(Dispatcher dispatcher, ByteRing ring, HttpResponse response, ByteBuffer wire, String raw) {
        ring.put(ByteBuffer.wrap(raw.getBytes(StandardCharsets.US_ASCII)));
        dispatcher.process(0, ring, response);
        wire.clear();
        ResponseWriter.write(response, wire);
    }

    public static void main(String[] args) throws IOException {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        final int delayMs = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        final int warm = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        if (warm > 0) {
            warmUp(warm);
        }
        try (AgendamentoServer app = new AgendamentoServer("0.0.0.0", port, DEFAULT_CONNECTIONS,
                DelayPolicy.fixed(delayMs), () -> System.nanoTime() / 1_000_000)) {
            System.out.println("agendamento listening on :" + app.port() + " (create delay " + delayMs + " ms)");
            app.run();
        }
    }
}
