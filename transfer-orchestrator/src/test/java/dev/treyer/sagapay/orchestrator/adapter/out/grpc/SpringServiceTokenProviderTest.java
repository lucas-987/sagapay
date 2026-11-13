package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import dev.treyer.sagapay.orchestrator.config.LedgerAuthProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SpringServiceTokenProviderTest {

    private static final String TOKEN_URL = "http://idp.test/realms/sagapay/protocol/openid-connect/token";
    private static final long SKEW_MS = 30_000;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer idp = MockRestServiceServer.bindTo(
                    SpringServiceTokenProvider.withTokenConverters(builder))
            .build();
    private final AdjustableClock clock = new AdjustableClock();

    private SpringServiceTokenProvider provider(String secret) {
        return new SpringServiceTokenProvider(
                builder.build(),
                new LedgerAuthProperties("transfer-orchestrator", secret, URI.create(TOKEN_URL), SKEW_MS, 2000),
                clock);
    }

    private SpringServiceTokenProvider provider() {
        return provider("s3cret");
    }

    private static String basic(String id, String encodedSecret) {
        return "Basic "
                + Base64.getEncoder().encodeToString((id + ":" + encodedSecret).getBytes(StandardCharsets.UTF_8));
    }

    private static String grant(String token, int expiresIn) {
        return "{\"access_token\":\"%s\",\"expires_in\":%d,\"token_type\":\"Bearer\"}".formatted(token, expiresIn);
    }

    private void idpAnswers(String token, int expiresIn) {
        idp.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(grant(token, expiresIn), MediaType.APPLICATION_JSON));
    }

    @Test
    void theTokenIsFetchedWithTheClientCredentialsGrant() {
        idp.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/x-www-form-urlencoded"))
                .andExpect(header("Authorization", basic("transfer-orchestrator", "s3cret")))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess(grant("t1", 300), MediaType.APPLICATION_JSON));

        assertThat(provider().token()).isEqualTo("t1");

        idp.verify();
    }

    @Test
    void theSecretIsFormEncodedBeforeBasicEncodingSoThatNoCharacterChangesItsMeaning() {
        idp.expect(once(), requestTo(TOKEN_URL))
                .andExpect(header("Authorization", basic("transfer-orchestrator", "p%2Bs%2Fw%3Dd%26x+y")))
                .andRespond(withSuccess(grant("t1", 300), MediaType.APPLICATION_JSON));

        assertThat(provider("p+s/w=d&x y").token()).isEqualTo("t1");

        idp.verify();
    }

    @Test
    void consecutiveCallsReuseOneToken() {
        idpAnswers("t1", 300);
        SpringServiceTokenProvider provider = provider();

        List<String> tokens = List.of(provider.token(), provider.token(), provider.token());

        assertThat(tokens).containsExactly("t1", "t1", "t1");
        idp.verify();
    }

    @Test
    void theTokenIsStillUsedJustBeforeTheRefreshMargin() {
        idpAnswers("t1", 300);
        SpringServiceTokenProvider provider = provider();
        provider.token();

        clock.advance(Duration.ofSeconds(300).minus(Duration.ofMillis(SKEW_MS)).minusSeconds(1));

        assertThat(provider.token()).isEqualTo("t1");
        idp.verify();
    }

    @Test
    void aTokenCloseToExpiryIsRenewedBeforeUse() {
        idpAnswers("t1", 300);
        idpAnswers("t2", 300);
        SpringServiceTokenProvider provider = provider();
        provider.token();

        clock.advance(Duration.ofSeconds(300).minus(Duration.ofMillis(SKEW_MS)).plusSeconds(1));

        assertThat(provider.token()).isEqualTo("t2");
        idp.verify();
    }

    @ParameterizedTest(name = "a token valid for {0} seconds, less than the margin of 30, is never reused")
    @ValueSource(ints = {10, 29})
    void aTokenThatLivesLessThanTheMarginIsNeverReused(int expiresIn) {
        idpAnswers("t1", expiresIn);
        idpAnswers("t2", expiresIn);
        SpringServiceTokenProvider provider = provider();

        assertThat(provider.token()).isEqualTo("t1");
        assertThat(provider.token()).isEqualTo("t2");

        idp.verify();
    }

    @Test
    void aDroppedTokenIsReplacedByANewOne() {
        idpAnswers("t1", 300);
        idpAnswers("t2", 300);
        SpringServiceTokenProvider provider = provider();
        provider.token();

        provider.drop();

        assertThat(provider.token()).isEqualTo("t2");
        idp.verify();
    }

    @Test
    void droppingBeforeAnyTokenWasFetchedChangesNothing() {
        idpAnswers("t1", 300);
        SpringServiceTokenProvider provider = provider();

        provider.drop();

        assertThat(provider.token()).isEqualTo("t1");
        idp.verify();
    }

    @Test
    void anUnreachableIdentityProviderIsAnUnavailableToken() {
        idp.expect(once(), requestTo(TOKEN_URL)).andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> provider().token())
                .isInstanceOf(ServiceTokenUnavailableException.class)
                .hasMessage("token endpoint unreachable");
    }

    @ParameterizedTest(name = "HTTP {0} from the token endpoint is an unavailable token")
    @ValueSource(ints = {400, 401, 503})
    void aRefusalOrAnErrorFromTheIdentityProviderIsAnUnavailableToken(int status) {
        idp.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"unauthorized_client\"}"));

        assertThatThrownBy(() -> provider().token())
                .isInstanceOf(ServiceTokenUnavailableException.class)
                .hasMessage("token endpoint answered HTTP " + status)
                .hasNoCause();
    }

    static Stream<Arguments> unusableAnswers() {
        return Stream.of(
                Arguments.of("no access_token", "{\"token_type\":\"Bearer\",\"expires_in\":300}"),
                Arguments.of(
                        "a blank access_token",
                        "{\"access_token\":\" \",\"token_type\":\"Bearer\",\"expires_in\":300}"),
                Arguments.of("no token_type", "{\"access_token\":\"t1\",\"expires_in\":300}"),
                Arguments.of(
                        "a token_type that is not Bearer",
                        "{\"access_token\":\"t1\",\"token_type\":\"mac\",\"expires_in\":300}"),
                Arguments.of("a body that is not JSON", "<html>login</html>"),
                Arguments.of("an empty body", ""));
    }

    @ParameterizedTest(name = "an answer with {0} is an unavailable token")
    @MethodSource("unusableAnswers")
    void anAnswerWithoutAUsableTokenIsAnUnavailableToken(String ignoredName, String body) {
        idp.expect(once(), requestTo(TOKEN_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider().token())
                .isInstanceOf(ServiceTokenUnavailableException.class)
                .hasMessage("token endpoint answered without a usable token")
                .hasNoCause();
    }

    @Test
    void aFailedFetchIsNotCachedAsATokenSoALaterCallTriesAgain() {
        idp.expect(once(), requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        idpAnswers("t1", 300);
        SpringServiceTokenProvider provider = provider();

        assertThatThrownBy(provider::token).isInstanceOf(ServiceTokenUnavailableException.class);

        assertThat(provider.token()).isEqualTo("t1");
        idp.verify();
    }

    @Test
    void aRefreshThatFailsAfterTheCachedTokenExpiredThrowsInsteadOfReturningTheOldToken() {
        idpAnswers("t1", 300);
        idp.expect(once(), requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        SpringServiceTokenProvider provider = provider();
        assertThat(provider.token()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(301));

        assertThatThrownBy(provider::token)
                .isInstanceOf(ServiceTokenUnavailableException.class)
                .hasMessage("token endpoint answered HTTP 503");

        idp.verify();
    }

    /** A clock the test moves, so that no test waits for a token to age. */
    private static final class AdjustableClock extends Clock {

        // The library stamps a token with the system clock: the test clock starts there.
        private Instant now = Instant.now();

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
