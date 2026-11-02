package dev.villani.server.http;

/**
 * Handler whose response may be completed later (e.g. after a simulated external call). In
 * {@link #handle} it keeps its own per-slot state and calls {@code response.deferMs(n)}; the server
 * then calls {@link #complete} once the delay elapsed, or {@link #abort} if the connection died first.
 */
public interface AsyncHandler extends Handler {

    void complete(int slot, HttpResponse response);

    void abort(int slot);
}
