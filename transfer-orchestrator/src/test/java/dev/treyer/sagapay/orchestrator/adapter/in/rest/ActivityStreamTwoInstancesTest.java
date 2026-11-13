package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.TransferOrchestratorApplication;
import dev.treyer.sagapay.orchestrator.adapter.in.rest.ActivityStreamSupport.Event;
import dev.treyer.sagapay.orchestrator.security.TestJwtConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.http.HttpClient;
import java.util.List;
import java.util.UUID;

import static dev.treyer.sagapay.orchestrator.adapter.in.rest.ActivityStreamSupport.data;
import static dev.treyer.sagapay.orchestrator.adapter.in.rest.ActivityStreamSupport.publish;
import static org.assertj.core.api.Assertions.assertThat;

/** The test context is the first instance; a second context shares its broker and database. */
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActivityStreamTwoInstancesTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private KafkaContainer kafkaContainer;

    @Autowired
    private PostgreSQLContainer postgres;

    private ConfigurableApplicationContext secondInstance() {
        return new SpringApplicationBuilder(TransferOrchestratorApplication.class, TestJwtConfiguration.class)
                .run(
                        "--server.port=0",
                        "--spring.kafka.bootstrap-servers=" + kafkaContainer.getBootstrapServers(),
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.flyway.url=" + postgres.getJdbcUrl(),
                        "--spring.flyway.user=" + postgres.getUsername(),
                        "--spring.flyway.password=" + postgres.getPassword(),
                        "--spring.flyway.placeholders.orchestratorAppPassword=unused",
                        "--saga.reprise.sweep-interval-ms=3600000",
                        "--saga.deadline.sweep-interval-ms=3600000");
    }

    private static int portOf(ConfigurableApplicationContext instance) {
        return Integer.parseInt(instance.getEnvironment().getProperty("local.server.port"));
    }

    @Test
    @Tag("M4-S25")
    @DisplayName("An event reaches a user whichever instance of the transfer service holds their connection")
    void anEventReachesAUserWhicheverInstanceHoldsTheirConnection() throws Exception {
        ActivityStreamSupport.awaitStreamListenerAssigned(registry);
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (ConfigurableApplicationContext second = secondInstance()) {
            ActivityStreamSupport.awaitStreamListenerAssigned(second.getBean(KafkaListenerEndpointRegistry.class));
            try (ActivityStreamSupport.Stream onFirst = new ActivityStreamSupport.Stream(http, port, sender);
                    ActivityStreamSupport.Stream onSecond =
                            new ActivityStreamSupport.Stream(http, portOf(second), recipient)) {
                publish(kafka, eventId, "TransferPosted.v1", transfer, sender, recipient, null);

                List<Event> expected =
                        List.of(new Event(eventId.toString(), "transfer.posted", data(transfer, sender, recipient)));
                assertThat(onFirst.take(1)).isEqualTo(expected);
                assertThat(onSecond.take(1)).isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("An instance started after an event was published does not push it")
    void anInstanceStartedAfterAnEventDoesNotPushIt() throws Exception {
        ActivityStreamSupport.awaitStreamListenerAssigned(registry);
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID before = UUID.randomUUID();
        UUID after = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        try (ActivityStreamSupport.Stream onFirst = new ActivityStreamSupport.Stream(http, port, sender)) {
            publish(kafka, before, "TransferPosted.v1", transfer, sender, recipient, null);
            assertThat(onFirst.take(1)).extracting(Event::id).containsExactly(before.toString());

            try (ConfigurableApplicationContext second = secondInstance()) {
                ActivityStreamSupport.awaitStreamListenerAssigned(second.getBean(KafkaListenerEndpointRegistry.class));
                try (ActivityStreamSupport.Stream onSecond =
                        new ActivityStreamSupport.Stream(http, portOf(second), sender)) {
                    publish(kafka, after, "TransferPosted.v1", transfer, sender, recipient, null);

                    assertThat(onSecond.take(1)).extracting(Event::id).containsExactly(after.toString());
                }
            }
        }
    }
}
