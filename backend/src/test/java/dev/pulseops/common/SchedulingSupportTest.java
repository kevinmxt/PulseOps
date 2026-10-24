package dev.pulseops.common;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingSupportTest {

    @Test
    void runsTasksOnOneNamedThread() throws InterruptedException {
        ThreadPoolTaskScheduler scheduler = SchedulingSupport.singleThreadScheduler("test-tick-");
        scheduler.initialize();
        try {
            Set<String> threadNames = ConcurrentHashMap.newKeySet();
            CountDownLatch completed = new CountDownLatch(4);
            for (int i = 0; i < 4; i++) {
                scheduler.execute(() -> {
                    threadNames.add(Thread.currentThread().getName());
                    completed.countDown();
                });
            }

            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threadNames).hasSize(1);
            assertThat(threadNames.iterator().next()).startsWith("test-tick-");
        } finally {
            scheduler.shutdown();
        }
    }
}
