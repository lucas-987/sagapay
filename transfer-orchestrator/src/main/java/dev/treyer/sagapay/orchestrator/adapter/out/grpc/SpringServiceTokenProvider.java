package dev.treyer.sagapay.orchestrator.adapter.out.grpc;

import dev.treyer.sagapay.orchestrator.config.LedgerAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The service's token from Spring Security's OAuth 2.0 client: the client credentials grant, a
 * token kept until shortly before it expires, the secret sent as HTTP Basic. Calls arrive from
 * executors, schedulers and listeners with no request behind them, which the service-tier
 * manager is made for. Concurrent callers near expiry may each fetch a token of their own: every
 * one is valid, and the ledger client's circuit breaker bounds what an identity provider outage
 * costs. Messages never carry the answer of the identity provider or the secret.
 */
@Component
class SpringServiceTokenProvider implements ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(SpringServiceTokenProvider.class);

    private static final String REGISTRATION_ID = "ledger";
    // The client credentials grant has no user: the name only keys the cached token.
    private static final String PRINCIPAL_NAME = "transfer-orchestrator";
    // The token type that carries the principal requires a key and an authority, whatever their
    // value: the flow only needs the principal's name.
    private static final String PRINCIPAL_KEY = "service";
    private static final String PRINCIPAL_AUTHORITY = "ROLE_SERVICE";

    private final AuthorizedClientServiceOAuth2AuthorizedClientManager manager;
    private final OAuth2AuthorizedClientService clients;

    @Autowired
    SpringServiceTokenProvider(LedgerAuthProperties properties, Clock clock) {
        this(restClient(properties), properties, clock);
    }

    SpringServiceTokenProvider(RestClient restClient, LedgerAuthProperties properties, Clock clock) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(properties.tokenUri().toString())
                .build();
        this.clients = new InMemoryOAuth2AuthorizedClientService(new InMemoryClientRegistrationRepository(registration));
        RestClientClientCredentialsTokenResponseClient tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(restClient);
        ClientCredentialsOAuth2AuthorizedClientProvider provider = new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokenClient);
        provider.setClockSkew(Duration.ofMillis(properties.refreshSkewMs()));
        provider.setClock(clock);
        this.manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                new InMemoryClientRegistrationRepository(registration), clients);
        this.manager.setAuthorizedClientProvider(provider);
    }

    /** The timeout bounds a caller whatever the identity provider does. */
    private static RestClient restClient(LedgerAuthProperties properties) {
        Duration timeout = Duration.ofMillis(properties.requestTimeoutMs());
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        return withTokenConverters(RestClient.builder().requestFactory(requestFactory)).build();
    }

    /**
     * A client of our own replaces the library's, converters included: without these the answer
     * is not read as a token. The library's error handler is left out on purpose, so that a
     * refusal stays an HTTP failure whose body is never turned into a message.
     */
    static RestClient.Builder withTokenConverters(RestClient.Builder builder) {
        return builder.configureMessageConverters(converters -> {
            converters.addCustomConverter(new FormHttpMessageConverter());
            converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
        });
    }

    @Override
    public String token() {
        OAuth2AuthorizedClient client;
        try {
            client = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
                    .principal(new AnonymousAuthenticationToken(
                            PRINCIPAL_KEY, PRINCIPAL_NAME, AuthorityUtils.createAuthorityList(PRINCIPAL_AUTHORITY)))
                    .build());
        } catch (OAuth2AuthorizationException | IllegalArgumentException e) {
            // An answer the library cannot build a token from (a missing field) surfaces as the latter.
            ServiceTokenUnavailableException failure = new ServiceTokenUnavailableException(describe(e));
            log.warn("service token not obtained", kv("failure", failure.getMessage()));
            throw failure;
        }
        if (client == null) {
            throw new ServiceTokenUnavailableException("token endpoint answered without a usable token");
        }
        return client.getAccessToken().getTokenValue();
    }

    @Override
    public void drop() {
        clients.removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL_NAME);
    }

    /** The exception's own text carries what the identity provider answered: only its kind is kept. */
    private static String describe(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException) {
                return "token endpoint unreachable";
            }
            if (cause instanceof RestClientResponseException response) {
                return "token endpoint answered HTTP " + response.getStatusCode().value();
            }
        }
        return "token endpoint answered without a usable token";
    }
}
