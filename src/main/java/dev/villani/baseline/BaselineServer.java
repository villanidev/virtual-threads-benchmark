package dev.villani.baseline;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.villani.application.agendamento.AgendamentoServer;
import dev.villani.baseline.heap.HeapController;
import dev.villani.baseline.heap.HeapStore;
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
        if (memory == Memory.OFFHEAP) {
            throw new UnsupportedOperationException("off-heap variant not implemented yet");
        }
        final HttpHandler handler = new HeapController(
                new HeapStore(AgendamentoServer.RECORDS, AgendamentoServer.DAYS), delay);
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

    public static void main(String[] args) throws IOException {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        final int delayMs = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        final ConcurrencyModel model = ConcurrencyModel.valueOf((args.length > 2 ? args[2] : "VIRTUAL").toUpperCase());
        final Memory memory = Memory.valueOf((args.length > 3 ? args[3] : "HEAP").toUpperCase());
        final int pool = args.length > 4 ? Integer.parseInt(args[4]) : 200;
        final BaselineServer app = new BaselineServer("0.0.0.0", port, model, memory, pool, DelayPolicy.fixed(delayMs));
        app.start();
        System.out.println("agendamento listening on :" + app.port() + " (" + model + "-" + memory
                + ", create delay " + delayMs + " ms, pool " + pool + ")");
    }
}
