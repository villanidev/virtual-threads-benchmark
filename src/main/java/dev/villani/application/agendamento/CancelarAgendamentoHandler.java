package dev.villani.application.agendamento;

import dev.villani.server.http.Handler;
import dev.villani.server.http.HttpRequest;
import dev.villani.server.http.HttpResponse;

/** PUT /api/agendamentos/{id}/cancelar -> 204, 404 when unknown, 409 when already cancelled. */
public final class CancelarAgendamentoHandler implements Handler {

    private final AgendamentoTable table;

    public CancelarAgendamentoHandler(AgendamentoTable table) {
        this.table = table;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) {
        final int result = table.cancel(request.pathParam());
        if (result == AgendamentoTable.OK) {
            response.status(204);
        } else if (result == AgendamentoTable.NOT_FOUND) {
            response.status(404);
        } else {
            response.status(409);
        }
    }
}
