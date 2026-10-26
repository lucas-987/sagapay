package dev.treyer.sagapay.fraud.config;

import dev.treyer.sagapay.fraud.adapter.in.kafka.MalformedTransferEventException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.support.serializer.StringOrBytesSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
class KafkaErrorHandlingConfig {

    private static final long RETRY_INTERVAL_MS = 1000;
    private static final long RETRIES = 3;

    /**
     * An unreadable record reaches the recoverer as raw bytes, a readable one as the decoded
     * message: each needs its own serializer. The templates are not beans, so that Boot keeps
     * building its own producer for the outbox; their settings come from that producer, which
     * knows the broker address the application really connects to.
     */
    @Bean
    DefaultErrorHandler transferEventsErrorHandler(ProducerFactory<?, ?> producerFactory) {
        Map<String, Object> producerProperties = producerFactory.getConfigurationProperties();
        KafkaTemplate<Object, Object> rawTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                producerProperties, new StringOrBytesSerializer(), new StringOrBytesSerializer()));
        KafkaTemplate<String, Object> jsonTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<
                String, Object>(producerProperties, new StringSerializer(), new JacksonJsonSerializer<Object>()));
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, rawTemplate);
        templates.put(Object.class, jsonTemplate);

        // Named <topic>.DLT, as the topic is created; Spring's default name is <topic>-dlt. It has
        // fewer partitions than the topic it shadows: -1 lets the producer pick one.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                templates, (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, RETRIES));
        handler.addNotRetryableExceptions(MalformedTransferEventException.class);
        return handler;
    }
}
