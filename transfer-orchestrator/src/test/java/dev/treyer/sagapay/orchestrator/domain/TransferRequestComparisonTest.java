package dev.treyer.sagapay.orchestrator.domain;

import dev.treyer.sagapay.common.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRequestComparisonTest {

    private static final UUID RECIPIENT = UUID.fromString("0a1b2c3d-0000-4000-8000-0000000000c3");
    private static final UUID ANOTHER_RECIPIENT = UUID.fromString("0a1b2c3d-0000-4000-8000-0000000000c4");

    private static Transfer stored(String amount, String currency, String note) {
        UUID sender = UUID.randomUUID();
        return new Transfer(
                UUID.randomUUID(),
                UUID.randomUUID(),
                sender,
                sender,
                RECIPIENT,
                RECIPIENT,
                new BigDecimal(amount),
                currency,
                note,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void sameRecipientAmountCurrencyAndNoteIsTheSameRequest() {
        assertThat(stored("80.0000", "EUR", "pizza").isSameRequest(RECIPIENT, Money.of("80.00", "EUR"), "pizza"))
                .isTrue();
    }

    @Test
    void amountsOfDifferentScaleAreTheSameRequest() {
        assertThat(stored("80.0000", "EUR", null).isSameRequest(RECIPIENT, Money.of("80", "EUR"), null))
                .isTrue();
    }

    @Test
    void twoAbsentNotesAreTheSameRequest() {
        assertThat(stored("80.0000", "EUR", null).isSameRequest(RECIPIENT, Money.of("80.00", "EUR"), null))
                .isTrue();
    }

    @Test
    void anAbsentStoredNoteDiffersFromAPresentRequestNote() {
        assertThat(stored("80.0000", "EUR", null).isSameRequest(RECIPIENT, Money.of("80.00", "EUR"), "pizza"))
                .isFalse();
    }

    @Test
    void aPresentStoredNoteDiffersFromAnAbsentRequestNote() {
        assertThat(stored("80.0000", "EUR", "pizza").isSameRequest(RECIPIENT, Money.of("80.00", "EUR"), null))
                .isFalse();
    }

    @Test
    void anEmptyNoteDiffersFromAnAbsentOne() {
        assertThat(stored("80.0000", "EUR", null).isSameRequest(RECIPIENT, Money.of("80.00", "EUR"), ""))
                .isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "another amount,80.01,EUR,pizza,false",
        "another currency,80.00,USD,pizza,false",
        "another note,80.00,EUR,Pizza,false",
        "another recipient,80.00,EUR,pizza,true"
    })
    void aDifferenceInAnyFieldIsAnotherRequest(
            String difference, String amount, String currency, String note, boolean otherRecipient) {
        UUID recipient = otherRecipient ? ANOTHER_RECIPIENT : RECIPIENT;

        assertThat(stored("80.0000", "EUR", "pizza").isSameRequest(recipient, Money.of(amount, currency), note))
                .isFalse();
    }
}
