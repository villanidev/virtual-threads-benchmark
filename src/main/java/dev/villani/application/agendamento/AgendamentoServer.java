package dev.villani.application.agendamento;

import dev.villani.server.http.DelayPolicy;
import dev.villani.server.http.Dispatcher;
import dev.villani.server.http.HttpConnectionHandler;
import dev.villani.server.http.HttpRequestParser;
import dev.villani.server.http.HttpResponse;
import dev.villani.server.memory.ConnectionPool;
import dev.villani.server.memory.Regions;
import dev.villani.server.net.NioServer;
import dev.villani.server.os.CpuAffinity;
import dev.villani.server.time.TimerWheel;

import java.io.IOException;
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
    public static final int RECORDS = 200_000;
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
     * Optional end-to-end boot warm-up with fake data (see {@link AgendamentoWarmUp}): a throwaway instance is
     * driven over loopback sockets for {@code cycles} create/list/cancel cycles before the real port opens.
     * Boot-time cost only, never needed for correctness.
     */
    public static void warmUp(int cycles) throws IOException, InterruptedException {
        AgendamentoWarmUp.run(cycles);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        final int delayMs = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        final int warm = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        final int pinned = CpuAffinity.pinFromProperty();
        System.out.println("memory=" + Regions.mode() + (pinned >= 0 ? " pinned-to-cpu=" + pinned : ""));
        if (warm > 0) {
            final long t0 = System.nanoTime();
            warmUp(warm);
            System.out.println("warm-up: " + warm + " cycles in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        }
        try (AgendamentoServer app = new AgendamentoServer("0.0.0.0", port, DEFAULT_CONNECTIONS,
                DelayPolicy.fixed(delayMs), () -> System.nanoTime() / 1_000_000)) {
            System.out.println("agendamento listening on :" + app.port() + " (create delay " + delayMs + " ms)");
            app.run();
        }
    }
}
