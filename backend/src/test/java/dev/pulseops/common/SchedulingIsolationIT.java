package dev.pulseops.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ClassUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "pulseops.probe.scheduling-enabled=true",
        "pulseops.notification.scheduling-enabled=true",
        "pulseops.probe.tick-interval=1h",
        "pulseops.notification.dispatch-interval=1h"
})
@Testcontainers
class SchedulingIsolationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    ConfigurableApplicationContext context;

    @Test
    void everyScheduledMethodDeclaresAUniqueSingleThreadScheduler() {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        List<Scheduled> declarations = new ArrayList<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = beanFactory.getType(beanName);
            if (type == null) {
                continue;
            }
            Class<?> targetType = ClassUtils.getUserClass(type);
            Map<Method, Set<Scheduled>> scheduledMethods = MethodIntrospector.selectMethods(targetType,
                    (MethodIntrospector.MetadataLookup<Set<Scheduled>>) method ->
                            AnnotatedElementUtils.findMergedRepeatableAnnotations(method, Scheduled.class));
            scheduledMethods.values().forEach(declarations::addAll);
        }

        assertThat(declarations).hasSizeGreaterThanOrEqualTo(2);
        for (Scheduled declaration : declarations) {
            assertThat(declaration.scheduler())
                    .as("@Scheduled must declare a dedicated scheduler")
                    .isNotBlank();
        }

        List<String> schedulers = declarations.stream().map(Scheduled::scheduler).toList();
        assertThat(schedulers).doesNotHaveDuplicates();
        assertThat(schedulers).contains("probeTickScheduler", "notificationTickScheduler");
        for (String schedulerName : schedulers) {
            ThreadPoolTaskScheduler scheduler = context.getBean(schedulerName, ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getPoolSize()).isEqualTo(1);
        }
    }
}
