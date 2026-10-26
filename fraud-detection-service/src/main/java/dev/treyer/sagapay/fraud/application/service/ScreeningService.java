package dev.treyer.sagapay.fraud.application.service;

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

/**
 * Reads the history, decides, commits, and only then updates the history: a crash between
 * the commit and the update under-counts one transfer, which is the safe side. A history
 * update that fails after the commit is logged and not raised: a retry would meet the
 * deduplication and skip the event, so it could not repair the history, only cost a pause.
 */
@Service
class ScreeningService implements ScreenTransferUseCase {

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

    private void recordInHistory(ReservedTransfer transfer) {
        try {
            history.record(transfer.senderId(), transfer.recipientId());
        } catch (RuntimeException e) {
            LOG.warn(
                    "History update failed after the decision of transfer {} was committed;"
                            + " the sender's history misses this transfer",
                    transfer.transferId(),
                    e);
        }
    }
}
