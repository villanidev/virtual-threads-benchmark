package dev.villani;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * <p><b>Semaphore Integration:</b></p>
 * Wrapped the Thread.sleep blocking call in semaphore.acquire() and semaphore.release().
 * Even if you run 1,000,000 virtual threads, only 50 of them will execute the simulated database call at any split second.
 * The other 999,950 threads seamlessly yield their underlying CPU carrier threads while waiting in line
 * */
public class VirtualThreadPoolWithSemaphore {

    // Simulating a downstream resource bottleneck (e.g., a database connection pool maxed at 50)
    private static final int CONCURRENCY_LIMIT = 50;
    private final Semaphore semaphore = new Semaphore(CONCURRENCY_LIMIT);

    public String executeTasks(final int NUMBER_OF_TASKS) {

        final int BLOCKING_CALL = 1;
        System.out.println("Executing " + NUMBER_OF_TASKS + " tasks using Virtual Threads with a Semaphore limit of " + CONCURRENCY_LIMIT);

        long startTime = System.currentTimeMillis();
        final int dezPct = Math.max(1, NUMBER_OF_TASKS / 10); // Prevent divide-by-zero if tasks < 10
        AtomicInteger peace = new AtomicInteger(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            IntStream.range(0, NUMBER_OF_TASKS).forEach(i -> {
                executor.submit(() -> {

                    // Create a byte array within the thread, simulating data that came from the database.
                    byte[] inMemoryData = new byte[1024 * 10]; // 10 KB por thread

                    // 1. Acquire a permit before entering the resource-heavy section
                    semaphore.acquire();
                    try {
                        // Simulate a blocking call (e.g., database operation)
                        Thread.sleep(Duration.ofSeconds(BLOCKING_CALL));

                        // Use the variable so that the compiler doesn't optimize it away.
                        int size = inMemoryData.length;

                    } finally {
                        // 2. Always release the permit in a finally block
                        semaphore.release();
                    }

                    // Progress reporting logic (moved outside the lock to keep the critical section fast)
                    if (i == dezPct * peace.get()) {
                        System.out.println(">> " + peace.get() * 10 + "%");
                        peace.getAndIncrement();
                    }
                    return i;
                });
            });

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println(">> 100%");

        long endTime = System.currentTimeMillis();

        var message = "For executing %06d tasks duration is: %06d ms".formatted(NUMBER_OF_TASKS, endTime - startTime);
        System.out.println(message);
        return message;
    }

    public static void main(String[] args) {
        var exe = new VirtualThreadPoolWithSemaphore();

        // Heads up: With a concurrency limit of 50 and 1-second tasks,
        // running 10,000+ tasks sequentially through the semaphore will take quite a while locally!
        var t1 = exe.test_1000_tasks();
        var t2 = exe.test_10_000_tasks();

        System.out.println("\n--- FINAL RESULTS ---");
        System.out.println("Virtual Threads + Semaphore (" + CONCURRENCY_LIMIT + " limit)");
        System.out.println("  1_000 tasks - " + t1);
        System.out.println(" 10_000 tasks - " + t2);
    }

    public String test_1000_tasks() {
        return this.executeTasks(1000);
    }

    public String test_10_000_tasks() {
        return this.executeTasks(10_000);
    }

    public String test_100_000_tasks() {
        return this.executeTasks(100_000);
    }

    public String test_1_000_000_tasks() {
        return this.executeTasks(1_000_000);
    }
}
