package dev.pulseops.heartbeat;

import dev.pulseops.common.SchedulingSupport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(HeartbeatProperties.class)
public class HeartbeatConfiguration {

    @Bean
    ThreadPoolTaskScheduler heartbeatTickScheduler() {
        return SchedulingSupport.singleThreadScheduler("heartbeat-tick-");
    }
}
