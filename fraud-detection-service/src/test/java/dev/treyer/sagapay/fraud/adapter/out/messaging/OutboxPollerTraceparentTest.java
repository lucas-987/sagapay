package dev.treyer.sagapay.fraud.adapter.out.messaging;

import dev.treyer.sagapay.fraud.application.port.out.OutboxRelayPort;
import dev.treyer.sagapay.fraud.domain.OutboxMessage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPollerTraceparentTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    private final OutboxRelayPort outbox = mock(OutboxRelayPort.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final Tracer tracer = mock(Tracer.class);
    private final Span span = mock(Span.class);
    private final TraceContext context = mock(TraceContext.class);

    private final OutboxPoller poller =
            new OutboxPoller(outbox, kafka, JsonMapper.builder().build(), tracer);

    private void oneRowToPublish() {
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name("outbox-publish")).thenReturn(span);
        when(span.start()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(TRACE_ID);
        when(context.spanId()).thenReturn(SPAN_ID);
        when(outbox.findUnpublishedForUpdateSkipLocked(100))
                .thenReturn(List.of(new OutboxMessage(
                        UUID.randomUUID(), "Transfer", UUID.randomUUID().toString(), "TransferCleared", "{}")));
    }

    @ParameterizedTest(name = "sampled={0}")
    @CsvSource({"true,01", "false,00", "null,00"})
    void theTraceparentHeaderCarriesTheTraceIdTheSpanIdAndTheSampledFlagOfTheSpan(String sampled, String flags) {
        oneRowToPublish();
        when(context.sampled()).thenReturn("null".equals(sampled) ? null : Boolean.valueOf(sampled));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        poller.drain();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertThat(new String(
                        sent.getValue().headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-" + flags);
        verify(span).end();
    }

    @Test
    void aFailedSendMarksTheSpanAsFailedBeforeEndingIt() {
        oneRowToPublish();
        when(context.sampled()).thenReturn(true);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        Throwable thrown = catchThrowable(poller::drain);

        assertThat(thrown).hasRootCauseMessage("broker down");
        var order = inOrder(span);
        order.verify(span).error(thrown);
        order.verify(span).end();
    }
}
