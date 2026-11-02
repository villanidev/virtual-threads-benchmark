package dev.villani.server.http;

/** Application entry point for one routed request. Implementations must not allocate on the hot path. */
@FunctionalInterface
public interface Handler {

    void handle(HttpRequest request, HttpResponse response);
}
