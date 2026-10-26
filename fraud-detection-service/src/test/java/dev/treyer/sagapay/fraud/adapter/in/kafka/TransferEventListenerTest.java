package dev.treyer.sagapay.fraud.adapter.in.kafka;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.fraud.domain.ReservedTransfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferEventListenerTest {

    private static final UUID TRANSFER = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID SENDER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RECIPIENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final List<ReservedTransfer> screened = new ArrayList<>();
    private final TransferEventListener listener = new TransferEventListener(screened::add);

    private static TransferEventMessage.Data data(String eventType) {
        return new TransferEventMessage.Data(eventType, TRANSFER, SENDER, RECIPIENT, new BigDecimal("500.00"), "EUR");
    }

    private static TransferEventMessage message(String id, TransferEventMessage.Data data) {
        return new TransferEventMessage(id, data);
    }

    @Test
    void aFundsReservedEventIsScreenedWithTheEventIdOfTheEnvelope() {
        listener.onTransferEvent(message("event-1", data("FundsReserved")));

        assertThat(screened)
                .containsExactly(
                        new ReservedTransfer("event-1", TRANSFER, SENDER, RECIPIENT, Money.of("500.00", "EUR")));
    }

    @ParameterizedTest(name = "{0} is ignored")
    @ValueSource(strings = {"TransferInitiated", "TransferPosted", "TransferFailed", "SomethingNew"})
    void otherEventTypesAreIgnored(String eventType) {
        listener.onTransferEvent(message("event-1", data(eventType)));

        assertThat(screened).isEmpty();
    }

    @Test
    void aTombstoneIsRefusedAsMalformedWithoutBeingScreened() {
        assertThatThrownBy(() -> listener.onTransferEvent(null))
                .isExactlyInstanceOf(MalformedTransferEventException.class)
                .hasMessage("transfer event is empty");

        assertThat(screened).isEmpty();
    }

    static Stream<Arguments> malformedReservations() {
        return Stream.of(
                malformed("no data", "event-1", null, "transfer event has no data"),
                malformed("no event type", "event-1", data(null), "transfer event has no eventType"),
                malformed("no id", null, data("FundsReserved"), "FundsReserved event has no id"),
                malformed(
                        "no transferId",
                        "event-1",
                        new TransferEventMessage.Data(
                                "FundsReserved", null, SENDER, RECIPIENT, new BigDecimal("5.00"), "EUR"),
                        "FundsReserved event has no transferId"),
                malformed(
                        "no senderId",
                        "event-1",
                        new TransferEventMessage.Data(
                                "FundsReserved", TRANSFER, null, RECIPIENT, new BigDecimal("5.00"), "EUR"),
                        "FundsReserved event has no senderId"),
                malformed(
                        "no recipientId",
                        "event-1",
                        new TransferEventMessage.Data(
                                "FundsReserved", TRANSFER, SENDER, null, new BigDecimal("5.00"), "EUR"),
                        "FundsReserved event has no recipientId"),
                malformed(
                        "no amount",
                        "event-1",
                        new TransferEventMessage.Data("FundsReserved", TRANSFER, SENDER, RECIPIENT, null, "EUR"),
                        "FundsReserved event has no amount"),
                malformed(
                        "no currency",
                        "event-1",
                        new TransferEventMessage.Data(
                                "FundsReserved", TRANSFER, SENDER, RECIPIENT, new BigDecimal("5.00"), null),
                        "FundsReserved event has no currency"),
                malformed(
                        "an unknown currency",
                        "event-1",
                        new TransferEventMessage.Data(
                                "FundsReserved", TRANSFER, SENDER, RECIPIENT, new BigDecimal("5.00"), "ZZZ"),
                        "FundsReserved event has an invalid amount or currency"),
                malformed(
                        "an amount finer than four decimals",
                        "event-1",
                        new TransferEventMessage.Data(
                                "FundsReserved", TRANSFER, SENDER, RECIPIENT, new BigDecimal("5.00001"), "EUR"),
                        "FundsReserved event has an invalid amount or currency"));
    }

    private static Arguments malformed(String name, String id, TransferEventMessage.Data data, String expectedMessage) {
        return Arguments.of(name, message(id, data), expectedMessage);
    }

    @ParameterizedTest(name = "a reservation with {0} is refused")
    @MethodSource("malformedReservations")
    void aMalformedReservationIsRefusedWithoutBeingScreened(
            String name, TransferEventMessage message, String expectedMessage) {
        assertThatThrownBy(() -> listener.onTransferEvent(message))
                .isExactlyInstanceOf(MalformedTransferEventException.class)
                .hasMessage(expectedMessage);

        assertThat(screened).isEmpty();
    }
}
