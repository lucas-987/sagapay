package dev.treyer.sagapay.orchestrator;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// Public: reused by tests in sub-packages.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // The JVM image: the GraalVM native one ("apache/kafka-native") crashes at startup now and then
    // (SIGSEGV in the broker process), which fails whichever context starts it.
    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
    }
}
