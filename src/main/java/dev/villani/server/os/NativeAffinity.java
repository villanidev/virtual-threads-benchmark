package dev.villani.server.os;

import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

/**
 * sched_setaffinity/sched_getaffinity through the Native Image SDK {@code @CFunction} (a direct C call linked
 * statically, works in a static musl image). Only loaded inside a native image, see {@link CpuAffinity}.
 */
final class NativeAffinity {

    private static final int MASK_BYTES = 128;

    private NativeAffinity() {
    }

    @CFunction("sched_setaffinity")
    private static native int sched_setaffinity(int pid, long cpusetsize, Pointer mask);

    @CFunction("sched_getaffinity")
    private static native int sched_getaffinity(int pid, long cpusetsize, Pointer mask);

    static boolean supported() {
        return true;
    }

    static void pin(int cpu) {
        final Pointer mask = UnmanagedMemory.calloc(WordFactory.unsigned(MASK_BYTES));
        try {
            mask.writeByte(cpu >> 3, (byte) (1 << (cpu & 7)));
            final int rc = sched_setaffinity(0, MASK_BYTES, mask);
            if (rc != 0) {
                throw new IllegalStateException("sched_setaffinity failed for cpu " + cpu + " (rc=" + rc + ")");
            }
        } finally {
            UnmanagedMemory.free(mask);
        }
    }

    static long currentMask() {
        final Pointer mask = UnmanagedMemory.calloc(WordFactory.unsigned(MASK_BYTES));
        try {
            final int rc = sched_getaffinity(0, MASK_BYTES, mask);
            if (rc != 0) {
                throw new IllegalStateException("sched_getaffinity failed (rc=" + rc + ")");
            }
            return mask.readLong(0);
        } finally {
            UnmanagedMemory.free(mask);
        }
    }
}
