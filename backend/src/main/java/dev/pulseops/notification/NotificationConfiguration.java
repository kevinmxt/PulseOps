package dev.pulseops.notification;

import dev.pulseops.common.SchedulingSupport;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties({TelegramProperties.class, NotificationProperties.class})
public class NotificationConfiguration {

    @Bean
    ThreadPoolTaskScheduler notificationTickScheduler() {
        return SchedulingSupport.singleThreadScheduler("notification-tick-");
    }

    @Bean(destroyMethod = "close")
    CloseableHttpClient notificationHttpClient() {
        return HttpClientBuilder.create()
                .setConnectionManager(new PoolingHttpClientConnectionManager())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build();
    }

    @Bean
    Gauge notificationPendingGauge(NotificationDeliveryRepository deliveryRepository,
                                   MeterRegistry meterRegistry) {
        return Gauge.builder("notification.pending", deliveryRepository,
                        repository -> repository.countByStatus(NotificationDeliveryStatus.PENDING))
                .register(meterRegistry);
    }
}
