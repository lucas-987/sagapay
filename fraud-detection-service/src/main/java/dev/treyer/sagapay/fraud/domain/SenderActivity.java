package dev.treyer.sagapay.fraud.domain;

import java.util.Set;
import java.util.UUID;

/** The sender's counters as they were before the transfer being screened. */
public record SenderActivity(int transfersInLastHour, Set<UUID> recentRecipients) {

    public SenderActivity {
        recentRecipients = Set.copyOf(recentRecipients);
    }
}
