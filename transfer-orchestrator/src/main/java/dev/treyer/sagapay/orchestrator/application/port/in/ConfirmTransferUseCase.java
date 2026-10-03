package dev.treyer.sagapay.orchestrator.application.port.in;

import java.util.UUID;

/** Only the guard exists until transfers can be blocked: every call ends in
 * {@code TransferNotBlockedException}. */
public interface ConfirmTransferUseCase {

    void confirmTransfer(UUID transferId, String verificationToken);
}
