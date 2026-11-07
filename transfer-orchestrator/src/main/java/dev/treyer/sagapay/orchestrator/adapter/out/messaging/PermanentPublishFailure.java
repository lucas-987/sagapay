package dev.treyer.sagapay.orchestrator.adapter.out.messaging;

import org.apache.kafka.common.errors.RecordTooLargeException;
import tools.jackson.core.JacksonException;

import java.util.Optional;

/** The closed list of failures a retry cannot fix. Anything else counts as temporary: treated as
 * permanent, a Kafka outage would put every key in quarantine. */
final class PermanentPublishFailure {

    private PermanentPublishFailure() {}

    static Optional<Throwable> causeOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException || cause instanceof RecordTooLargeException) {
                return Optional.of(cause);
            }
        }
        return Optional.empty();
    }
}
