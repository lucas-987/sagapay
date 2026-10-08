package dev.treyer.sagapay.fraud.domain;

import dev.treyer.sagapay.common.domain.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRuleTest {

    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final FraudRuleSettings SETTINGS = TestSettings.defaults();

    private static TransferToScreen transferOf(String amount) {
        return new TransferToScreen(RECIPIENT, Money.of(amount, "EUR"));
    }

    private static SenderActivity activity(int inLastHour, Set<UUID> recipients) {
        return new SenderActivity(inLastHour, recipients);
    }

    @ParameterizedTest
    @CsvSource({"0,false", "5,false", "6,true", "50,true"})
    void velocityFiresOnlyAboveTheMaximumPerHour(int inLastHour, boolean fires) {
        assertThat(FraudRule.VELOCITY_1H.fires(transferOf("10.00"), activity(inLastHour, Set.of(RECIPIENT)), SETTINGS))
                .isEqualTo(fires);
    }

    @Test
    void newRecipientIsSilentWhenTheRecipientIsInTheRecentSet() {
        assertThat(FraudRule.NEW_RECIPIENT.fires(transferOf("10.00"), activity(0, Set.of(RECIPIENT)), SETTINGS))
                .isFalse();
    }

    @Test
    void newRecipientFiresWhenTheRecipientIsAbsentFromTheRecentSet() {
        assertThat(FraudRule.NEW_RECIPIENT.fires(transferOf("10.00"), activity(0, Set.of(UUID.randomUUID())), SETTINGS))
                .isTrue();
        assertThat(FraudRule.NEW_RECIPIENT.fires(transferOf("10.00"), activity(0, Set.of()), SETTINGS))
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "499.99,false",
        "500.01,false",
        "450.00,false",
        "400.00,false",
        "550.00,false",
        "500.00,true",
        "500.0000,true",
        "600.00,true",
        "10000.00,true"
    })
    void amountRoundFiresOnlyOnAMultipleOf100AtOrAboveTheThreshold(String amount, boolean fires) {
        assertThat(FraudRule.AMOUNT_ROUND.fires(transferOf(amount), activity(0, Set.of(RECIPIENT)), SETTINGS))
                .isEqualTo(fires);
    }

    @Test
    void amountRoundHonoursAnOverriddenThreshold() {
        FraudRuleSettings settings = TestSettings.withAmountRoundThreshold(new BigDecimal("1000"));

        assertThat(FraudRule.AMOUNT_ROUND.fires(transferOf("500.00"), activity(0, Set.of()), settings))
                .isFalse();
        assertThat(FraudRule.AMOUNT_ROUND.fires(transferOf("1000.00"), activity(0, Set.of()), settings))
                .isTrue();
    }
}
