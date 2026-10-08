package dev.treyer.sagapay.fraud.domain;

import java.math.BigDecimal;

public enum FraudRule {
    VELOCITY_1H {
        @Override
        boolean fires(TransferToScreen transfer, SenderActivity activity, FraudRuleSettings settings) {
            return activity.transfersInLastHour() > settings.velocityMaxPerHour();
        }
    },
    NEW_RECIPIENT {
        @Override
        boolean fires(TransferToScreen transfer, SenderActivity activity, FraudRuleSettings settings) {
            return !activity.recentRecipients().contains(transfer.recipientId());
        }
    },
    AMOUNT_ROUND {
        private static final BigDecimal ROUND_STEP = BigDecimal.valueOf(100);

        @Override
        boolean fires(TransferToScreen transfer, SenderActivity activity, FraudRuleSettings settings) {
            BigDecimal amount = transfer.amount().amount();
            return amount.compareTo(settings.amountRoundThreshold()) >= 0
                    && amount.remainder(ROUND_STEP).signum() == 0;
        }
    };

    abstract boolean fires(TransferToScreen transfer, SenderActivity activity, FraudRuleSettings settings);
}
