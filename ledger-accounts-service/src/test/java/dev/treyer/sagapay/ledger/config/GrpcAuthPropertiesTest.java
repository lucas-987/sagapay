package dev.treyer.sagapay.ledger.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.validation.FieldError;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the ledger's gRPC API requires of a token beyond being the realm's: the shipped defaults,
 * and the refusal at startup of a blank setting. The defaults test reads the shipped
 * {@code application.properties} on purpose: it proves that file's values.
 */
class GrpcAuthPropertiesTest {

    private static final String PREFIX = "ledger.grpc.auth.";

    @EnableConfigurationProperties(GrpcAuthProperties.class)
    static class Binding {}

    /** The variables may be set on the machine running the test: the process environment is left out. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withInitializer(context -> context.getEnvironment()
                    .getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .withUserConfiguration(Binding.class);

    @Test
    void theShippedDefaultsAreTheLedgerAudienceAndScope() {
        runner.run(context -> assertThat(context.getBean(GrpcAuthProperties.class))
                .isEqualTo(new GrpcAuthProperties("ledger-accounts-service", "ledger:write")));
    }

    @ParameterizedTest(name = "a blank {0} ({2}) stops the startup, naming {1}")
    @CsvSource(
            value = {
                "audience|audience|spaces",
                "scope|scope|spaces",
                "audience|audience|empty",
                "scope|scope|empty",
            },
            delimiter = '|')
    void aBlankSettingStopsTheStartup(String property, String field, String kind) {
        String blank = kind.equals("empty") ? "" : "  ";
        runner.withPropertyValues(PREFIX + property + "=" + blank).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOfSatisfying(
                            BindValidationException.class,
                            e -> assertThat(e.getValidationErrors().getAllErrors().stream()
                                            .map(error -> ((FieldError) error).getField() + ":" + error.getCode())
                                            .toList())
                                    .containsExactly(field + ":NotBlank"));
        });
    }
}
