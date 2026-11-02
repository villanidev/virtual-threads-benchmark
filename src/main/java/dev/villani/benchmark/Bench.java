package dev.villani.benchmark;

import dev.villani.benchmark.eventloop.DirectBufferEventLoop;
import dev.villani.benchmark.eventloop.FlatEventLoop;
import dev.villani.benchmark.eventloop.NioEventLoop;
import dev.villani.benchmark.executors.SemaphoreSlabBench;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/** Usage: Bench cached|fixed|vfixed|virtual|flat|flat-dbb|flat-nio N [poolSize] [sleepMs]. Prints a single result line (no println in hot path). */
public class Bench {

    public static void main(String[] args) throws Exception {
        final String model = args[0];
        final int n = Integer.parseInt(args[1]);
        final int poolSize = args.length > 2 ? Integer.parseInt(args[2]) : 500;
        sleepMs = args.length > 3 ? Long.parseLong(args[3]) : 1000;
        startPeakSampler();
        final LongAdder done = new LongAdder();
        final var tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long allocBefore = tmx.getCurrentThreadAllocatedBytes();

        final long t0 = System.nanoTime();
        switch (model) {
            case "cached" -> runPool(Executors.newCachedThreadPool(), n, done);
            case "fixed" -> runPool(Executors.newFixedThreadPool(poolSize), n, done);
            case "vfixed" -> runPool(Executors.newFixedThreadPool(poolSize, Thread.ofVirtual().factory()), n, done);
            case "virtual" -> runPool(Executors.newVirtualThreadPerTaskExecutor(), n, done);
            case "flat" -> done.add(FlatEventLoop.run(n, 1_000_000_000L));
            case "flat-dbb" -> done.add(DirectBufferEventLoop.run(n, 1_000_000_000L));
            case "flat-nio" -> done.add(NioEventLoop.run(n, 1_000_000_000L));
            case "vsem-heap" -> done.add(SemaphoreSlabBench.run(n, sleepMs, true));
            case "vsem-slab" -> done.add(SemaphoreSlabBench.run(n, sleepMs, false));
            default -> throw new IllegalArgumentException(model);
        }
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        final long mainAlloc = tmx.getCurrentThreadAllocatedBytes() - allocBefore;

        long gcCount = 0, gcMs = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += gc.getCollectionCount();
            gcMs += gc.getCollectionTime();
        }
        System.out.printf("model=%s tasks=%d completed=%d ms=%d mainThreadAllocKB=%d gcCount=%d gcMs=%d peakUsedMB=%d%n",
                model, n, done.sum(), ms, mainAlloc / 1024, gcCount, gcMs, peakUsed >> 20);
    }

    private static volatile long sleepMs = 1000;
    private static volatile long peakUsed;

    private static void startPeakSampler() {
        final Runtime rt = Runtime.getRuntime();
        final Thread t = new Thread(() -> {
            while (true) {
                final long used = rt.totalMemory() - rt.freeMemory();
                if (used > peakUsed) peakUsed = used;
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void runPool(ExecutorService ex, int n, LongAdder done) {
        try (ex) {
            for (int i = 0; i < n; i++) {
                ex.execute(() -> {
                    try {
                        Thread.sleep(Duration.ofMillis(sleepMs));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    done.increment();
                });
            }
        }
    }
}
