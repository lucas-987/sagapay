package dev.treyer.sagapay.orchestrator.adapter.in.kafka;

import dev.treyer.sagapay.orchestrator.application.port.in.ApplyFraudResultUseCase;
import dev.treyer.sagapay.orchestrator.domain.FraudResult;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class FraudResultListener {

    private final ApplyFraudResultUseCase applyFraudResult;
    private final JsonMapper jsonMapper;

    public FraudResultListener(ApplyFraudResultUseCase applyFraudResult, JsonMapper jsonMapper) {
        this.applyFraudResult = applyFraudResult;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = "payments.fraud.results", groupId = "orchestrator-fraud")
    public void onFraudResult(String value) {
        applyFraudResult.applyFraudResult(parse(value));
    }

    private FraudResult parse(String value) {
        FraudResultMessage message;
        try {
            message = jsonMapper.readValue(value, FraudResultMessage.class);
        } catch (JacksonException e) {
            throw new MalformedFraudResultException("fraud result is not readable", e);
        }
        if (message == null || message.id() == null) {
            throw new MalformedFraudResultException("fraud result has no id");
        }
        FraudResultMessage.Data data = message.data();
        if (data == null) {
            throw new MalformedFraudResultException("fraud result has no data");
        }
        return new FraudResult(
                message.id(),
                required(data.transferId(), "transferId"),
                required(data.decision(), "decision"),
                required(data.score(), "score"),
                required(data.reasons(), "reasons"));
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new MalformedFraudResultException("fraud result has no " + field);
        }
        return value;
    }
}
