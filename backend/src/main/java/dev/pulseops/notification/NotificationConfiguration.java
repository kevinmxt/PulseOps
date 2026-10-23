package dev.pulseops.notification;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TelegramProperties.class, NotificationProperties.class})
public class NotificationConfiguration {

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
