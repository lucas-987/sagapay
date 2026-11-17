package dev.treyer.sagapay.gateway;

import dev.treyer.sagapay.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every endpoint is exposed here, so that what refuses the other ones is the security chain and
 * not their absence.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cloud.gateway.server.webflux.httpclient.response-timeout=" + GatewayTestBase.RESPONSE_TIMEOUT_MILLIS
                    + "ms",
            "management.endpoints.web.exposure.include=*"
        })
class ActuatorTest extends GatewayTestBase {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "/actuator/health|{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}",
                "/actuator/health/liveness|{\"status\":\"UP\"}",
                "/actuator/health/readiness|{\"status\":\"UP\"}"
            })
    void healthAnswersWithNoToken(String path, String body) throws Exception {
        HttpResponse<String> response = get(path, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
    }

    @ParameterizedTest(name = "{0} with no token")
    @ValueSource(
            strings = {
                "/actuator",
                "/actuator/prometheus",
                "/actuator/metrics",
                "/actuator/info",
                "/actuator/env",
                "/actuator/beans",
                "/actuator/loggers",
                "/actuator/configprops",
                "/actuator/gateway/routes"
            })
    void everyOtherActuatorEndpointIsRefusedWithNoToken(String path) throws Exception {
        assertThat(get(path, null).statusCode()).isEqualTo(401);
    }

    @ParameterizedTest(name = "{0} with a valid user token")
    @ValueSource(
            strings = {
                "/actuator",
                "/actuator/prometheus",
                "/actuator/metrics",
                "/actuator/info",
                "/actuator/env",
                "/actuator/beans",
                "/actuator/loggers",
                "/actuator/configprops",
                "/actuator/gateway/routes"
            })
    void everyOtherActuatorEndpointIsRefusedWithAValidUserToken(String path) throws Exception {
        assertThat(get(path, TestTokens.valid()).statusCode()).isEqualTo(403);
    }

    @Test
    void aRefusedActuatorCallReachesNoService() throws Exception {
        get("/actuator/prometheus", TestTokens.valid());

        assertThat(TRANSFERS.received()).isEmpty();
        assertThat(LEDGER.received()).isEmpty();
    }
}
