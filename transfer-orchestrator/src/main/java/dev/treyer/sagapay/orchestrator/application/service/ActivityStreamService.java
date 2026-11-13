package dev.treyer.sagapay.orchestrator.application.service;

import dev.treyer.sagapay.orchestrator.application.port.in.OpenActivityStreamUseCase;
import dev.treyer.sagapay.orchestrator.application.port.in.RelayTransferEventUseCase;
import dev.treyer.sagapay.orchestrator.domain.ActivityEvent;
import dev.treyer.sagapay.orchestrator.domain.TransferEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The open connections of this instance, per user. Writing to a client can block, so each connection
 * has a bounded buffer drained by a task of its own: the thread that relays an event only enqueues
 * it, and a client that stays too slow is disconnected instead of holding the others back.
 */
public class ActivityStreamService implements OpenActivityStreamUseCase, RelayTransferEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(ActivityStreamService.class);

    private final Map<UUID, Set<StreamConnection>> connections = new ConcurrentHashMap<>();
    private final Executor writers;
    private final int bufferSize;

    public ActivityStreamService(Executor writers, int bufferSize) {
        this.writers = writers;
        this.bufferSize = bufferSize;
    }

    @Override
    public Connection open(UUID userId, Sink sink) {
        StreamConnection connection = new StreamConnection(userId, sink);
        connections.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(connection);
        return connection;
    }

    @Override
    public void relay(TransferEvent event) {
        for (ActivityMapping.Delivery delivery : ActivityMapping.of(event)) {
            for (StreamConnection connection : connections.getOrDefault(delivery.userId(), Set.of())) {
                connection.offer(delivery.event());
            }
        }
    }

    private void remove(StreamConnection connection) {
        connections.computeIfPresent(connection.userId, (id, open) -> {
            open.remove(connection);
            return open.isEmpty() ? null : open;
        });
    }

    private final class StreamConnection implements Connection {

        private final UUID userId;
        private final Sink sink;
        private final Queue<ActivityEvent> buffer = new ArrayBlockingQueue<>(bufferSize);
        private final AtomicBoolean draining = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private StreamConnection(UUID userId, Sink sink) {
            this.userId = userId;
            this.sink = sink;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                remove(this);
            }
        }

        private void offer(ActivityEvent event) {
            if (!buffer.offer(event)) {
                log.warn("activity stream client too slow, disconnected", kv("user.id", userId));
                disconnect();
                return;
            }
            startDraining();
        }

        private void startDraining() {
            if (draining.compareAndSet(false, true)) {
                writers.execute(this::drain);
            }
        }

        private void drain() {
            try {
                ActivityEvent event;
                while (!closed.get() && (event = buffer.poll()) != null) {
                    sink.send(event);
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

        /** Closing the sink may wait on the same write as the one that is blocked: it never runs on the
         * relaying thread. */
        private void disconnect() {
            if (closed.compareAndSet(false, true)) {
                remove(this);
                writers.execute(sink::close);
            }
        }
    }
}
