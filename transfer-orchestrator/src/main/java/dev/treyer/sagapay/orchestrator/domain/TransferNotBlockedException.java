package dev.treyer.sagapay.orchestrator.domain;

import java.util.UUID;

/** Every call to {@code confirmBlockedTransfer} in M2 ends up here: no transfer
 * can ever reach {@code BLOCKED} without a fraud branch (M3). A real code path,
 * not dead code — proven by {@code TransferRestAdapterTest}. */
public class TransferNotBlockedException extends RuntimeException {

    public TransferNotBlockedException(UUID transferId) {
        super("transfer " + transferId + " is not BLOCKED");
    }
}
