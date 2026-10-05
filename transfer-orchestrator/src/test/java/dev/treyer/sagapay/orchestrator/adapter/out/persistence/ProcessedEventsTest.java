package dev.treyer.sagapay.orchestrator.adapter.out.persistence;

import dev.treyer.sagapay.orchestrator.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class ProcessedEventsTest {

    private static final String INSERT = "insert into processed_events (event_id, consumer) values (?, ?)";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Rolled back with the test transaction.
    private void actAsRuntimeRole() {
        jdbcTemplate.execute("set local role orchestrator_app");
    }

    @Test
    void runtimeRoleCanInsertAndSelect() {
        actAsRuntimeRole();
        UUID eventId = UUID.randomUUID();

        jdbcTemplate.update(INSERT, eventId, "fraud-verdict");

        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from processed_events where event_id = ? and processed_at is not null",
                        Integer.class,
                        eventId))
                .isEqualTo(1);
    }

    @Test
    void insertingAnExistingEventIdFailsWithADuplicateKey() {
        actAsRuntimeRole();
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update(INSERT, eventId, "fraud-verdict");

        assertThatThrownBy(() -> jdbcTemplate.update(INSERT, eventId, "fraud-verdict"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void runtimeRoleCannotUpdateOrDelete() {
        actAsRuntimeRole();

        assertThatThrownBy(() -> jdbcTemplate.update("delete from processed_events"))
                .rootCause()
                .hasMessageContaining("permission denied");
    }
}
