package dev.treyer.sagapay.ledger.adapter.in.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The caller's wallet is chosen by the token, so the contract must not let a request name
 * another account.
 */
class LedgerContractTest {

    private static final String LEDGER_DOCUMENT = "/openapi/ledger-accounts-service.openapi.yaml";

    @SuppressWarnings("unchecked")
    private static List<String> parameterNames(String path) throws IOException {
        try (InputStream document = LedgerContractTest.class.getResourceAsStream(LEDGER_DOCUMENT)) {
            Map<String, Object> contract = new Yaml().load(document);
            Map<String, Object> paths = (Map<String, Object>) contract.get("paths");
            Map<String, Object> get = (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("get");
            List<Map<String, Object>> parameters = (List<Map<String, Object>>) get.get("parameters");
            return parameters == null
                    ? List.of()
                    : parameters.stream().map(p -> (String) p.get("name")).toList();
        }
    }

    @Test
    @DisplayName("The wallet operation takes no parameter: the account is the token's")
    void theWalletTakesNoParameter() throws IOException {
        assertThat(parameterNames("/v1/wallet")).isEmpty();
    }

    @Test
    @DisplayName("The postings operation takes paging parameters and no account id")
    void thePostingsTakePagingParametersOnly() throws IOException {
        assertThat(parameterNames("/v1/wallet/postings")).containsExactly("from", "cursor", "limit");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getOperation(String path) throws IOException {
        try (InputStream document = LedgerContractTest.class.getResourceAsStream(LEDGER_DOCUMENT)) {
            Map<String, Object> contract = new Yaml().load(document);
            Map<String, Object> paths = (Map<String, Object>) contract.get("paths");
            return (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("get");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> response(String path, String status) throws IOException {
        Map<String, Object> responses = (Map<String, Object>) getOperation(path).get("responses");
        return (Map<String, Object>) responses.get(status);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> contentTypes(Map<String, Object> response) {
        return ((Map<String, Object>) response.get("content")).keySet();
    }

    @Test
    @DisplayName("The lookup declares its 404 as a problem document")
    void theLookupDeclaresItsNotFound() throws IOException {
        Map<String, Object> notFound = response("/v1/users/lookup", "404");

        assertThat(notFound).isNotNull();
        assertThat(contentTypes(notFound)).containsExactly("application/problem+json");
        assertThat(notFound.get("description")).isEqualTo("no account has this handle (code RECIPIENT_NOT_FOUND)");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("The names read declares its 200, 400 and 401")
    void theNamesReadDeclaresItsAnswers() throws IOException {
        Map<String, Object> responses =
                (Map<String, Object>) getOperation("/v1/users/names").get("responses");

        assertThat(responses.keySet()).containsExactly("200", "400", "401");
        assertThat(contentTypes(response("/v1/users/names", "400"))).containsExactly("application/problem+json");
    }
}
