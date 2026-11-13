package dev.treyer.sagapay.orchestrator.contract;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransferServiceContractTest {

    private static final String DOCUMENT = "openapi/transfer-service.openapi.yaml";

    @Test
    void sendingATransferDeclaresNo409() {
        assertThat(sendResponses().keySet()).containsExactly("202", "422", "503");
    }

    @Test
    void sendingATransferListsTheCodesOfItsRefusals() {
        assertThat(sendResponses().get("422"))
                .isEqualTo(Map.of(
                        "description",
                        "Refused: INSUFFICIENT_FUNDS, RECIPIENT_NOT_FOUND, IDEMPOTENCY_KEY_REUSED",
                        "content",
                        Map.of(
                                "application/problem+json",
                                Map.of("schema", Map.of("$ref", "#/components/schemas/Problem")))));
    }

    @Test
    void failureReasonIsDescribedAsGivenToTheSenderOnly() {
        assertThat(property(schema("Transfer"), "failureReason").get("description"))
                .isEqualTo("Code of the failure, given to the sender only: null for the recipient.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void stepsAreDescribedAsGivenToTheSenderOnly() {
        Map<String, Object> detailOwnProperties =
                ((List<Map<String, Object>>) schema("TransferDetail").get("allOf")).get(1);

        assertThat(property(detailOwnProperties, "steps").get("description"))
                .isEqualTo("Steps of the saga, given to the sender only: empty for the recipient.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) {
        Map<String, Object> components = (Map<String, Object>) load().get("components");
        return (Map<String, Object>) ((Map<String, Object>) components.get("schemas")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String name) {
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sendResponses() {
        Map<String, Object> doc = load();
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        Map<String, Object> transfers = (Map<String, Object>) paths.get("/v1/transfers");
        Map<String, Object> post = (Map<String, Object>) transfers.get("post");
        return (Map<String, Object>) post.get("responses");
    }

    private static Map<String, Object> load() {
        try (InputStream in = TransferServiceContractTest.class.getClassLoader().getResourceAsStream(DOCUMENT)) {
            assertThat(in).as(DOCUMENT).isNotNull();
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
