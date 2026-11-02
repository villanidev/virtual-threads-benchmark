package dev.villani.baseline;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Thread models of the comparison (the single event loop is the flat server itself). */
public enum ConcurrencyModel {
    VIRTUAL,
    FIXED,
    CACHED;

    public ExecutorService newExecutor(int poolSize) {
        return switch (this) {
            case VIRTUAL -> Executors.newVirtualThreadPerTaskExecutor();
            case FIXED -> Executors.newFixedThreadPool(poolSize);
            case CACHED -> Executors.newCachedThreadPool();
        };
    }
}
