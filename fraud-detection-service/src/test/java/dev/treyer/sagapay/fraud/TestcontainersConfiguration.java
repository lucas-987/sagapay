package dev.treyer.sagapay.fraud;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// Public: reused by tests in sub-packages.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
    }

    // spring.flyway.url is set in application.properties, so it wins over the service
    // connection: point Flyway at the container with its superuser, and fill the role
    // placeholders the role migration needs.
    @Bean
    DynamicPropertyRegistrar flywayOnTheContainer(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("spring.flyway.url", postgres::getJdbcUrl);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
            registry.add("spring.flyway.placeholders.fraudAppUsername", () -> "fraud_app");
            registry.add("spring.flyway.placeholders.fraudAppPassword", () -> "secret");
        };
    }
}
