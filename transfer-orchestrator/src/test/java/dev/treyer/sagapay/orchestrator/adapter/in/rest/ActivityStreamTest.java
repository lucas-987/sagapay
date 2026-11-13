package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import dev.treyer.sagapay.orchestrator.adapter.in.rest.ActivityStreamSupport.Event;
import dev.treyer.sagapay.orchestrator.config.ActivityStreamProperties;
import dev.treyer.sagapay.orchestrator.security.TestJwtConfiguration;
import dev.treyer.sagapay.orchestrator.security.TestTokens;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static dev.treyer.sagapay.orchestrator.adapter.in.rest.ActivityStreamSupport.data;
import static dev.treyer.sagapay.orchestrator.adapter.in.rest.ActivityStreamSupport.dataWithCause;
import static dev.treyer.sagapay.orchestrator.adapter.in.rest.ActivityStreamSupport.publish;
import static org.assertj.core.api.Assertions.assertThat;

/** Real port, Kafka and test-key tokens; each test has users of its own. */
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActivityStreamTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private ActivityStreamProperties properties;

    private enum Party {
        SENDER,
        RECIPIENT
    }

    @BeforeEach
    void listenerIsAssigned() {
        ActivityStreamSupport.awaitStreamListenerAssigned(registry);
    }

    private ActivityStreamSupport.Stream open(UUID user) throws Exception {
        return new ActivityStreamSupport.Stream(http, port, user);
    }

    private static UUID of(Party party, UUID sender, UUID recipient) {
        return party == Party.SENDER ? sender : recipient;
    }

    private static Event event(UUID id, String name, Map<String, Object> data) {
        return new Event(id.toString(), name, data);
    }

    @Test
    @DisplayName("A stream lasts 30 minutes and a connection holds 64 unwritten events unless configured otherwise")
    void theStreamSettingsHaveDefaults() {
        assertThat(properties).isEqualTo(new ActivityStreamProperties(1_800_000L, 64));
    }

    @Test
    @DisplayName("An open stream answers 200 as an event stream")
    void anOpenStreamAnswersAsAnEventStream() throws Exception {
        try (ActivityStreamSupport.Stream stream = open(UUID.randomUUID())) {
            assertThat(stream.status()).isEqualTo(200);
            assertThat(stream.contentType()).isEqualTo("text/event-stream");
        }
    }

    @ParameterizedTest
    @EnumSource(Party.class)
    @Tag("M4-S18")
    @DisplayName("Both parties see a transfer posted, with no balance on the stream")
    void bothPartiesSeeATransferPosted(Party party) throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (ActivityStreamSupport.Stream stream = open(of(party, sender, recipient))) {
            publish(kafka, eventId, "TransferPosted.v1", transfer, sender, recipient, null);

            assertThat(stream.take(1))
                    .containsExactly(event(eventId, "transfer.posted", data(transfer, sender, recipient)));
        }
    }

    @ParameterizedTest
    @EnumSource(Party.class)
    @Tag("M4-S19")
    @DisplayName("A blocked transfer is shown without its screening outcome")
    void aBlockedTransferIsShownWithoutItsScreeningOutcome(Party party) throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (ActivityStreamSupport.Stream stream = open(of(party, sender, recipient))) {
            publish(kafka, eventId, "TransferBlocked.v1", transfer, sender, recipient, null);

            assertThat(stream.take(1))
                    .containsExactly(event(eventId, "transfer.blocked", data(transfer, sender, recipient)));
        }
    }

    @Test
    @Tag("M4-S21")
    @DisplayName("A confirmed transfer goes from blocked to posted on the stream")
    void aConfirmedTransferGoesFromBlockedToPosted() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        UUID posted = UUID.randomUUID();
        try (ActivityStreamSupport.Stream senderStream = open(sender);
                ActivityStreamSupport.Stream recipientStream = open(recipient)) {
            publish(kafka, blocked, "TransferBlocked.v1", transfer, sender, recipient, null);
            publish(kafka, posted, "TransferPosted.v1", transfer, sender, recipient, null);

            List<Event> expected = List.of(
                    event(blocked, "transfer.blocked", data(transfer, sender, recipient)),
                    event(posted, "transfer.posted", data(transfer, sender, recipient)));
            assertThat(senderStream.take(2)).isEqualTo(expected);
            assertThat(recipientStream.take(2)).isEqualTo(expected);
        }
    }

    @Test
    @Tag("M4-S22")
    @DisplayName("A cleared transfer shows no event before it is posted")
    void aClearedTransferShowsNoEventBeforeItIsPosted() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        UUID posted = UUID.randomUUID();
        try (ActivityStreamSupport.Stream senderStream = open(sender);
                ActivityStreamSupport.Stream recipientStream = open(recipient)) {
            publish(kafka, UUID.randomUUID(), "TransferInitiated.v1", transfer, sender, recipient, null);
            publish(kafka, UUID.randomUUID(), "FundsReserved.v1", transfer, sender, recipient, null);
            publish(kafka, posted, "TransferPosted.v1", transfer, sender, recipient, null);

            List<Event> expected = List.of(event(posted, "transfer.posted", data(transfer, sender, recipient)));
            assertThat(senderStream.take(1)).isEqualTo(expected);
            assertThat(recipientStream.take(1)).isEqualTo(expected);
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> causes() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.argumentSet("insufficient funds", "INSUFFICIENT_FUNDS"),
                org.junit.jupiter.params.provider.Arguments.argumentSet("no fraud result in time", "SCREENING_TIMEOUT"),
                org.junit.jupiter.params.provider.Arguments.argumentSet(
                        "no confirmation before the end", "VERIFICATION_EXPIRED"));
    }

    @ParameterizedTest
    @MethodSource("causes")
    @Tag("M4-S23")
    @DisplayName("A failed transfer reaches the sender with its cause")
    void aFailedTransferReachesTheSenderWithItsCause(String cause) throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (ActivityStreamSupport.Stream senderStream = open(sender);
                ActivityStreamSupport.Stream recipientStream = open(recipient)) {
            publish(kafka, eventId, "TransferFailed.v1", transfer, sender, recipient, cause);

            assertThat(senderStream.take(1))
                    .containsExactly(
                            event(eventId, "transfer.failed", dataWithCause(transfer, sender, recipient, cause)));
            assertThat(recipientStream.take(1))
                    .containsExactly(event(eventId, "transfer.failed", data(transfer, sender, recipient)));
        }
    }

    @Test
    @Tag("M4-S24")
    @DisplayName("A third party receives nothing about someone else's transfer")
    void aThirdPartyReceivesNothing() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        try (ActivityStreamSupport.Stream thirdStream = open(third)) {
            publish(kafka, UUID.randomUUID(), "TransferPosted.v1", UUID.randomUUID(), sender, recipient, null);
            // The third user's own transfer, published after, shows the first had the time to arrive.
            UUID transfer = UUID.randomUUID();
            publish(kafka, theirs, "TransferPosted.v1", transfer, third, recipient, null);

            assertThat(thirdStream.take(1))
                    .containsExactly(event(theirs, "transfer.posted", data(transfer, third, recipient)));
        }
    }

    @Test
    @DisplayName("A user with two connections gets each event on both")
    void aUserWithTwoConnectionsGetsEachEventOnBoth() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (ActivityStreamSupport.Stream first = open(sender);
                ActivityStreamSupport.Stream second = open(sender)) {
            publish(kafka, eventId, "TransferPosted.v1", transfer, sender, recipient, null);

            List<Event> expected = List.of(event(eventId, "transfer.posted", data(transfer, sender, recipient)));
            assertThat(first.take(1)).isEqualTo(expected);
            assertThat(second.take(1)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("A user who is both parties gets one event of each kind, not two")
    void aUserWhoIsBothPartiesGetsOneEvent() throws Exception {
        UUID user = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        UUID posted = UUID.randomUUID();
        try (ActivityStreamSupport.Stream stream = open(user)) {
            publish(kafka, blocked, "TransferBlocked.v1", transfer, user, user, null);
            publish(kafka, posted, "TransferPosted.v1", transfer, user, user, null);

            assertThat(stream.take(2))
                    .containsExactly(
                            event(blocked, "transfer.blocked", data(transfer, user, user)),
                            event(posted, "transfer.posted", data(transfer, user, user)));
        }
    }

    @Test
    @DisplayName("A closed connection is dropped and another connection of the same user keeps receiving")
    void aClosedConnectionIsDroppedAndAnotherKeepsReceiving() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID earlierEventId = UUID.randomUUID();
        UUID earlierTransfer = UUID.randomUUID();
        try (ActivityStreamSupport.Stream staying = open(sender)) {
            ActivityStreamSupport.Stream leaving = open(sender);
            leaving.close();
            publish(kafka, earlierEventId, "TransferPosted.v1", earlierTransfer, sender, recipient, null);
            publish(kafka, eventId, "TransferPosted.v1", transfer, sender, recipient, null);

            assertThat(staying.take(2))
                    .containsExactly(
                            event(earlierEventId, "transfer.posted", data(earlierTransfer, sender, recipient)),
                            event(eventId, "transfer.posted", data(transfer, sender, recipient)));
        }
    }

    @Test
    @DisplayName("A record that is unreadable or has no value is skipped with a warning, and the next one is delivered")
    void anUnusableRecordIsSkippedAndTheNextIsDelivered(CapturedOutput output) throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (ActivityStreamSupport.Stream stream = open(sender)) {
            kafka.send(new ProducerRecord<>("payments.transfers", sender.toString(), "not json {"))
                    .join();
            kafka.send(new ProducerRecord<String, String>("payments.transfers", sender.toString(), null))
                    .join();
            publish(kafka, eventId, "TransferPosted.v1", transfer, sender, recipient, null);

            assertThat(stream.take(1))
                    .containsExactly(event(eventId, "transfer.posted", data(transfer, sender, recipient)));
        }
        List<List<String>> warnings = Arrays.stream(output.getOut().split("\n"))
                .filter(line -> line.contains("activity stream skipped a record"))
                .map(line -> JSON.readValue(line, Map.class))
                .map(line -> List.of((String) line.get("level"), (String) line.get("reason")))
                .toList();
        assertThat(warnings)
                .containsExactly(List.of("WARN", "record is not readable"), List.of("WARN", "record has no value"));
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers()))) {
            Set<String> topics = admin.listTopics().names().get();
            assertThat(topics).doesNotContain("payments.transfers.DLT", "payments.transfers-dlt");
        }
    }

    @Test
    @DisplayName(
            "The stream listener reads from the latest offset in a group of its own, and the fraud result listener keeps its group and offset")
    void listenerGroupsAndOffsets() {
        MessageListenerContainer stream = ActivityStreamSupport.streamContainer(registry);
        MessageListenerContainer fraud = registry.getListenerContainers().stream()
                .filter(c -> "orchestrator-fraud".equals(c.getGroupId()))
                .findFirst()
                .orElseThrow();

        assertThat(stream.getContainerProperties().getKafkaConsumerProperties().getProperty("auto.offset.reset"))
                .isEqualTo("latest");
        assertThat(stream.getGroupId()).startsWith(ActivityStreamSupport.GROUP_PREFIX);
        assertThat(fraud.getContainerProperties().getKafkaConsumerProperties().getProperty("auto.offset.reset"))
                .isNull();
        assertThat(consumerFactory.getConfigurationProperties()).containsEntry("auto.offset.reset", "earliest");
    }

    @ParameterizedTest
    @MethodSource("refusedTokens")
    @Tag("M4-S26")
    @DisplayName("The stream refuses a caller without a valid token")
    void theStreamRefusesACallerWithoutAValidToken(String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/stream/activity"))
                .header("Accept", "text/event-stream");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> refusedTokens() {
        UUID user = UUID.randomUUID();
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.argumentSet("no token", (String) null),
                org.junit.jupiter.params.provider.Arguments.argumentSet(
                        "an expired token", "Bearer " + TestTokens.expiredFor(user)),
                org.junit.jupiter.params.provider.Arguments.argumentSet(
                        "a token signed by another key", "Bearer " + TestTokens.signedByAnotherKey(user)));
    }
}
