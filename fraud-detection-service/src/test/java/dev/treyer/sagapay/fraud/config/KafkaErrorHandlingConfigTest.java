package dev.treyer.sagapay.fraud.config;

import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaErrorHandlingConfigTest {

    private final List<String> destroyed = new ArrayList<>();

    private final KafkaErrorHandlingConfig config = new KafkaErrorHandlingConfig() {
        @Override
        <K, V> DefaultKafkaProducerFactory<K, V> newProducerFactory(
                Map<String, Object> properties, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
            String name = valueSerializer.getClass().getSimpleName();
            return new DefaultKafkaProducerFactory<>(properties, keySerializer, valueSerializer) {
                @Override
                public void destroy() {
                    destroyed.add(name);
                }
            };
        }
    };

    @Test
    void bothDeadLetterProducersAreClosedWhenTheContextCloses() {
        config.transferEventsErrorHandler(
                new DefaultKafkaProducerFactory<>(Map.of("bootstrap.servers", "localhost:1")));
        assertThat(destroyed).isEmpty();

        config.destroy();

        assertThat(destroyed).containsExactly("StringOrBytesSerializer", "JacksonJsonSerializer");
    }
}
