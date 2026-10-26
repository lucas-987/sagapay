package dev.treyer.sagapay.fraud.adapter.in.kafka;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.fraud.application.port.in.ScreenTransferUseCase;
import dev.treyer.sagapay.fraud.domain.ReservedTransfer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class TransferEventListener {

    private static final String FUNDS_RESERVED = "FundsReserved";

    private final ScreenTransferUseCase screening;

    public TransferEventListener(ScreenTransferUseCase screening) {
        this.screening = screening;
    }

    @KafkaListener(topics = "payments.transfers", groupId = "fraud-screening")
    public void onTransferEvent(TransferEventMessage message) {
        TransferEventMessage.Data data = message.data();
        if (data == null) {
            throw new MalformedTransferEventException("transfer event has no data");
        }
        if (data.eventType() == null) {
            throw new MalformedTransferEventException("transfer event has no eventType");
        }
        if (FUNDS_RESERVED.equals(data.eventType())) {
            screening.screen(toReservedTransfer(message.id(), data));
        }
    }

    private static ReservedTransfer toReservedTransfer(String eventId, TransferEventMessage.Data data) {
        return new ReservedTransfer(
                required(eventId, "id"),
                required(data.transferId(), "transferId"),
                required(data.senderId(), "senderId"),
                required(data.recipientId(), "recipientId"),
                moneyOf(required(data.amount(), "amount"), required(data.currency(), "currency")));
    }

    private static Money moneyOf(BigDecimal amount, String currency) {
        try {
            return Money.of(amount, currency);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new MalformedTransferEventException("FundsReserved event has an invalid amount or currency");
        }
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new MalformedTransferEventException("FundsReserved event has no " + field);
        }
        return value;
    }
}
