package dev.villani.server.os;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Pins the calling thread to one CPU with {@code sched_setaffinity(2)} through the FFM API (Linux only, no JNI, no
 * extra library). Unlike {@code taskset} on the whole process, it pins just the thread that calls it, so the GC, JIT
 * and JFR threads stay free on the other CPUs. Call it from the event-loop thread. Boot-time only.
 */
public final class CpuAffinity {

    public static final String PROPERTY = "flat.pin";

    private static final int MASK_BYTES = 128; // cpu_set_t: 1024 bits
    private static final MethodHandle SET;
    private static final MethodHandle GET;

    static {
        MethodHandle set = null;
        MethodHandle get = null;
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            final Linker linker = Linker.nativeLinker();
            final var lookup = linker.defaultLookup();
            final FunctionDescriptor fd = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS);
            set = lookup.find("sched_setaffinity").map(a -> linker.downcallHandle(a, fd)).orElse(null);
            get = lookup.find("sched_getaffinity").map(a -> linker.downcallHandle(a, fd)).orElse(null);
        }
        SET = set;
        GET = get;
    }

    private CpuAffinity() {
    }

    public static boolean supported() {
        return SET != null && GET != null;
    }

    /** Pins the calling (platform) thread to {@code cpu}. */
    public static void pinCurrentThread(int cpu) {
        if (!supported()) {
            throw new UnsupportedOperationException("sched_setaffinity is only available on Linux");
        }
        if (cpu < 0 || cpu >= MASK_BYTES * 8) {
            throw new IllegalArgumentException("cpu out of range: " + cpu);
        }
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment mask = arena.allocate(MASK_BYTES);
            mask.set(ValueLayout.JAVA_BYTE, cpu >> 3, (byte) (1 << (cpu & 7)));
            final int rc = (int) SET.invokeExact(0, (long) MASK_BYTES, mask); // pid 0 = calling thread
            if (rc != 0) {
                throw new IllegalStateException("sched_setaffinity failed for cpu " + cpu + " (rc=" + rc + ")");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The CPUs the calling thread may run on, as a bit mask of CPUs 0-63. */
    public static long currentMask() {
        if (!supported()) {
            throw new UnsupportedOperationException("sched_getaffinity is only available on Linux");
        }
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment mask = arena.allocate(MASK_BYTES);
            final int rc = (int) GET.invokeExact(0, (long) MASK_BYTES, mask);
            if (rc != 0) {
                throw new IllegalStateException("sched_getaffinity failed (rc=" + rc + ")");
            }
            return mask.get(ValueLayout.JAVA_LONG, 0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Pins the calling thread if {@code -Dflat.pin=<cpu>} is set; returns the CPU or -1. */
    public static int pinFromProperty() {
        final String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            return -1;
        }
        final int cpu = Integer.parseInt(value.trim());
        pinCurrentThread(cpu);
        return cpu;
    }
}
