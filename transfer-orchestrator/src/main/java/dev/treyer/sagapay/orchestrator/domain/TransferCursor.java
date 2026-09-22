package dev.treyer.sagapay.orchestrator.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** The last page item's {@code (createdAt, id)}, not a plain offset — same
 * reasoning as the ledger's {@code PostingCursor}: {@code transfers} keeps
 * receiving writes (status transitions bump {@code updatedAt}, but {@code
 * createdAt} never changes), so a position-based cursor stays stable no matter
 * what's happened since the previous page was read. */
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
