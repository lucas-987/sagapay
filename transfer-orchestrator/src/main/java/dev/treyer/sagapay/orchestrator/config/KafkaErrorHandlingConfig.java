package dev.treyer.sagapay.orchestrator.config;

import dev.treyer.sagapay.orchestrator.adapter.in.kafka.MalformedFraudResultException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.StringOrBytesSerializer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class KafkaErrorHandlingConfig implements DisposableBean {

    private final KafkaRetryProperties retry;

    private DefaultKafkaProducerFactory<Object, Object> deadLetterProducers;

    KafkaErrorHandlingConfig(KafkaRetryProperties retry) {
        this.retry = retry;
    }

    /**
     * A record the deserialiser rejected reaches the recoverer as raw bytes, a readable one as a
     * string: the dead-letter template accepts both. It is not a bean, so that Boot keeps building
     * its own producer for the outbox; its settings come from that producer, which knows the broker
     * address the application really connects to.
     */
    @Bean
    DefaultErrorHandler fraudResultsErrorHandler(ProducerFactory<?, ?> producerFactory) {
        deadLetterProducers = new DefaultKafkaProducerFactory<>(
                producerFactory.getConfigurationProperties(),
                new StringOrBytesSerializer(),
                new StringOrBytesSerializer());
        // Named <topic>.DLT, as the topic is created; Spring's default name is <topic>-dlt. It has
        // fewer partitions than the topic it shadows: -1 lets the producer pick one.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                new KafkaTemplate<>(deadLetterProducers),
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));
        DefaultErrorHandler handler =
                new DefaultErrorHandler(recoverer, new FixedBackOff(retry.intervalMs(), retry.attempts()));
        handler.addNotRetryableExceptions(MalformedFraudResultException.class);
        return handler;
    }

    @Override
    public void destroy() {
        if (deadLetterProducers != null) {
            deadLetterProducers.destroy();
        }
    }
}
