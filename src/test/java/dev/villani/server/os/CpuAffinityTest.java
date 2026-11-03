package dev.villani.server.os;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CpuAffinity")
class CpuAffinityTest {

    @Test
    @DisplayName("given a thread pinned to an allowed CPU, when reading its affinity, then only that CPU is set and other threads are untouched")
    void givenPinnedThread_whenReadingAffinity_thenOnlyThatCpu() throws InterruptedException {
        // given
        Assumptions.assumeTrue(CpuAffinity.supported(), "Linux only");
        final long allowed = CpuAffinity.currentMask();
        final int cpu = Long.numberOfTrailingZeros(allowed);
        final AtomicLong pinnedMask = new AtomicLong(-1);
        final AtomicLong failure = new AtomicLong(0);

        // when: pin a dedicated platform thread, never the test runner thread
        final Thread worker = Thread.ofPlatform().start(() -> {
            try {
                CpuAffinity.pinCurrentThread(cpu);
                pinnedMask.set(CpuAffinity.currentMask());
            } catch (RuntimeException e) {
                failure.set(1);
            }
        });
        worker.join();

        // then
        assertEquals(0, failure.get(), "pinning should not fail");
        assertEquals(1L << cpu, pinnedMask.get());
        assertEquals(allowed, CpuAffinity.currentMask(), "the calling thread keeps its affinity");
    }

    @Test
    @DisplayName("given a CPU outside the mask range, when pinning, then it is rejected")
    void givenCpuOutOfRange_whenPinning_thenRejected() {
        // given
        Assumptions.assumeTrue(CpuAffinity.supported(), "Linux only");

        // when / then
        assertThrows(IllegalArgumentException.class, () -> CpuAffinity.pinCurrentThread(5000));
        assertThrows(IllegalArgumentException.class, () -> CpuAffinity.pinCurrentThread(-1));
    }
}
