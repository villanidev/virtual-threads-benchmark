package dev.villani;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public class FixedThreadPool {
    public String executeTasks(final int NUMBER_OF_TASKS) {

        final int BLOCKING_CALL = 1;
        System.out.println("Number of tasks which executed using 'newFixedThreadPool(500)' " + NUMBER_OF_TASKS + " tasks each.");

        final int dezPct = NUMBER_OF_TASKS / 10;
        AtomicInteger peace = new AtomicInteger(1);

        long startTime = System.currentTimeMillis();

        try (var executor = Executors.newFixedThreadPool(500)) {

            IntStream.range(0, NUMBER_OF_TASKS).forEach(i -> {
                executor.submit(() -> {
                    // simulate a blocking call (e.g. I/O or db operation)
                    Thread.sleep(Duration.ofSeconds(BLOCKING_CALL));
                    if (i == dezPct*peace.get()) {
                        System.out.println();
                        System.out.println(">> "+peace.get() *10 + "%");
                        peace.getAndIncrement();
                    }
                    return i;
                });
            });

        }   catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println(">> 100%");

        long endTime = System.currentTimeMillis();

        var message = "For executing %06d tasks duration is: %06d ms".formatted(NUMBER_OF_TASKS,endTime - startTime);
        System.out.println(message);
        return message;
    }


    public static void main(String[] args) {
        var exe = new FixedThreadPool();
        var t1 = exe.test_1000_tasks();
        var t2 = exe.test_10_000_tasks();
        var t3 = exe.test_100_000_tasks();
        //var t4 = exe.test_1_000_000_tasks();
        System.out.println("Executors.newFixedThreadPool (Java 1.5)");
        System.out.println("  1_000 - "+t1);
        System.out.println(" 10_000 - "+t2);
        System.out.println("100_000 - "+t3);
        //System.out.println("1_000_000 - "+t4);
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
