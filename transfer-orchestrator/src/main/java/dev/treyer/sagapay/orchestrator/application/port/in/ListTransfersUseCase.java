package dev.treyer.sagapay.orchestrator.application.port.in;

import dev.treyer.sagapay.orchestrator.domain.Transfer;
import dev.treyer.sagapay.orchestrator.domain.TransferCursor;
import dev.treyer.sagapay.orchestrator.domain.TransferStatus;

import java.util.List;
import java.util.UUID;

public interface ListTransfersUseCase {

    enum Direction { SENT, RECEIVED, ALL }

    record Page(List<Transfer> items, TransferCursor next) {}

    Page listTransfers(UUID userId, Direction direction, TransferStatus status, TransferCursor after, int limit);
}
