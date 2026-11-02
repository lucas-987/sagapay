package dev.treyer.sagapay.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.PlaceholderResolutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Starts the application on the shipped {@code application.properties} alone: the test
 * configuration, which sets the code, is left out. */
class VerificationCodeStartupTest {

    @Test
    @Tag("M3-S28")
    @DisplayName("The service does not start without a verification code")
    void theServiceDoesNotStartWithoutAVerificationCode() {
        SpringApplicationBuilder application = new SpringApplicationBuilder(
                        TransferOrchestratorApplication.class, TestcontainersConfiguration.class)
                .web(WebApplicationType.NONE)
                .environment(environmentWithoutTheProcessEnvironment());

        assertThatThrownBy(() -> application.run("--spring.config.location=optional:classpath:/application.properties"))
                .rootCause()
                .isInstanceOf(PlaceholderResolutionException.class)
                .hasMessage("Could not resolve placeholder 'SAGAPAY_FRAUD_VERIFICATION_CODE'"
                        + " in value \"${SAGAPAY_FRAUD_VERIFICATION_CODE}\""
                        + " <-- \"${sagapay.fraud.verification-code}\"");
    }

    /** The variable may be set on the machine running the test: the process environment is left out. */
    private static StandardEnvironment environmentWithoutTheProcessEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return environment;
    }

    @Test
    void theCodeComesFromTheShippedConfigurationWhenTheVariableIsSet() {
        SpringApplicationBuilder application = new SpringApplicationBuilder(
                        TransferOrchestratorApplication.class, TestcontainersConfiguration.class)
                .web(WebApplicationType.NONE);

        try (var context = application.run(
                "--spring.config.location=optional:classpath:/application.properties",
                "--SAGAPAY_FRAUD_VERIFICATION_CODE=from-the-environment")) {
            assertThat(context.getEnvironment().getProperty("sagapay.fraud.verification-code"))
                    .isEqualTo("from-the-environment");
        }
    }
}
