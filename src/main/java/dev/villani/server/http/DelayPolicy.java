package dev.villani.server.http;

/** Source of the simulated external-call latency. Implementations must not allocate. */
@FunctionalInterface
public interface DelayPolicy {

    int nextDelayMs();

    static DelayPolicy fixed(int ms) {
        return () -> ms;
    }
}
