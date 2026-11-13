package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.in.OpenActivityStreamUseCase;
import dev.treyer.sagapay.orchestrator.domain.ActivityEvent;
import dev.treyer.sagapay.orchestrator.domain.TransferEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ActivityStreamServiceTest {

    private static final UUID SENDER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RECIPIENT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID THIRD = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    private ActivityStreamService service(int bufferSize) {
        return new ActivityStreamService(executor, bufferSize);
    }

    private static TransferEvent posted(UUID sender, UUID recipient) {
        return new TransferEvent(
                UUID.randomUUID(), "TransferPosted.v1", UUID.randomUUID(), sender, recipient, "80.0000", "EUR", null);
    }

    static Stream<Arguments> writeFailures() {
        return Stream.of(
                Arguments.argumentSet("the client left", new IOException("broken pipe")),
                Arguments.argumentSet("an unexpected failure", new IllegalStateException("encoder failed")));
    }

    private static List<String> ids(RecordingSink sink) {
        return sink.received.stream().map(ActivityEvent::id).toList();
    }

    @Test
    @DisplayName("An event is pushed to the connections of both parties and to nobody else")
    void anEventReachesBothPartiesAndNobodyElse() {
        ActivityStreamService service = service(8);
        RecordingSink sender = new RecordingSink();
        RecordingSink recipient = new RecordingSink();
        RecordingSink third = new RecordingSink();
        service.open(SENDER, sender);
        service.open(RECIPIENT, recipient);
        service.open(THIRD, third);
        TransferEvent event = posted(SENDER, RECIPIENT);

        service.relay(event);

        await().atMost(PATIENCE).until(() -> sender.received.size() == 1 && recipient.received.size() == 1);
        assertThat(ids(sender)).containsExactly(event.eventId().toString());
        assertThat(ids(recipient)).containsExactly(event.eventId().toString());
        // A marker pushed to the third user afterwards proves the first event had its chance to arrive.
        TransferEvent marker = posted(THIRD, THIRD);
        service.relay(marker);
        await().atMost(PATIENCE).until(() -> !third.received.isEmpty());
        assertThat(ids(third)).containsExactly(marker.eventId().toString());
    }

    @Test
    @DisplayName("A user with two connections gets each event on both")
    void aUserWithTwoConnectionsGetsEachEventOnBoth() {
        ActivityStreamService service = service(8);
        RecordingSink phone = new RecordingSink();
        RecordingSink laptop = new RecordingSink();
        service.open(SENDER, phone);
        service.open(SENDER, laptop);
        TransferEvent event = posted(SENDER, RECIPIENT);

        service.relay(event);

        await().atMost(PATIENCE).until(() -> phone.received.size() == 1 && laptop.received.size() == 1);
        assertThat(ids(phone)).containsExactly(event.eventId().toString());
        assertThat(ids(laptop)).containsExactly(event.eventId().toString());
    }

    @Test
    @DisplayName("A user who is both parties gets one event, not two")
    void aUserWhoIsBothPartiesGetsOneEvent() {
        ActivityStreamService service = service(8);
        RecordingSink sink = new RecordingSink();
        service.open(SENDER, sink);
        TransferEvent toSelf = posted(SENDER, SENDER);
        TransferEvent marker = posted(SENDER, RECIPIENT);

        service.relay(toSelf);
        service.relay(marker);

        await().atMost(PATIENCE).until(() -> sink.received.size() >= 2);
        assertThat(ids(sink))
                .containsExactly(toSelf.eventId().toString(), marker.eventId().toString());
    }

    @Test
    @DisplayName("The events of a connection arrive in the order they were relayed")
    void eventsArriveInOrder() {
        ActivityStreamService service = service(8);
        RecordingSink sink = new RecordingSink();
        service.open(SENDER, sink);
        List<TransferEvent> events = List.of(
                posted(SENDER, RECIPIENT),
                posted(SENDER, RECIPIENT),
                posted(SENDER, RECIPIENT),
                posted(SENDER, RECIPIENT));

        events.forEach(service::relay);

        await().atMost(PATIENCE).until(() -> sink.received.size() == 4);
        assertThat(ids(sink))
                .containsExactlyElementsOf(
                        events.stream().map(e -> e.eventId().toString()).toList());
    }

    @Test
    @DisplayName("A closed connection is removed and the others keep receiving")
    void aClosedConnectionIsRemovedAndTheOthersKeepReceiving() {
        ActivityStreamService service = service(8);
        RecordingSink closing = new RecordingSink();
        RecordingSink staying = new RecordingSink();
        OpenActivityStreamUseCase.Connection connection = service.open(SENDER, closing);
        service.open(SENDER, staying);

        connection.close();
        TransferEvent event = posted(SENDER, RECIPIENT);
        service.relay(event);

        await().atMost(PATIENCE).until(() -> !staying.received.isEmpty());
        assertThat(ids(staying)).containsExactly(event.eventId().toString());
        assertThat(closing.received).isEmpty();
        assertThat(closing.closed.get()).isZero();
    }

    @ParameterizedTest
    @MethodSource("writeFailures")
    @DisplayName("A connection whose write fails is closed and removed, and the others keep receiving")
    void aFailingWriteDisconnectsOnlyThatConnection(Exception failure) {
        ActivityStreamService service = service(8);
        RecordingSink broken = new RecordingSink();
        broken.failWith = failure;
        RecordingSink healthy = new RecordingSink();
        service.open(SENDER, broken);
        service.open(SENDER, healthy);

        TransferEvent first = posted(SENDER, RECIPIENT);
        service.relay(first);
        await().atMost(PATIENCE).until(() -> broken.closed.get() == 1);
        TransferEvent next = posted(SENDER, RECIPIENT);
        service.relay(next);

        await().atMost(PATIENCE).until(() -> healthy.received.size() == 2);
        assertThat(ids(healthy))
                .containsExactly(first.eventId().toString(), next.eventId().toString());
        assertThat(broken.attempts.get()).isEqualTo(1);
        assertThat(broken.closed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("A client that does not read is disconnected once its buffer is full, and another keeps receiving")
    void aSlowClientIsDisconnectedAndAnotherKeepsReceiving() {
        ActivityStreamService service = service(2);
        RecordingSink slow = new RecordingSink();
        slow.blocked = new CountDownLatch(1);
        RecordingSink fast = new RecordingSink();
        service.open(SENDER, slow);
        service.open(SENDER, fast);
        // One event in the blocked write, two in the buffer, the fourth finds it full.
        List<TransferEvent> events = List.of(
                posted(SENDER, RECIPIENT),
                posted(SENDER, RECIPIENT),
                posted(SENDER, RECIPIENT),
                posted(SENDER, RECIPIENT));

        for (TransferEvent event : events) {
            service.relay(event);
            await().atMost(PATIENCE).until(() -> slow.attempts.get() >= 1);
        }

        await().atMost(PATIENCE).until(() -> fast.received.size() == 4);
        await().atMost(PATIENCE).until(() -> slow.closed.get() == 1);
        TransferEvent after = posted(SENDER, RECIPIENT);
        service.relay(after);
        await().atMost(PATIENCE).until(() -> fast.received.size() == 5);
        slow.blocked.countDown();
        // A negative outcome: the queued events must stay unwritten, so the writer gets a moment to prove it.
        await().during(Duration.ofMillis(300))
                .atMost(PATIENCE)
                .until(() -> slow.attempts.get() == 1 && slow.sent.get() == 1);
        assertThat(ids(fast))
                .containsExactlyElementsOf(Stream.concat(events.stream(), Stream.of(after))
                        .map(e -> e.eventId().toString())
                        .toList());
        // Only the event that was being written when the client was cut off reached it: the two queued
        // events stay unwritten once the connection is closed.
        assertThat(ids(slow)).containsExactly(events.get(0).eventId().toString());
        assertThat(slow.attempts.get()).isEqualTo(1);
        assertThat(slow.closed.get()).isEqualTo(1);
    }

    private static final class RecordingSink implements OpenActivityStreamUseCase.Sink {
        final List<ActivityEvent> received = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger sent = new AtomicInteger();
        volatile Exception failWith;
        volatile CountDownLatch blocked;

        @Override
        public void send(ActivityEvent event) throws IOException {
            attempts.incrementAndGet();
            if (failWith instanceof IOException io) {
                throw io;
            }
            if (failWith instanceof RuntimeException runtime) {
                throw runtime;
            }
            CountDownLatch latch = blocked;
            if (latch != null) {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            received.add(event);
            sent.incrementAndGet();
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }
}
