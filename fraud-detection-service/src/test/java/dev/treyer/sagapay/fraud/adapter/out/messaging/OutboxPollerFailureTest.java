package dev.treyer.sagapay.fraud.adapter.out.messaging;

import dev.treyer.sagapay.fraud.application.port.out.OutboxRelayPort;
import dev.treyer.sagapay.fraud.domain.OutboxMessage;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPollerFailureTest {

    private final OutboxRelayPort outbox = mock(OutboxRelayPort.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final Tracer tracer = Tracer.NOOP;

    private final OutboxPoller poller =
            new OutboxPoller(outbox, kafka, JsonMapper.builder().build(), tracer);

    private static OutboxMessage message(UUID id) {
        return new OutboxMessage(id, "Transfer", UUID.randomUUID(), "TransferCleared", "{}");
    }

    @Test
    void aRowWhosePublishFailsIsNotMarkedPublishedAndTheRowsAfterItAreNotSent() {
        UUID failing = UUID.randomUUID();
        UUID after = UUID.randomUUID();
        when(outbox.findUnpublishedForUpdateSkipLocked(100)).thenReturn(List.of(message(failing), message(after)));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(poller::drain)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("broker down");

        verify(outbox, never()).markPublished(any());
        verify(kafka).send(any(ProducerRecord.class));
    }

    // The database stores the payload as jsonb, so this cannot happen through the adapter: the poller
    // still must not mark or send anything it could not turn into an event.
    @Test
    void aRowWhosePayloadIsNotJsonIsNotMarkedPublishedAndNothingIsSent() {
        OutboxMessage malformed =
                new OutboxMessage(UUID.randomUUID(), "Transfer", UUID.randomUUID(), "TransferCleared", "not json");
        when(outbox.findUnpublishedForUpdateSkipLocked(100)).thenReturn(List.of(malformed, message(UUID.randomUUID())));

        assertThatThrownBy(poller::drain).isInstanceOf(JacksonException.class);

        verify(outbox, never()).markPublished(any());
        verify(kafka, never()).send(any(ProducerRecord.class));
    }
}
