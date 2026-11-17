package dev.treyer.sagapay.orchestrator.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.PlaceholderResolutionException;

import java.net.URI;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Starts the configuration on the shipped {@code application.properties} alone, with the real
 * placeholder configurer (without it an unresolved placeholder is left as is, and property binding
 * would keep it too). The dependence on the shipped file is on purpose: these tests prove that its
 * placeholders have no fallback and that its defaults are the documented ones.
 */
class LedgerAuthPropertiesTest {

    private static final String SHIPPED_ONLY = "--spring.config.location=optional:classpath:/application.properties";
    // The shipped file imports a local .env, which a developer's machine may hold.
    private static final String NO_ENV_FILE = "--spring.config.import=optional:file:./no-such-file.properties";

    /** The variables may be set on the machine running the test: the process environment is left out. */
    private static SpringApplicationBuilder application() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return new SpringApplicationBuilder(LedgerAuthConfig.class, PropertyPlaceholderAutoConfiguration.class)
                .web(WebApplicationType.NONE)
                .environment(environment);
    }

    @Test
    void theServiceDoesNotStartWithoutAClientId() {
        SpringApplicationBuilder application = application();

        assertThatThrownBy(() -> application.run(SHIPPED_ONLY, NO_ENV_FILE, "--ORCHESTRATOR_CLIENT_SECRET=s3cret"))
                .rootCause()
                .isInstanceOf(PlaceholderResolutionException.class)
                .hasMessage("Could not resolve placeholder 'ORCHESTRATOR_CLIENT_ID'"
                        + " in value \"${ORCHESTRATOR_CLIENT_ID}\" <-- \"${ledger.auth.client-id}\"");
    }

    @Test
    void theServiceDoesNotStartWithoutAClientSecret() {
        SpringApplicationBuilder application = application();

        assertThatThrownBy(() -> application.run(SHIPPED_ONLY, NO_ENV_FILE, "--ORCHESTRATOR_CLIENT_ID=orchestrator"))
                .rootCause()
                .isInstanceOf(PlaceholderResolutionException.class)
                .hasMessage("Could not resolve placeholder 'ORCHESTRATOR_CLIENT_SECRET'"
                        + " in value \"${ORCHESTRATOR_CLIENT_SECRET}\" <-- \"${ledger.auth.client-secret}\"");
    }

    @Test
    void theServiceDoesNotStartWithABlankClientId() {
        SpringApplicationBuilder application = application();

        assertThatThrownBy(() -> application.run(
                        SHIPPED_ONLY, NO_ENV_FILE, "--ORCHESTRATOR_CLIENT_ID= ", "--ORCHESTRATOR_CLIENT_SECRET=s3cret"))
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ledger.auth.client-id must not be blank");
    }

    @Test
    void theServiceDoesNotStartWithABlankClientSecret() {
        SpringApplicationBuilder application = application();

        assertThatThrownBy(() -> application.run(
                        SHIPPED_ONLY,
                        NO_ENV_FILE,
                        "--ORCHESTRATOR_CLIENT_ID=orchestrator",
                        "--ORCHESTRATOR_CLIENT_SECRET="))
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ledger.auth.client-secret must not be blank");
    }

    @Test
    void theCredentialAndTheDefaultsComeFromTheShippedConfiguration() {
        try (ConfigurableApplicationContext context = application()
                .run(
                        SHIPPED_ONLY,
                        NO_ENV_FILE,
                        "--ORCHESTRATOR_CLIENT_ID=orchestrator",
                        "--ORCHESTRATOR_CLIENT_SECRET=s3cret")) {
            assertThat(context.getBean(LedgerAuthProperties.class))
                    .isEqualTo(new LedgerAuthProperties(
                            "orchestrator",
                            "s3cret",
                            URI.create("http://keycloak:8180/realms/sagapay/protocol/openid-connect/token"),
                            30000,
                            2000));
        }
    }

    @Test
    void thePropertiesNeverPrintTheSecret() {
        LedgerAuthProperties properties = new LedgerAuthProperties(
                "transfer-orchestrator", "s3cret", URI.create("http://idp.test/token"), 30000, 2000);

        assertThat(properties)
                .hasToString(
                        "LedgerAuthProperties[clientId=transfer-orchestrator, clientSecret=***, tokenUri=http://idp.test/token]");
    }

    static Stream<Arguments> invalidCredentials() {
        URI uri = URI.create("http://idp.test/token");
        return Stream.of(
                Arguments.of(
                        "no client id",
                        (Supplier<LedgerAuthProperties>) () -> new LedgerAuthProperties(null, "s3cret", uri, 1, 1),
                        "ledger.auth.client-id must not be blank"),
                Arguments.of(
                        "no client secret",
                        (Supplier<LedgerAuthProperties>) () -> new LedgerAuthProperties("id", null, uri, 1, 1),
                        "ledger.auth.client-secret must not be blank"),
                Arguments.of(
                        "no token endpoint",
                        (Supplier<LedgerAuthProperties>) () -> new LedgerAuthProperties("id", "s3cret", null, 1, 1),
                        "ledger.auth.token-uri must be set"),
                Arguments.of(
                        "a refresh margin of zero",
                        (Supplier<LedgerAuthProperties>) () -> new LedgerAuthProperties("id", "s3cret", uri, 0, 1),
                        "ledger.auth.refresh-skew-ms must be positive"),
                Arguments.of(
                        "a request timeout of zero",
                        (Supplier<LedgerAuthProperties>) () -> new LedgerAuthProperties("id", "s3cret", uri, 1, 0),
                        "ledger.auth.request-timeout-ms must be positive"));
    }

    @ParameterizedTest(name = "{0} is refused")
    @MethodSource("invalidCredentials")
    void anIncompleteCredentialSettingIsRefused(
            String ignoredName, Supplier<LedgerAuthProperties> properties, String message) {
        assertThatThrownBy(properties::get)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }
}
