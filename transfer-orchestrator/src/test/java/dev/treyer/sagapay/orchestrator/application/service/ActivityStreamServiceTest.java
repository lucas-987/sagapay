package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.in.OpenActivityStreamUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.TransferEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityStreamServiceTest {

    private static final UUID SENDER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RECIPIENT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID THIRD = UUID.fromString("55555555-5555-4555-8555-555555555555");

    private final ActivityStreamService service = new ActivityStreamService();

    private static TransferEvent posted(UUID sender, UUID recipient) {
        return new TransferEvent(
                UUID.randomUUID(),
                TransferEvent.Outcome.POSTED,
                UUID.randomUUID(),
                sender,
                recipient,
                new BigDecimal("80.0000"),
                "EUR",
                null,
                null);
    }

    private static List<UUID> ids(RecordingSink sink) {
        return sink.received.stream().map(TransferEvent::eventId).toList();
    }

    @Test
    @DisplayName("An event is pushed to the connections of both parties and to nobody else")
    void anEventReachesBothPartiesAndNobodyElse() {
        RecordingSink sender = new RecordingSink();
        RecordingSink recipient = new RecordingSink();
        RecordingSink third = new RecordingSink();
        service.open(SENDER, sender);
        service.open(RECIPIENT, recipient);
        service.open(THIRD, third);
        TransferEvent event = posted(SENDER, RECIPIENT);

        service.relay(event);

        assertThat(ids(sender)).containsExactly(event.eventId());
        assertThat(ids(recipient)).containsExactly(event.eventId());
        assertThat(third.received).isEmpty();
    }

    @Test
    @DisplayName("A user with two connections gets each event on both")
    void aUserWithTwoConnectionsGetsEachEventOnBoth() {
        RecordingSink phone = new RecordingSink();
        RecordingSink laptop = new RecordingSink();
        service.open(SENDER, phone);
        service.open(SENDER, laptop);
        TransferEvent event = posted(SENDER, RECIPIENT);

        service.relay(event);

        assertThat(ids(phone)).containsExactly(event.eventId());
        assertThat(ids(laptop)).containsExactly(event.eventId());
    }

    @Test
    @DisplayName("A user who is both parties gets one event, not two")
    void aUserWhoIsBothPartiesGetsOneEvent() {
        RecordingSink sink = new RecordingSink();
        service.open(SENDER, sink);
        TransferEvent toSelf = posted(SENDER, SENDER);

        service.relay(toSelf);

        assertThat(ids(sink)).containsExactly(toSelf.eventId());
    }

    @Test
    @DisplayName("A closed connection is pushed nothing and the others keep receiving")
    void aClosedConnectionIsPushedNothingAndTheOthersKeepReceiving() {
        RecordingSink closing = new RecordingSink();
        RecordingSink staying = new RecordingSink();
        OpenActivityStreamUseCase.Connection connection = service.open(SENDER, closing);
        service.open(SENDER, staying);

        connection.close();
        TransferEvent event = posted(SENDER, RECIPIENT);
        service.relay(event);

        assertThat(ids(staying)).containsExactly(event.eventId());
        assertThat(closing.received).isEmpty();
    }

    @Test
    @DisplayName("A user whose last connection closed is pushed nothing, and a new connection receives again")
    void aUserWhoseLastConnectionClosedCanConnectAgain() {
        RecordingSink first = new RecordingSink();
        service.open(SENDER, first).close();
        TransferEvent missed = posted(SENDER, RECIPIENT);
        service.relay(missed);
        RecordingSink again = new RecordingSink();
        service.open(SENDER, again);
        TransferEvent next = posted(SENDER, RECIPIENT);

        service.relay(next);

        assertThat(first.received).isEmpty();
        assertThat(ids(again)).containsExactly(next.eventId());
    }

    @Test
    @DisplayName("Closing a connection twice leaves the other connections of the user open")
    void closingTwiceLeavesTheOthersOpen() {
        RecordingSink closing = new RecordingSink();
        RecordingSink staying = new RecordingSink();
        OpenActivityStreamUseCase.Connection connection = service.open(SENDER, closing);
        service.open(SENDER, staying);

        connection.close();
        connection.close();
        TransferEvent event = posted(SENDER, RECIPIENT);
        service.relay(event);

        assertThat(ids(staying)).containsExactly(event.eventId());
    }

    @Test
    @DisplayName("A connection opened while the user's last one closes still receives")
    void aConnectionOpenedWhileTheLastOneClosesStillReceives() throws Exception {
        // The race needs the two to overlap: many rounds make an overlap all but certain.
        for (int round = 0; round < 2_000; round++) {
            ActivityStreamService fresh = new ActivityStreamService();
            OpenActivityStreamUseCase.Connection last = fresh.open(SENDER, new RecordingSink());
            RecordingSink reconnected = new RecordingSink();
            CyclicBarrier start = new CyclicBarrier(2);
            Thread closer = Thread.ofPlatform().start(() -> {
                await(start);
                last.close();
            });
            await(start);
            fresh.open(SENDER, reconnected);
            closer.join();

            TransferEvent event = posted(SENDER, RECIPIENT);
            fresh.relay(event);

            assertThat(ids(reconnected)).as("round %d", round).containsExactly(event.eventId());
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class RecordingSink implements OpenActivityStreamUseCase.Sink {
        final List<TransferEvent> received = new CopyOnWriteArrayList<>();

        @Override
        public void push(TransferEvent event) {
            received.add(event);
        }
    }
}
