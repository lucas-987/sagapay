package dev.treyer.sagapay.fraud.adapter.in.kafka;

import dev.treyer.sagapay.common.domain.Money;
import dev.treyer.sagapay.common.event.TransferEventType;
import dev.treyer.sagapay.fraud.application.port.in.RecordPostedTransferUseCase;
import dev.treyer.sagapay.fraud.application.port.in.ScreenTransferUseCase;
import dev.treyer.sagapay.fraud.domain.ReservedTransfer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class TransferEventListener {

    private final ScreenTransferUseCase screening;
    private final RecordPostedTransferUseCase postedTransfers;

    public TransferEventListener(ScreenTransferUseCase screening, RecordPostedTransferUseCase postedTransfers) {
        this.screening = screening;
        this.postedTransfers = postedTransfers;
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
        if (TransferEventType.FUNDS_RESERVED.wireName().equals(data.eventType())) {
            screening.screen(toReservedTransfer(message.id(), data));
        } else if (TransferEventType.TRANSFER_POSTED.wireName().equals(data.eventType())) {
            postedTransfers.recordPosted(
                    required(data.senderId(), "senderId"), required(data.recipientId(), "recipientId"));
        }
    }

    private static ReservedTransfer toReservedTransfer(UUID eventId, TransferEventMessage.Data data) {
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
            throw new MalformedTransferEventException("FundsReserved event has an invalid amount or currency", e);
        }
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new MalformedTransferEventException("transfer event has no " + field);
        }
        return value;
    }
}
