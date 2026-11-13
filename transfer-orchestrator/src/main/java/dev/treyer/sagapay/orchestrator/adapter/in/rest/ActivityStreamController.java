package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.orchestrator.application.port.in.OpenActivityStreamUseCase;
import dev.treyer.sagapay.orchestrator.config.ActivityStreamProperties;
import dev.treyer.sagapay.orchestrator.domain.ActivityEvent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Its contract, {@code activity-stream.openapi.yaml}, is written by hand: nothing is generated. */
@RestController
public class ActivityStreamController {

    private final OpenActivityStreamUseCase openActivityStream;
    private final long timeoutMs;

    public ActivityStreamController(OpenActivityStreamUseCase openActivityStream, ActivityStreamProperties properties) {
        this.openActivityStream = openActivityStream;
        this.timeoutMs = properties.timeoutMs();
    }

    @GetMapping(path = "/v1/stream/activity", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamActivity() throws IOException {
        UUID userId = CallerIdentity.requireUserId();
        SseEmitter emitter = new SseEmitter(timeoutMs);
        OpenActivityStreamUseCase.Connection connection = openActivityStream.open(userId, new EmitterSink(emitter));
        emitter.onCompletion(connection::close);
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> connection.close());
        // Sends the headers at once: the client knows the stream is open, and a proxy does not wait for
        // the first event.
        emitter.send(SseEmitter.event().comment("open"));
        return emitter;
    }

    private record EmitterSink(SseEmitter emitter) implements OpenActivityStreamUseCase.Sink {

        @Override
        public void send(ActivityEvent event) throws IOException {
            emitter.send(SseEmitter.event()
                    .id(event.id())
                    .name(event.name())
                    .data(dataOf(event), MediaType.APPLICATION_JSON));
        }

        @Override
        public void close() {
            emitter.complete();
        }

        private static Map<String, Object> dataOf(ActivityEvent event) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("transferId", event.transferId().toString());
            data.put("senderId", event.senderId().toString());
            data.put("recipientId", event.recipientId().toString());
            data.put("amount", event.amount());
            data.put("currency", event.currency());
            if (event.cause() != null) {
                data.put("cause", event.cause());
            }
            return data;
        }
    }
}
