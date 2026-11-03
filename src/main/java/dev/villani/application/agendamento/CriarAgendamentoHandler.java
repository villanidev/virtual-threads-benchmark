package dev.villani.application.agendamento;

import dev.villani.server.http.AsyncHandler;
import dev.villani.server.http.DelayPolicy;
import dev.villani.server.http.HttpRequest;
import dev.villani.server.http.HttpResponse;
import dev.villani.server.json.JsonBinder;
import dev.villani.server.json.JsonRecord;
import dev.villani.server.json.JsonRecordPool;
import dev.villani.server.json.JsonSchema;
import dev.villani.server.json.JsonWriter;
import dev.villani.server.memory.Regions;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** POST /api/agendamentos {"nome":"..","servicoId":N,"dataHora":epochSeconds} -> 201 {"id":N,"status":"CONFIRMADO"}.
 * With a {@link DelayPolicy} the request is validated at once but committed (conflict check included)
 * after the simulated external-call delay, holding its pooled record meanwhile. */
public final class CriarAgendamentoHandler implements AsyncHandler {

    private static final byte[] ID_OPEN = "{\"id\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ID_CLOSE = ",\"status\":\"CONFIRMADO\"}".getBytes(StandardCharsets.US_ASCII);

    private final AgendamentoTable table;
    private final JsonRecordPool pool;
    private final JsonBinder binder;
    private final DelayPolicy delay;
    private final JsonRecord[] inFlight;
    private final ByteBuffer nomeScratch = Regions.allocateBuffer(AgendamentoTable.MAX_NOME);
    private final int fNome;
    private final int fServico;
    private final int fDataHora;

    /** Synchronous variant. */
    public CriarAgendamentoHandler(AgendamentoTable table, int maxInFlight) {
        this(table, maxInFlight, 1, null);
    }

    /** {@code delay == null} keeps the handler synchronous. */
    public CriarAgendamentoHandler(AgendamentoTable table, int maxInFlight, int maxConnections, DelayPolicy delay) {
        this.table = table;
        this.delay = delay;
        this.inFlight = new JsonRecord[maxConnections];
        final JsonSchema schema = new JsonSchema(3);
        fNome = schema.field("nome", JsonSchema.STRING, AgendamentoTable.MAX_NOME);
        fServico = schema.field("servicoId", JsonSchema.INT);
        fDataHora = schema.field("dataHora", JsonSchema.INT);
        this.pool = new JsonRecordPool(schema, maxInFlight);
        this.binder = new JsonBinder(schema, 2);
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) {
        final JsonRecord rec = pool.acquire();
        if (rec == null) {
            response.status(503);
            return;
        }
        if (!bindAndValidate(request, rec)) {
            pool.release(rec);
            response.status(400);
            return;
        }
        final int delayMs = delay == null ? 0 : delay.nextDelayMs();
        if (delayMs > 0) {
            inFlight[request.slot()] = rec;
            response.deferMs(delayMs);
            return;
        }
        try {
            commit(rec, response);
        } finally {
            pool.release(rec);
        }
    }

    @Override
    public void complete(int slot, HttpResponse response) {
        final JsonRecord rec = inFlight[slot];
        inFlight[slot] = null;
        try {
            commit(rec, response);
        } finally {
            pool.release(rec);
        }
    }

    @Override
    public void abort(int slot) {
        final JsonRecord rec = inFlight[slot];
        inFlight[slot] = null;
        if (rec != null) {
            pool.release(rec);
        }
    }

    private boolean bindAndValidate(HttpRequest request, JsonRecord rec) {
        final int code = binder.bind(request.ring(), request.bodyOffset(), request.bodyLength(), rec);
        return code == JsonBinder.OK && rec.has(fNome) && rec.has(fServico) && rec.has(fDataHora)
                && AgendamentoRules.isValid(rec.stringLength(fNome), rec.getInt(fServico), rec.getInt(fDataHora));
    }

    private void commit(JsonRecord rec, HttpResponse response) {
        nomeScratch.clear();
        rec.copyStringTo(fNome, nomeScratch);
        nomeScratch.flip();
        final int id = table.create((int) rec.getInt(fServico), rec.getInt(fDataHora), nomeScratch);
        if (id == AgendamentoTable.CONFLICT) {
            response.status(409);
        } else if (id < 0) {
            response.status(503);
        } else {
            final ByteBuffer body = response.body();
            JsonWriter.put(body, ID_OPEN);
            JsonWriter.putLong(body, id);
            JsonWriter.put(body, ID_CLOSE);
            response.status(201);
        }
    }
}
