package dev.villani.application.agendamento;

import dev.villani.server.http.Handler;
import dev.villani.server.http.HttpRequest;
import dev.villani.server.http.HttpResponse;
import dev.villani.server.json.JsonWriter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * GET /api/agendamentos?data=YYYY-MM-DD -> 200 JSON array (newest first). The array is capped by
 * the response body capacity: items that do not fit are left out rather than allocating.
 */
public final class ListarAgendamentosHandler implements Handler {

    private static final byte[] DATA = "data".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ID = "{\"id\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SERVICO = ",\"servicoId\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DATA_HORA = ",\"dataHora\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS_CONFIRMADO = ",\"status\":\"CONFIRMADO\",\"nome\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STATUS_CANCELADO = ",\"status\":\"CANCELADO\",\"nome\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END_ITEM = "\"}".getBytes(StandardCharsets.US_ASCII);
    /** Upper bound of one serialised item plus separator and closing bracket. */
    private static final int MAX_ITEM_BYTES = 160;

    private final AgendamentoTable table;
    private final byte[] dateScratch = new byte[10];

    public ListarAgendamentosHandler(AgendamentoTable table) {
        this.table = table;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) {
        final int value = request.findQueryParam(DATA);
        if (value < 0 || request.queryValueLength() != 10) {
            response.status(400);
            return;
        }
        for (int i = 0; i < 10; i++) {
            dateScratch[i] = request.queryByte(value + i);
        }
        final long day = AgendamentoDias.parseIsoDate(dateScratch, 10);
        if (day == Long.MIN_VALUE) {
            response.status(400);
            return;
        }

        final ByteBuffer body = response.body();
        body.put((byte) '[');
        boolean first = true;
        for (int id = table.firstOfDay((int) day); id != 0 && body.remaining() >= MAX_ITEM_BYTES; id = table.nextOfDay(id)) {
            if (!first) {
                body.put((byte) ',');
            }
            first = false;
            JsonWriter.put(body, ID);
            JsonWriter.putLong(body, id);
            JsonWriter.put(body, SERVICO);
            JsonWriter.putLong(body, table.servico(id));
            JsonWriter.put(body, DATA_HORA);
            JsonWriter.putLong(body, table.dataHora(id));
            JsonWriter.put(body, table.status(id) == AgendamentoTable.CONFIRMADO ? STATUS_CONFIRMADO : STATUS_CANCELADO);
            table.copyNome(id, body);
            JsonWriter.put(body, END_ITEM);
        }
        body.put((byte) ']');
        response.status(200);
    }
}
