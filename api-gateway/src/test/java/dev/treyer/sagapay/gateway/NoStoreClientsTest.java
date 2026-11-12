package dev.treyer.sagapay.gateway;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The gateway holds no state and consumes no events: neither client is on its classpath. */
class NoStoreClientsTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "org.springframework.data.redis.core.ReactiveRedisTemplate",
                "io.lettuce.core.RedisClient",
                "org.apache.kafka.clients.consumer.KafkaConsumer",
                "org.springframework.kafka.core.KafkaTemplate"
            })
    void noRedisAndNoKafkaClientIsOnTheClasspath(String className) {
        assertThatThrownBy(() -> Class.forName(className)).isInstanceOf(ClassNotFoundException.class);
    }
}
