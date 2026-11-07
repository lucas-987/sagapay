package dev.treyer.sagapay.fraud.domain;

import java.math.BigDecimal;

public enum FraudRule {
    VELOCITY {
        @Override
        boolean fires(TransferToScreen transfer, SenderActivity activity, FraudRuleSettings settings) {
            return activity.transfersInWindow() > settings.velocityMaxInWindow();
        }
    },
    NEW_RECIPIENT {
        @Override
        boolean fires(TransferToScreen transfer, SenderActivity activity, FraudRuleSettings settings) {
            return !activity.recentRecipients().contains(transfer.recipientId());
        }
    },
    AMOUNT_ROUND {
        @Override
        boolean fires(TransferToScreen transfer, SenderActivity activity, FraudRuleSettings settings) {
            BigDecimal amount = transfer.amount().amount();
            return amount.compareTo(settings.amountRoundThreshold()) >= 0
                    && amount.remainder(settings.amountRoundStep()).signum() == 0;
        }
    };

    abstract boolean fires(TransferToScreen transfer, SenderActivity activity, FraudRuleSettings settings);
}
