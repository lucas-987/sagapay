package dev.treyer.sagapay.orchestrator.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** A position rather than an offset, stable under concurrent writes; {@code
 * createdAt} never changes. */
public record TransferCursor(Instant createdAt, UUID id) {

    private static final String SEPARATOR = "|";

    public static TransferCursor of(Transfer transfer) {
        return new TransferCursor(transfer.getCreatedAt(), transfer.getId());
    }

    public String encode() {
        String raw = createdAt + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static TransferCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf(SEPARATOR);
            return new TransferCursor(Instant.parse(raw.substring(0, sep)), UUID.fromString(raw.substring(sep + 1)));
        } catch (RuntimeException e) {
            throw new InvalidCursorException("malformed cursor", e);
        }
    }
}
