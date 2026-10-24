package dev.pulseops.common;

import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

public final class SchedulingSupport {

    private SchedulingSupport() {
    }

    public static ThreadPoolTaskScheduler singleThreadScheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
