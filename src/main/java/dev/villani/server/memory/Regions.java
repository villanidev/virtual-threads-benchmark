package dev.villani.server.memory;

import java.nio.ByteBuffer;

/**
 * Allocates {@link Region}s with the backend selected by {@code -Dflat.memory=direct|ffm} (default direct).
 * Everything is allocated once at construction time; nothing here is used on the request path.
 */
public final class Regions {

    public static final String PROPERTY = "flat.memory";

    private static final boolean FFM = parse(System.getProperty(PROPERTY, "direct"));

    private Regions() {
    }

    private static boolean parse(String mode) {
        return switch (mode) {
            case "direct" -> false;
            case "ffm" -> true;
            default -> throw new IllegalArgumentException("-D" + PROPERTY + " must be direct or ffm: " + mode);
        };
    }

    public static String mode() {
        return FFM ? "ffm" : "direct";
    }

    public static Region allocate(long bytes) {
        return FFM ? new FfmRegion(bytes) : new DirectRegion(bytes);
    }

    public static Region allocate(long bytes, boolean ffm) {
        return ffm ? new FfmRegion(bytes) : new DirectRegion(bytes);
    }

    /** A native-order ByteBuffer for I/O (NIO channels), backed by the selected region type. */
    public static ByteBuffer allocateBuffer(int bytes) {
        return allocate(bytes).asByteBuffer();
    }
}
