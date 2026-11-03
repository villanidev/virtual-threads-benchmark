package dev.villani.server.memory;

import java.nio.ByteBuffer;

/**
 * Allocates {@link Region}s with the backend selected by {@code -Dflat.memory=direct|ffm|native} (default direct; native = word API, native image only).
 * Everything is allocated once at construction time; nothing here is used on the request path.
 */
public final class Regions {

    public static final String PROPERTY = "flat.memory";

    private static final String MODE = parse(System.getProperty(PROPERTY, "direct"));

    private Regions() {
    }

    private static String parse(String mode) {
        return switch (mode) {
            case "direct", "ffm" -> mode;
            case "native" -> {
                if (!inNativeImage()) {
                    throw new IllegalArgumentException("-D" + PROPERTY + "=native only works inside a native image");
                }
                yield mode;
            }
            default -> throw new IllegalArgumentException("-D" + PROPERTY + " must be direct, ffm or native: " + mode);
        };
    }

    /** Same check as ImageInfo.inImageRuntimeCode(), without loading the SDK on a plain JVM. */
    public static boolean inNativeImage() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    public static String mode() {
        return MODE;
    }

    public static Region allocate(long bytes) {
        return allocate(bytes, MODE);
    }

    public static Region allocate(long bytes, boolean ffm) {
        return allocate(bytes, ffm ? "ffm" : "direct");
    }

    public static Region allocate(long bytes, String mode) {
        return switch (mode) {
            case "ffm" -> new FfmRegion(bytes);
            case "native" -> new NativeRegion(bytes);
            default -> new DirectRegion(bytes);
        };
    }

    /** A native-order ByteBuffer for I/O (NIO channels), backed by the selected region type. */
    public static ByteBuffer allocateBuffer(int bytes) {
        return allocate(bytes).asByteBuffer();
    }
}
