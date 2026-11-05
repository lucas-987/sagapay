package dev.treyer.sagapay.fraud.application.service;

import dev.treyer.sagapay.fraud.application.port.in.RecordPostedTransferUseCase;
import dev.treyer.sagapay.fraud.application.port.in.ScreenTransferUseCase;
import dev.treyer.sagapay.fraud.application.port.out.SenderHistoryPort;
import dev.treyer.sagapay.fraud.domain.FraudAssessment;
import dev.treyer.sagapay.fraud.domain.FraudScorer;
import dev.treyer.sagapay.fraud.domain.ReservedTransfer;
import dev.treyer.sagapay.fraud.domain.SenderActivity;
import dev.treyer.sagapay.fraud.domain.TransferToScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Counts the transfer only after the commit, since Redis cannot join the Postgres transaction. A
 * lost count is an accepted under-count: a heuristic moving no money prefers it to counting a
 * rolled-back decision. A failure there is not raised, as deduplication would skip the retry.
 *
 * <p>A recipient joins the history once posted, so that a blocked transfer left to expire does not
 * pass when sent again. Nothing deduplicates that update and it is idempotent: its failure is raised.
 */
@Service
class ScreeningService implements ScreenTransferUseCase, RecordPostedTransferUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(ScreeningService.class);

    private final SenderHistoryPort history;
    private final FraudScorer scorer;
    private final DecisionWriter decisions;

    ScreeningService(SenderHistoryPort history, FraudScorer scorer, DecisionWriter decisions) {
        this.history = history;
        this.scorer = scorer;
        this.decisions = decisions;
    }

    @Override
    public void screen(ReservedTransfer transfer) {
        SenderActivity activity = history.read(transfer.senderId());
        FraudAssessment assessment =
                scorer.assess(new TransferToScreen(transfer.recipientId(), transfer.amount()), activity);
        if (decisions.store(transfer, activity, assessment)) {
            recordInHistory(transfer);
        }
    }

    @Override
    public void recordPosted(UUID senderId, UUID recipientId) {
        history.recordRecipient(senderId, recipientId);
    }

    private void recordInHistory(ReservedTransfer transfer) {
        try {
            history.recordTransfer(transfer.senderId(), transfer.transferId());
        } catch (RuntimeException e) {
            LOG.warn(
                    "sender history update failed after the decision was committed",
                    kv("transfer.id", transfer.transferId()),
                    kv("sender.id", transfer.senderId()),
                    e);
        }
    }
}
