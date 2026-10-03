package dev.treyer.sagapay.orchestrator;

import org.springframework.boot.SpringApplication;

public class TestTransferOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.from(TransferOrchestratorApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
