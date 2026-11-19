package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import dev.treyer.sagapay.orchestrator.application.port.in.OpenActivityStreamUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.TransferEvent;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The sink of one stream. A push only enqueues into a bounded buffer drained by a task of its own:
 * a client too slow is disconnected instead of holding back the thread that relays every event.
 * Heartbeat comments share the buffer, so a quiet stream stays open through proxies and a client
 * that has gone is found by the write that fails.
 */
final class BufferedSseSink implements OpenActivityStreamUseCase.Sink {

    private static final Logger log = LoggerFactory.getLogger(BufferedSseSink.class);

    /** A comment line, which clients ignore. */
    static final String HEARTBEAT_COMMENT = "ping";

    private final UUID userId;
    private final SseEmitter emitter;
    private final Executor ioExecutor;
    private final Queue<SseEmitter.SseEventBuilder> buffer;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ScheduledFuture<?> heartbeat;

    BufferedSseSink(UUID userId, SseEmitter emitter, Executor ioExecutor, int bufferSize) {
        this.userId = userId;
        this.emitter = emitter;
        this.ioExecutor = ioExecutor;
        this.buffer = new ArrayBlockingQueue<>(bufferSize);
    }

    @Override
    public void push(TransferEvent event) {
        enqueue(frameOf(event));
    }

    /** Called at a fixed interval once {@link #startHeartbeat} has run. */
    void heartbeat() {
        enqueue(SseEmitter.event().comment(HEARTBEAT_COMMENT));
    }

    void startHeartbeat(ScheduledExecutorService scheduler, long intervalMs) {
        heartbeat = scheduler.scheduleAtFixedRate(this::heartbeat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        // The stream may have ended while the task was being scheduled.
        if (closed.get()) {
            cancelHeartbeat();
        }
    }

    /** The client left or the stream timed out: nothing is written any more. */
    void stop() {
        closed.set(true);
        cancelHeartbeat();
    }

    private void enqueue(SseEmitter.SseEventBuilder frame) {
        if (closed.get()) {
            return;
        }
        if (!buffer.offer(frame)) {
            log.warn("activity stream client too slow, disconnected", kv("user.id", userId));
            disconnect();
            return;
        }
        startDraining();
    }

    private void startDraining() {
        if (draining.compareAndSet(false, true)) {
            ioExecutor.execute(this::drain);
        }
    }

    private void drain() {
        try {
            SseEmitter.SseEventBuilder frame;
            while (!closed.get() && (frame = buffer.poll()) != null) {
                emitter.send(frame);
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
            cancelHeartbeat();
            // Completing the emitter can wait on a write that is blocked: it never runs on the caller's thread.
            ioExecutor.execute(emitter::complete);
        }
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> task = heartbeat;
        if (task != null) {
            task.cancel(false);
        }
    }

    /** Written as {@code activity-stream.openapi.yaml} has it. */
    private static SseEmitter.SseEventBuilder frameOf(TransferEvent event) {
        return SseEmitter.event()
                .id(event.eventId().toString())
                .name(ActivityStreamEventName.of(event.outcome()).wireName())
                .data(dataOf(event), MediaType.APPLICATION_JSON);
    }

    private static Map<String, Object> dataOf(TransferEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transferId", event.transferId().toString());
        data.put("senderId", event.senderId().toString());
        data.put("recipientId", event.recipientId().toString());
        data.put("amount", event.amount().toPlainString());
        data.put("currency", event.currency());
        if (event.reason() != null) {
            data.put("cause", event.reason().name());
        }
        if (event.confirmationDeadline() != null) {
            data.put("confirmationDeadline", event.confirmationDeadline().toString());
        }
        return data;
    }
}
