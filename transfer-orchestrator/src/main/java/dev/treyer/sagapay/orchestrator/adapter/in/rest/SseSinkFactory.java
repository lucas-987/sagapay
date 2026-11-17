package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Builds the sink of each stream: the threads that write to the clients and the one that times the
 * heartbeats are shared, each stream has its own buffer.
 */
public final class SseSinkFactory {

    private final Executor ioExecutor;
    private final ScheduledExecutorService heartbeats;
    private final int bufferSize;
    private final long heartbeatMs;

    public SseSinkFactory(Executor ioExecutor, ScheduledExecutorService heartbeats, int bufferSize, long heartbeatMs) {
        this.ioExecutor = ioExecutor;
        this.heartbeats = heartbeats;
        this.bufferSize = bufferSize;
        this.heartbeatMs = heartbeatMs;
    }

    BufferedSseSink create(SseEmitter emitter, UUID userId) {
        BufferedSseSink sink = new BufferedSseSink(userId, emitter, ioExecutor, bufferSize);
        sink.startHeartbeat(heartbeats, heartbeatMs);
        return sink;
    }
}
