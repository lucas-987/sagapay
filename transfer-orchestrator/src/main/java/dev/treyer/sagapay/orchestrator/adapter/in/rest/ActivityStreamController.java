package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.orchestrator.application.port.in.OpenActivityStreamUseCase;
import dev.treyer.sagapay.orchestrator.config.ActivityStreamProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

/** Its contract, {@code activity-stream.openapi.yaml}, is written by hand: nothing is generated. */
@RestController
public class ActivityStreamController {

    /** A comment line, which clients ignore. */
    static final String OPEN_COMMENT = "open";

    private final OpenActivityStreamUseCase openActivityStream;
    private final SseSinkFactory sinks;
    private final long timeoutMs;

    public ActivityStreamController(
            OpenActivityStreamUseCase openActivityStream, SseSinkFactory sinks, ActivityStreamProperties properties) {
        this.openActivityStream = openActivityStream;
        this.sinks = sinks;
        this.timeoutMs = properties.timeoutMs();
    }

    @GetMapping(path = "/v1/stream/activity", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamActivity() throws IOException {
        UUID userId = CallerIdentity.requireUserId();
        SseEmitter emitter = new SseEmitter(timeoutMs);
        BufferedSseSink sink = sinks.create(emitter, userId);
        OpenActivityStreamUseCase.Connection connection = openActivityStream.open(userId, sink);
        // Every way the stream ends, the sink included (it completes the emitter), ends up in
        // onCompletion or onError: the subscription is released there and nowhere else.
        emitter.onCompletion(() -> end(sink, connection));
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> end(sink, connection));
        // Sends the headers at once: the client knows the stream is open, and a proxy does not wait for
        // the first event.
        emitter.send(SseEmitter.event().comment(OPEN_COMMENT));
        return emitter;
    }

    private static void end(BufferedSseSink sink, OpenActivityStreamUseCase.Connection connection) {
        sink.stop();
        connection.close();
    }
}
