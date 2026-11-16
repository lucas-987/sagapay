package dev.treyer.sagapay.orchestrator.adapter.in.rest;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.Executor;

/** Builds the sink of each stream: the threads that write to the clients are shared, each stream has its own buffer. */
public final class SseSinkFactory {

    private final Executor ioExecutor;
    private final int bufferSize;

    public SseSinkFactory(Executor ioExecutor, int bufferSize) {
        this.ioExecutor = ioExecutor;
        this.bufferSize = bufferSize;
    }

    BufferedSseSink create(SseEmitter emitter, UUID userId) {
        return new BufferedSseSink(userId, emitter, ioExecutor, bufferSize);
    }
}
