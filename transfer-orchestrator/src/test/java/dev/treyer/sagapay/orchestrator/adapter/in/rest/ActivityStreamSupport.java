package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.orchestrator.security.TestTokens;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** What the activity stream tests share: a published event in the form the outbox gives it, and a
 * client that reads the server-sent events a user is pushed. */
public final class ActivityStreamSupport {

    static final String GROUP_PREFIX = "orchestrator-activity-";
    static final Duration PATIENCE = Duration.ofSeconds(30);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ActivityStreamSupport() {}

    /** The listener reads from the latest offset: nothing is published before its partition is assigned. */
    public static void awaitStreamListenerAssigned(KafkaListenerEndpointRegistry registry) {
        MessageListenerContainer container = streamContainer(registry);
        ContainerTestUtils.waitForAssignment(container, 1);
    }

    static MessageListenerContainer streamContainer(KafkaListenerEndpointRegistry registry) {
        return registry.getListenerContainers().stream()
                .filter(c -> c.getGroupId() != null && c.getGroupId().startsWith(GROUP_PREFIX))
                .findFirst()
                .orElseThrow();
    }

    /** An event of {@code payments.transfers} as the outbox poller publishes it, keyed by the sender. */
    public static void publish(
            KafkaTemplate<String, String> kafka,
            UUID eventId,
            String type,
            UUID transferId,
            UUID senderId,
            UUID recipientId,
            String reason) {
        String eventType = type.substring(0, type.indexOf('.'));
        String reasonField = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        String value =
                """
                {"id":"%s","source":"transfer-orchestrator","type":"%s","time":"2026-11-01T10:00:00Z",\
                "subject":"%s","traceparent":null,"data":{"transferId":"%s","senderId":"%s",\
                "recipientId":"%s","amount":"80.0000","currency":"EUR","eventType":"%s"%s}}""".formatted(eventId, type, transferId, transferId, senderId, recipientId, eventType, reasonField);
        kafka.send(new ProducerRecord<>("payments.transfers", senderId.toString(), value))
                .join();
    }

    /** What the stream pushes: the data of the event with the fields of the contract only. */
    public static Map<String, Object> data(UUID transferId, UUID senderId, UUID recipientId) {
        return Map.of(
                "transferId", transferId.toString(),
                "senderId", senderId.toString(),
                "recipientId", recipientId.toString(),
                "amount", "80.0000",
                "currency", "EUR");
    }

    public static Map<String, Object> dataWithCause(UUID transferId, UUID senderId, UUID recipientId, String cause) {
        Map<String, Object> data = new java.util.HashMap<>(data(transferId, senderId, recipientId));
        data.put("cause", cause);
        return data;
    }

    public record Event(String id, String name, Map<?, ?> data) {}

    /** An open stream of one user, read in the background. */
    public static final class Stream implements AutoCloseable {

        private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        private final HttpResponse<java.util.stream.Stream<String>> response;
        private final Thread reader;
        private volatile boolean ended;

        public Stream(HttpClient http, int port, UUID userId) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/stream/activity"))
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + TestTokens.forSubject(userId))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            reader = Thread.ofPlatform().daemon().start(this::read);
        }

        int status() {
            return response.statusCode();
        }

        String contentType() {
            return response.headers().firstValue("Content-Type").orElse(null);
        }

        boolean ended() {
            return ended;
        }

        private void read() {
            String id = null;
            String name = null;
            String data = null;
            try (java.util.stream.Stream<String> lines = response.body()) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (line.isEmpty()) {
                        if (name != null) {
                            events.add(new Event(id, name, JSON.readValue(data, Map.class)));
                        }
                        id = null;
                        name = null;
                        data = null;
                    } else if (line.startsWith("id:")) {
                        id = line.substring(3).trim();
                    } else if (line.startsWith("event:")) {
                        name = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        data = line.substring(5).trim();
                    }
                }
            } catch (RuntimeException e) {
                // The connection was closed under the reader: the stream is over.
            } finally {
                ended = true;
            }
        }

        /** Waits for {@code count} events and returns them. */
        public List<Event> take(int count) throws InterruptedException {
            List<Event> taken = new ArrayList<>();
            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (taken.size() < count) {
                Event event = events.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (event == null) {
                    throw new AssertionError("expected " + count + " events, got " + taken);
                }
                taken.add(event);
            }
            return taken;
        }

        @Override
        public void close() {
            response.body().close();
        }
    }
}
