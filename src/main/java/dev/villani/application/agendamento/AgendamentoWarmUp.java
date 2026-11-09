package dev.villani.application.agendamento;

import dev.villani.server.http.DelayPolicy;
import dev.villani.server.net.Transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end boot warm-up: starts a throwaway {@link AgendamentoServer} on a loopback ephemeral port (own
 * table, pool and timer wheel, so no fake data reaches the real instance), drives it with synthetic socket
 * clients through the full path (accept, parse, route, JSON, deferred create via the timer wheel, list, cancel,
 * pipelining, connection churn), then closes it. The JIT compiles per method, so the real instance
 * starts with the hot classes already compiled. It allocates freely: it is boot-time work only.
 */
public final class AgendamentoWarmUp {

    private static final int CLIENTS = 16;
    private static final int WARM_DELAY_MS = 1;
    private static final long BASE = 1_767_225_600L; // 2026-01-01T00:00:00Z
    private static final int RECONNECT_EVERY = 256;
    private static final int PIPELINE_EVERY = 16;

    private AgendamentoWarmUp() {
    }

    /** One cycle = create + list + cancel (plus occasional pipelining and reconnects). */
    static void run(int cycles) throws IOException, InterruptedException {
        try (AgendamentoServer warm = new AgendamentoServer("127.0.0.1", 0, CLIENTS * 2,
                DelayPolicy.fixed(WARM_DELAY_MS), () -> System.nanoTime() / 1_000_000, Transport.fromProperty())) {
            final Thread loop = new Thread(() -> {
                try {
                    warm.run();
                } catch (IOException ignored) {
                    // closed below
                }
            }, "warm-up-loop");
            loop.start();
            drive(warm.port(), cycles, true);
            warm.stop();
            loop.join(2_000);
        }
    }

    /**
     * Drives any server speaking the Agendamento API on {@code port} with {@code cycles} synthetic cycles from
     * {@link #CLIENTS} socket clients. {@code pipelining} must be off for servers that do not support it.
     */
    public static void drive(int port, int cycles, boolean pipelining) throws InterruptedException {
        final AtomicInteger next = new AtomicInteger();
        final Thread[] clients = new Thread[CLIENTS];
        for (int c = 0; c < CLIENTS; c++) {
            clients[c] = new Thread(() -> client(port, cycles, next, pipelining), "warm-up-client-" + c);
            clients[c].start();
        }
        for (Thread t : clients) {
            t.join();
        }
    }

    private static void client(int port, int cycles, AtomicInteger next, boolean pipelining) {
        try {
            Socket s = new Socket("127.0.0.1", port);
            s.setTcpNoDelay(true);
            int n;
            int mine = 0;
            while ((n = next.getAndIncrement()) < cycles) {
                if (++mine % RECONNECT_EVERY == 0) {
                    s.close();
                    s = new Socket("127.0.0.1", port);
                    s.setTcpNoDelay(true);
                }
                final OutputStream out = s.getOutputStream();
                final InputStream in = new java.io.BufferedInputStream(s.getInputStream());
                final long day = BASE + (n / 1000) * 86_400L;
                if (pipelining && n % PIPELINE_EVERY == 0) {
                    out.write((create(n, day) + list(day)).getBytes(StandardCharsets.US_ASCII));
                    readResponse(in);
                    readResponse(in);
                    continue;
                }
                out.write(create(n, day).getBytes(StandardCharsets.US_ASCII));
                final String created = readResponse(in);
                out.write(list(day).getBytes(StandardCharsets.US_ASCII));
                readResponse(in);
                final int id = idOf(created);
                if (id > 0) {
                    out.write(("PUT /api/agendamentos/" + id + "/cancelar HTTP/1.1\r\nHost: w\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    readResponse(in);
                }
            }
            s.close();
        } catch (IOException e) {
            next.set(cycles);
        }
    }

    private static String create(int n, long day) {
        final String json = "{\"nome\":\"Warm " + n % 100 + "\",\"servicoId\":" + (1 + n % 1000)
                + ",\"dataHora\":" + (day + 9 * 3600) + "}";
        return "POST /api/agendamentos HTTP/1.1\r\nHost: w\r\nContent-Type: application/json\r\nContent-Length: "
                + json.length() + "\r\n\r\n" + json;
    }

    private static String list(long day) {
        return "GET /api/agendamentos?data=" + java.time.LocalDate.ofEpochDay(day / 86_400L)
                + " HTTP/1.1\r\nHost: w\r\n\r\n";
    }

    private static int idOf(String response) {
        final int i = response.indexOf("{\"id\":");
        if (i < 0) {
            return -1;
        }
        int v = 0;
        for (int j = i + 6; j < response.length() && Character.isDigit(response.charAt(j)); j++) {
            v = v * 10 + (response.charAt(j) - '0');
        }
        return v;
    }

    /** Reads exactly one response (head + Content-Length body) so pipelined responses are never swallowed. */
    private static String readResponse(InputStream in) throws IOException {
        final StringBuilder head = new StringBuilder(128);
        while (head.length() < 4 || !head.substring(head.length() - 4).equals("\r\n\r\n")) {
            final int b = in.read();
            if (b < 0) {
                throw new IOException("closed");
            }
            head.append((char) b);
        }
        final String lower = head.toString().toLowerCase();
        final int k = lower.indexOf("content-length: ");
        final int bodyLength = k < 0 ? 0 : Integer.parseInt(lower.substring(k + 16, lower.indexOf("\r\n", k)));
        return head + new String(in.readNBytes(bodyLength), StandardCharsets.US_ASCII);
    }
}
