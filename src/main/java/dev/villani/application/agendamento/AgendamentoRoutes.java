package dev.villani.application.agendamento;

import dev.villani.server.http.DelayPolicy;
import dev.villani.server.http.Dispatcher;
import dev.villani.server.http.HttpRequestParser;
import dev.villani.server.http.Router;

/** Wires the appointment API: routes, handler ids and handlers. */
public final class AgendamentoRoutes {

    public static final int CRIAR = 0;
    public static final int LISTAR = 1;
    public static final int CANCELAR = 2;
    public static final int HANDLER_COUNT = 3;

    private AgendamentoRoutes() {
    }

    public static Router newRouter() {
        return new Router(HANDLER_COUNT, 4, 64)
                .route(HttpRequestParser.POST, "/api/agendamentos", CRIAR)
                .route(HttpRequestParser.GET, "/api/agendamentos", LISTAR)
                .route(HttpRequestParser.PUT, "/api/agendamentos/{id}/cancelar", CANCELAR);
    }

    public static void install(Dispatcher dispatcher, AgendamentoTable table, int maxInFlight) {
        install(dispatcher, table, maxInFlight, 1, null);
    }

    /** Create is answered after {@code createDelay} (simulated external call); null keeps it synchronous. */
    public static void install(Dispatcher dispatcher, AgendamentoTable table, int maxInFlight,
                               int maxConnections, DelayPolicy createDelay) {
        dispatcher.register(CRIAR, new CriarAgendamentoHandler(table, maxInFlight, maxConnections, createDelay))
                .register(LISTAR, new ListarAgendamentosHandler(table))
                .register(CANCELAR, new CancelarAgendamentoHandler(table));
    }
}
