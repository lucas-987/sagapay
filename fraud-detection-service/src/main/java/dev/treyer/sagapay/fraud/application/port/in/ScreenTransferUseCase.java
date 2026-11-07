package dev.treyer.sagapay.fraud.application.port.in;

import dev.treyer.sagapay.fraud.domain.ReservedTransfer;

public interface ScreenTransferUseCase {

    void screen(ReservedTransfer transfer);
}
