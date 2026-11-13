package dev.treyer.sagapay.orchestrator.config;

import dev.treyer.sagapay.orchestrator.application.service.ActivityStreamService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableConfigurationProperties(ActivityStreamProperties.class)
class ActivityStreamConfig implements DisposableBean {

    // Not a bean: an Executor bean would stop Boot from building its own application task executor.
    private final ExecutorService writers = newWriters();

    @Bean
    ActivityStreamService activityStreamService(ActivityStreamProperties properties) {
        return new ActivityStreamService(writers, properties.bufferSize());
    }

    /**
     * The shared consumer settings and error handling suit the fraud results; the stream needs neither
     * their dead-letter topic nor their retries: a record it cannot use is skipped.
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> activityStreamListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(0L, 0L)));
        return factory;
    }

    @Override
    public void destroy() {
        writers.shutdownNow();
    }

    private static ExecutorService newWriters() {
        AtomicInteger count = new AtomicInteger();
        return Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "activity-stream-writer-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
