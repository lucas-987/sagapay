package dev.treyer.sagapay.fraud.adapter.out.messaging;

import dev.treyer.sagapay.common.event.CloudEvent;
import dev.treyer.sagapay.fraud.domain.OutboxMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class FraudResultEvents {

    static final String SOURCE = "fraud-detection-service";

    private FraudResultEvents() {}

    static CloudEvent<JsonNode> of(OutboxMessage message, JsonMapper jsonMapper) {
        return CloudEvent.of(
                message.id(),
                SOURCE,
                message.eventType() + ".v1",
                message.aggregateId(),
                jsonMapper.readTree(message.payload()));
    }
}
