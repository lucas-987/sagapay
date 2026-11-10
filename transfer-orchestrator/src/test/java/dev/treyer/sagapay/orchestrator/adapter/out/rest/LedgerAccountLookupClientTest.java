package dev.treyer.sagapay.orchestrator.adapter.out.rest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LedgerAccountLookupClientTest {

    private static final String LOOKUP_URL = "http://ledger.test/v1/users/lookup?handle=ines";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://ledger.test");
    private final MockRestServiceServer ledger =
            MockRestServiceServer.bindTo(builder).build();
    private final LedgerAccountLookupAdapter adapter = new LedgerAccountLookupAdapter(builder.build());

    @AfterEach
    void clearTheCaller() {
        SecurityContextHolder.clearContext();
    }

    private static void callerHoldsToken(String tokenValue) {
        Jwt jwt = Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void theLookupCarriesTheCallersToken() {
        UUID accountId = UUID.randomUUID();
        callerHoldsToken("caller-token");
        ledger.expect(once(), requestTo(LOOKUP_URL))
                .andExpect(header("Authorization", "Bearer caller-token"))
                .andRespond(withSuccess("{\"accountId\": \"%s\"}".formatted(accountId), MediaType.APPLICATION_JSON));

        assertThat(adapter.lookupByHandle("ines")).isEqualTo(Optional.of(accountId));

        ledger.verify();
    }

    @Test
    void aNotFoundAnswerStillGivesNoAccount() {
        callerHoldsToken("caller-token");
        ledger.expect(once(), requestTo(LOOKUP_URL))
                .andExpect(header("Authorization", "Bearer caller-token"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adapter.lookupByHandle("ines")).isEmpty();

        ledger.verify();
    }

    @Test
    void aCallWithNoTokenGoesWithoutAnAuthorizationHeader() {
        UUID accountId = UUID.randomUUID();
        ledger.expect(once(), requestTo(LOOKUP_URL))
                .andExpect(request -> assertThat(request.getHeaders().containsHeader("Authorization"))
                        .isFalse())
                .andRespond(withSuccess("{\"accountId\": \"%s\"}".formatted(accountId), MediaType.APPLICATION_JSON));

        assertThat(adapter.lookupByHandle("ines")).isEqualTo(Optional.of(accountId));

        ledger.verify();
    }
}
