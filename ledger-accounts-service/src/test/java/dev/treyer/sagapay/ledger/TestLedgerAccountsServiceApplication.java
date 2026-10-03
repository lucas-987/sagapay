package dev.treyer.sagapay.ledger;

import org.springframework.boot.SpringApplication;

public class TestLedgerAccountsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(LedgerAccountsServiceApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
