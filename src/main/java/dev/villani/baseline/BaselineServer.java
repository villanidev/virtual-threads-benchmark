package dev.villani.baseline;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.villani.application.agendamento.AgendamentoServer;
import dev.villani.application.agendamento.AgendamentoWarmUp;
import dev.villani.baseline.heap.HeapController;
import dev.villani.baseline.heap.HeapStore;
import dev.villani.baseline.offheap.OffHeapController;
import dev.villani.application.agendamento.AgendamentoTable;
import dev.villani.server.http.DelayPolicy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;

/** Agendamento API on the JDK {@link HttpServer} with a selectable thread model (the traditional counterpart). */
public final class BaselineServer implements AutoCloseable {

    public enum Memory {
        HEAP,
        OFFHEAP
    }

    private final HttpServer http;
    private final ExecutorService executor;

    public BaselineServer(String host, int port, ConcurrencyModel model, Memory memory, int pool,
                          DelayPolicy delay) throws IOException {
        final HttpHandler handler = memory == Memory.OFFHEAP
                ? new OffHeapController(new AgendamentoTable(AgendamentoServer.RECORDS, AgendamentoServer.DAYS),
                        AgendamentoServer.DEFAULT_CONNECTIONS, delay)
                : new HeapController(new HeapStore(AgendamentoServer.RECORDS, AgendamentoServer.DAYS), delay);
        this.executor = model.newExecutor(pool);
        this.http = HttpServer.create(new InetSocketAddress(host, port), 1024);
        http.createContext("/", handler);
        http.setExecutor(executor);
    }

    public void start() {
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        http.stop(0);
        executor.shutdownNow();
    }

    /** End-to-end boot warm-up on a throwaway instance (own store), same scheme as the flat server. */
    public static void warmUp(ConcurrencyModel model, Memory memory, int pool, int cycles)
            throws IOException, InterruptedException {
        try (BaselineServer warm = new BaselineServer("127.0.0.1", 0, model, memory, pool, DelayPolicy.fixed(1))) {
            warm.start();
            AgendamentoWarmUp.drive(warm.port(), cycles, false);
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        final int delayMs = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        final ConcurrencyModel model = ConcurrencyModel.valueOf((args.length > 2 ? args[2] : "VIRTUAL").toUpperCase());
        final Memory memory = Memory.valueOf((args.length > 3 ? args[3] : "HEAP").toUpperCase());
        final int pool = args.length > 4 ? Integer.parseInt(args[4]) : 200;
        final int warm = args.length > 5 ? Integer.parseInt(args[5]) : 0;
        if (warm > 0) {
            final long t0 = System.nanoTime();
            warmUp(model, memory, pool, warm);
            System.out.println("warm-up: " + warm + " cycles in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        }
        final BaselineServer app = new BaselineServer("0.0.0.0", port, model, memory, pool, DelayPolicy.fixed(delayMs));
        app.start();
        System.out.println("agendamento listening on :" + app.port() + " (" + model + "-" + memory
                + ", create delay " + delayMs + " ms, pool " + pool + ")");
    }
}
