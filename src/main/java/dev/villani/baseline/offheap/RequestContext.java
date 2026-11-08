package dev.villani.baseline.offheap;

import dev.villani.application.agendamento.AgendamentoLimits;
import dev.villani.application.agendamento.AgendamentoTable;
import dev.villani.server.json.JsonBinder;
import dev.villani.server.json.JsonRecord;
import dev.villani.server.json.JsonSchema;
import dev.villani.server.memory.ByteRing;
import dev.villani.server.memory.Regions;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Everything one in-flight request needs, allocated once: request bytes (heap staging + off-heap ring),
 * JSON binder and record, response body (off-heap + heap staging for the stream), scratch buffers.
 * Contexts are lent from a bounded pool instead of a ThreadLocal, so virtual threads do not each get their own.
 */
final class RequestContext {

    static final int IN_BYTES = AgendamentoLimits.READ_BYTES;
    static final int OUT_BYTES = AgendamentoLimits.RESPONSE_BODY_BYTES;

    final byte[] inArray = new byte[IN_BYTES + 1];
    final ByteBuffer inStaging = ByteBuffer.wrap(inArray);
    final ByteRing ring = new ByteRing(Regions.allocateBuffer(IN_BYTES));
    final JsonBinder binder;
    final JsonRecord record;
    final ByteBuffer body = Regions.allocateBuffer(OUT_BYTES);
    final byte[] outArray = new byte[OUT_BYTES];
    final ByteBuffer nome = Regions.allocateBuffer(AgendamentoTable.MAX_NOME);
    final byte[] date = new byte[10];

    private RequestContext(JsonSchema schema) {
        this.binder = new JsonBinder(schema, 2);
        this.record = schema.newRecord();
    }

    void reset() {
        ring.clear();
        record.clear();
        body.clear();
        nome.clear();
    }

    /** Bounded blocking pool: no allocation per borrow, back-pressure when exhausted. */
    static final class Pool {
        private final ArrayBlockingQueue<RequestContext> free;

        Pool(JsonSchema schema, int size) {
            free = new ArrayBlockingQueue<>(size);
            for (int i = 0; i < size; i++) {
                free.add(new RequestContext(schema));
            }
        }

        RequestContext acquire() throws InterruptedException {
            final RequestContext c = free.take();
            c.reset();
            return c;
        }

        void release(RequestContext c) {
            free.add(c);
        }
    }
}
