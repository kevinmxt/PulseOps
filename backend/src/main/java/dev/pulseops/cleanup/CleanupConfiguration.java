package dev.pulseops.cleanup;

import dev.pulseops.common.SchedulingSupport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(CleanupProperties.class)
public class CleanupConfiguration {

    @Bean
    ThreadPoolTaskScheduler cleanupTickScheduler() {
        return SchedulingSupport.singleThreadScheduler("cleanup-tick-");
    }
}
