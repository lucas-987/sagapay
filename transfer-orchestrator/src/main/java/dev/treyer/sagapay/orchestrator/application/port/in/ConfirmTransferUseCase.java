package dev.treyer.sagapay.orchestrator.application.port.in;

import java.util.UUID;

/** M2 implements only the guard: no transfer can reach {@code BLOCKED} without
 * a fraud branch (M3), so every call ends in {@code TransferNotBlockedException}
 * -- a real, reachable code path (§8.4), not dead code written ahead of a
 * caller. The real resume-after-confirmation logic (relaunch {@code
 * postTransfer}) is M3's, once {@code BLOCKED} is actually reachable. */
public interface ConfirmTransferUseCase {

    void confirmTransfer(UUID transferId, String verificationToken);
}
