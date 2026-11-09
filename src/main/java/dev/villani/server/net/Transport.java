package dev.villani.server.net;

/** Which transport drives the connections: the JDK Selector or io_uring (selected with {@code -Dserver.transport}). */
public enum Transport {
    NIO,
    URING;

    public static final String PROPERTY = "server.transport";

    public static Transport fromProperty() {
        return valueOf(System.getProperty(PROPERTY, "nio").toUpperCase());
    }
}
