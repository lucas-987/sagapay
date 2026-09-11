package dev.treyer.sagapay.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event envelope inspired by CloudEvents, serialized as JSON on Kafka.
 * The metadata (id, source, type, time, subject, traceparent) wraps the
 * business payload {@code data}.
 *
 * @param <T> the payload type (e.g. a TransferPosted record defined on the service side)
 */
public record CloudEvent<T>(
        String id,
        String source,
        String type,
        Instant time,
        String subject,
        String traceparent,
        T data) {

    public static <T> CloudEvent<T> now(String source, String type, String subject, T data) {
        return new CloudEvent<>(
                UUID.randomUUID().toString(), source, type, Instant.now(), subject, null, data);
    }

    public CloudEvent<T> withTraceparent(String traceparent) {
        return new CloudEvent<>(id, source, type, time, subject, traceparent, data);
    }
}