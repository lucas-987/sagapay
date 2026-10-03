package dev.treyer.sagapay.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerAccountsServiceApplicationTests {

    @Test
    void contextLoads() {}
}
