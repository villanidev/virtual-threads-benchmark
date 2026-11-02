package dev.villani.server.http;

import dev.villani.server.memory.ByteRing;

/**
 * Glues parser, router and handlers for one connection slot. Handlers are registered once at
 * startup under the ids used by the {@link Router}.
 */
public final class Dispatcher {

    /**
     * Gerencia e junta parser, router e handlers. process(slot, ring, response) devolve NEED_MORE ou RESPONSE_READY.
     * Responde 404 e 405 pelo router.
     * Erros do parser viram a resposta com status de erro e keep-alive desligado, para fechar a conexão.
     * Consome a requisição do ring ao terminar.
     */

    public static final int NEED_MORE = 0;
    /** The request was consumed and the response holder is filled (also for errors, 404 and 405). */
    public static final int RESPONSE_READY = 1;

    private final HttpRequestParser parser;
    private final Router router;
    private final Handler[] handlers;
    private final HttpRequest request = new HttpRequest();

    public Dispatcher(HttpRequestParser parser, Router router, int maxHandlers) {
        this.parser = parser;
        this.router = router;
        this.handlers = new Handler[maxHandlers];
    }

    public Dispatcher register(int handlerId, Handler handler) {
        handlers[handlerId] = handler;
        return this;
    }

    public int process(int slot, ByteRing ring, HttpResponse response) {
        final int state = parser.parse(slot, ring);
        if (state == HttpRequestParser.NEED_MORE) {
            return NEED_MORE;
        }
        response.reset();
        if (state == HttpRequestParser.ERROR) {
            // the stream is no longer trustworthy: answer and close
            response.status(parser.errorStatus(slot)).keepAlive(false);
            return RESPONSE_READY;
        }
        final int id = router.match(parser.method(slot), ring, parser.pathStart(slot), parser.pathLength(slot));
        if (id == Router.NOT_FOUND) {
            response.status(404);
        } else if (id == Router.METHOD_NOT_ALLOWED) {
            response.status(405);
        } else {
            request.bind(parser, ring, slot, router.pathParam());
            handlers[id].handle(request, response);
        }
        response.keepAlive(response.keepAlive() && parser.keepAlive(slot));
        parser.consume(slot, ring);
        return RESPONSE_READY;
    }
}
