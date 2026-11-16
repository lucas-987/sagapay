package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.orchestrator.domain.ActivityEvent;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BufferedSseSinkTest {

    private static final UUID USER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final RecordingEmitter emitter = new RecordingEmitter();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    private BufferedSseSink sink(int bufferSize) {
        return new BufferedSseSink(USER, emitter, executor, bufferSize);
    }

    private static List<String> ids(List<ActivityEvent> events) {
        return events.stream().map(e -> e.eventId().toString()).toList();
    }

    private static ActivityEvent event() {
        return new ActivityEvent(
                UUID.randomUUID(),
                TransferStatus.POSTED,
                UUID.randomUUID(),
                USER,
                UUID.randomUUID(),
                new BigDecimal("80.0000"),
                "EUR",
                null);
    }

    static Stream<Arguments> writeFailures() {
        return Stream.of(
                Arguments.argumentSet("the client left", new IOException("broken pipe")),
                Arguments.argumentSet("an unexpected failure", new IllegalStateException("encoder failed")));
    }

    @Test
    @DisplayName("Events are written in the order they were pushed")
    void eventsAreWrittenInOrder() {
        BufferedSseSink sink = sink(8);
        List<ActivityEvent> events = List.of(event(), event(), event(), event());

        events.forEach(sink::push);

        await().atMost(PATIENCE).until(() -> emitter.written.size() == 4);
        assertThat(emitter.written).containsExactlyElementsOf(ids(events));
        assertThat(emitter.completed.get()).isZero();
    }

    @ParameterizedTest
    @MethodSource("writeFailures")
    @DisplayName("A write that fails ends the sink once and closes its connection, and later events are dropped")
    void aFailingWriteEndsTheSink(Exception failure) {
        emitter.failWith = failure;
        BufferedSseSink sink = sink(8);

        sink.push(event());
        await().atMost(PATIENCE).until(() -> emitter.completed.get() == 1);
        sink.push(event());

        // A negative outcome: the dropped event must stay unwritten, so the emitter gets a moment to prove it.
        await().during(Duration.ofMillis(300)).atMost(PATIENCE).until(() -> emitter.attempts.get() == 1);
        assertThat(emitter.completed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "A client that does not read is disconnected once its buffer is full, and its queued events are dropped")
    void aSlowClientIsDisconnectedOnceItsBufferIsFull() {
        emitter.blocked = new CountDownLatch(1);
        BufferedSseSink sink = sink(2);
        ActivityEvent first = event();

        // One event in the blocked write, two in the buffer, the fourth finds it full.
        sink.push(first);
        await().atMost(PATIENCE).until(() -> emitter.attempts.get() == 1);
        sink.push(event());
        sink.push(event());
        assertThat(emitter.completed.get()).isZero();
        sink.push(event());

        await().atMost(PATIENCE).until(() -> emitter.completed.get() == 1);
        emitter.blocked.countDown();
        await().during(Duration.ofMillis(300))
                .atMost(PATIENCE)
                .until(() -> emitter.attempts.get() == 1 && emitter.written.size() == 1);
        assertThat(emitter.written).containsExactly(first.eventId().toString());
    }

    @Test
    @DisplayName("A stream that ended on its own stops the sink without closing its connection again")
    void aStoppedSinkWritesNothingAndDoesNotCloseTheConnection() {
        BufferedSseSink sink = sink(8);

        sink.stop();
        sink.stop();
        sink.push(event());

        await().during(Duration.ofMillis(300)).atMost(PATIENCE).until(() -> emitter.attempts.get() == 0);
        assertThat(emitter.completed.get()).isZero();
    }

    @Test
    @DisplayName("Disconnecting a slow client returns at once even when completing its emitter has to wait")
    void disconnectingDoesNotWaitForTheEmitter() {
        emitter.blocked = new CountDownLatch(1);
        emitter.completeBlocked = new CountDownLatch(1);
        BufferedSseSink sink = sink(1);

        sink.push(event());
        await().atMost(PATIENCE).until(() -> emitter.attempts.get() == 1);
        sink.push(event());
        // The buffer is full: this push disconnects, and must come back while complete() still waits.
        sink.push(event());

        assertThat(emitter.completed.get()).isZero();
        emitter.completeBlocked.countDown();
        await().atMost(PATIENCE).until(() -> emitter.completed.get() == 1);
        emitter.blocked.countDown();
    }

    @Test
    @DisplayName("An event is sent with its id, its name and the fields of the contract")
    void anEventIsSentAsTheContractHasIt() {
        BufferedSseSink sink = sink(8);
        ActivityEvent event = event();

        sink.push(event);

        await().atMost(PATIENCE).until(() -> emitter.sent.size() == 1);
        assertThat(emitter.sent.get(0))
                .startsWith("id:" + event.eventId() + "\nevent:transfer.posted\ndata:")
                .contains("transferId=" + event.transferId())
                .contains("amount=80.0000")
                .contains("currency=EUR")
                .doesNotContain("cause");
    }

    private static final class RecordingEmitter extends SseEmitter {
        static final Pattern ID = Pattern.compile("^id:([0-9a-f-]+)\\n");

        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<String> written = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        volatile Exception failWith;
        volatile CountDownLatch blocked;
        volatile CountDownLatch completeBlocked;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
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
            StringBuilder text = new StringBuilder();
            builder.build().forEach(part -> text.append(part.getData()));
            sent.add(text.toString());
            Matcher id = ID.matcher(text);
            if (id.find()) {
                written.add(id.group(1));
            }
        }

        @Override
        public void complete() {
            CountDownLatch latch = completeBlocked;
            if (latch != null) {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            completed.incrementAndGet();
        }
    }
}
