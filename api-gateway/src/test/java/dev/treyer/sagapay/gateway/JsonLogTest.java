package dev.treyer.sagapay.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The properties differ from the other contexts' so that this one starts, and logs, under capture. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.structured.test=json-line")
@ExtendWith(OutputCaptureExtension.class)
class JsonLogTest {

    @Autowired
    private Environment environment;

    @Test
    void theServerStartUpIsLoggedAsOneJsonObjectWithTheServiceFields(CapturedOutput output) {
        List<JsonNode> started = output.getOut()
                .lines()
                .filter(line -> line.startsWith("{"))
                .map(JsonLogTest::parse)
                .filter(line -> line.path("message").asString().startsWith("Netty started on port"))
                .toList();

        assertThat(started).singleElement().satisfies(line -> {
            assertThat(line.path("level").asString()).isEqualTo("INFO");
            assertThat(line.path("logger_name").asString())
                    .isEqualTo("org.springframework.boot.reactor.netty.NettyWebServer");
            assertThat(line.path("service").asString()).isEqualTo("api-gateway");
            assertThat(line.path("version").asString()).isEqualTo(environment.getProperty("service.version"));
            assertThat(line.path("tenant").asString()).isEqualTo("default");
            assertThat(line.path("@timestamp").asString()).endsWith("Z");
        });
    }

    private static JsonNode parse(String line) {
        return JsonMapper.builder().build().readTree(line);
    }
}
