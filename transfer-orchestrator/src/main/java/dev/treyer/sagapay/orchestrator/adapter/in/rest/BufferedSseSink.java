package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.orchestrator.application.port.in.OpenActivityStreamUseCase;
import dev.treyer.sagapay.orchestrator.domain.ActivityEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The sink of one stream. Writing to a client can block, so a push only enqueues: a bounded buffer
 * is drained by a task of its own, and a client that stays too slow is disconnected instead of
 * holding back the thread that relays events to every connection.
 */
final class BufferedSseSink implements OpenActivityStreamUseCase.Sink {

    private static final Logger log = LoggerFactory.getLogger(BufferedSseSink.class);

    private final UUID userId;
    private final SseEmitter emitter;
    private final Executor ioExecutor;
    private final Queue<ActivityEvent> buffer;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    BufferedSseSink(UUID userId, SseEmitter emitter, Executor ioExecutor, int bufferSize) {
        this.userId = userId;
        this.emitter = emitter;
        this.ioExecutor = ioExecutor;
        this.buffer = new ArrayBlockingQueue<>(bufferSize);
    }

    @Override
    public void push(ActivityEvent event) {
        if (closed.get()) {
            return;
        }
        if (!buffer.offer(event)) {
            log.warn("activity stream client too slow, disconnected", kv("user.id", userId));
            disconnect();
            return;
        }
        startDraining();
    }

    /** The client left or the stream timed out: nothing is written any more. */
    void stop() {
        closed.set(true);
    }

    private void startDraining() {
        if (draining.compareAndSet(false, true)) {
            ioExecutor.execute(this::drain);
        }
    }

    private void drain() {
        try {
            ActivityEvent event;
            while (!closed.get() && (event = buffer.poll()) != null) {
                // Written as {@code activity-stream.openapi.yaml} has it: the event names are known here only.
                emitter.send(SseEmitter.event()
                        .id(event.eventId().toString())
                        .name(nameOf(event))
                        .data(dataOf(event), MediaType.APPLICATION_JSON));
            }
        } catch (IOException e) {
            // The client left: routine, no stack trace.
            log.info("activity stream client gone, disconnected", kv("user.id", userId));
            disconnect();
        } catch (RuntimeException e) {
            log.warn("activity stream write failed, disconnected", kv("user.id", userId), e);
            disconnect();
        } finally {
            draining.set(false);
            if (!closed.get() && !buffer.isEmpty()) {
                startDraining();
            }
        }
    }

    private void disconnect() {
        if (closed.compareAndSet(false, true)) {
            // Completing the emitter can wait on a write that is blocked: it never runs on the caller's thread.
            ioExecutor.execute(emitter::complete);
        }
    }

    private static String nameOf(ActivityEvent event) {
        return switch (event.outcome()) {
            case POSTED -> "transfer.posted";
            case BLOCKED -> "transfer.blocked";
            case FAILED -> "transfer.failed";
            default -> throw new IllegalStateException("no stream event for " + event.outcome());
        };
    }

    private static Map<String, Object> dataOf(ActivityEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transferId", event.transferId().toString());
        data.put("senderId", event.senderId().toString());
        data.put("recipientId", event.recipientId().toString());
        data.put("amount", event.amount().toPlainString());
        data.put("currency", event.currency());
        if (event.cause() != null) {
            data.put("cause", event.cause().name());
        }
        return data;
    }
}
