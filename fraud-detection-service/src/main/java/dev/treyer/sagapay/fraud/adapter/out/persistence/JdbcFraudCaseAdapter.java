package dev.treyer.sagapay.fraud.adapter.out.persistence;

import dev.treyer.sagapay.fraud.application.port.out.FraudCasePort;
import dev.treyer.sagapay.fraud.domain.FraudCase;
import dev.treyer.sagapay.fraud.domain.FraudRule;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
class JdbcFraudCaseAdapter implements FraudCasePort {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    JdbcFraudCaseAdapter(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void open(FraudCase fraudCase) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("transfersInLastHour", fraudCase.velocitySnapshot().transfersInLastHour());
        snapshot.put(
                "recentRecipientCount",
                fraudCase.velocitySnapshot().recentRecipients().size());
        jdbc.sql("""
                        insert into fraud_cases
                            (transfer_id, sender_id, amount, currency, score, reasons, velocity_snapshot)
                        values
                            (:transferId, :senderId, :amount, :currency, :score, :reasons, cast(:snapshot as jsonb))
                        """)
                .param("transferId", fraudCase.transferId().toString())
                .param("senderId", fraudCase.senderId().toString())
                .param("amount", fraudCase.amount().amount())
                .param("currency", fraudCase.amount().currency().getCurrencyCode())
                .param("score", fraudCase.score())
                .param(
                        "reasons",
                        fraudCase.reasons().stream().map(FraudRule::name).toArray(String[]::new))
                .param("snapshot", jsonMapper.writeValueAsString(snapshot))
                .update();
    }
}
