package dev.treyer.sagapay.fraud;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class FraudRoleGrantsTest {

    private static final String INSERT_CASE = "insert into fraud_cases (transfer_id, sender_id, amount, score, reasons)"
            + " values ('t-1', 's-1', 10, 0.5, array['VELOCITY_1H'])";
    private static final String INSERT_OUTBOX = "insert into outbox (aggregate_type, aggregate_id, event_type, payload)"
            + " values ('FraudCase', 't-1', 'TransferCleared', '{}'::jsonb)";
    private static final String INSERT_PROCESSED = "insert into processed_events (event_id, consumer)"
            + " values ('e-1', 'transfers')";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Rolled back with the test transaction.
    private void actAsRuntimeRole() {
        jdbcTemplate.execute("set local role fraud_app");
    }

    @Test
    void runtimeRoleCannotRunDdl() {
        actAsRuntimeRole();

        assertThatThrownBy(() -> jdbcTemplate.execute("create table intruder (id int)"))
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void runtimeRoleCannotDeleteFraudCases() {
        actAsRuntimeRole();

        assertThatThrownBy(() -> jdbcTemplate.update("delete from fraud_cases"))
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void runtimeRoleCannotUpdateProcessedEvents() {
        actAsRuntimeRole();

        assertThatThrownBy(() -> jdbcTemplate.update("update processed_events set consumer = 'x'"))
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void runtimeRoleCanWriteTheTablesItOwnsAtRuntime() {
        actAsRuntimeRole();

        jdbcTemplate.update(INSERT_CASE);
        jdbcTemplate.update(INSERT_OUTBOX);
        jdbcTemplate.update(INSERT_PROCESSED);

        assertThat(jdbcTemplate.queryForObject("select count(*) from fraud_cases", Integer.class))
                .isEqualTo(1);
    }
}
