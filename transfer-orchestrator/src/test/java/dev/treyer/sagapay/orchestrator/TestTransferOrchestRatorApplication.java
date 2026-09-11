package dev.treyer.sagapay.orchestrator;

import org.springframework.boot.SpringApplication;

public class TestTransferOrchestRatorApplication {

	public static void main(String[] args) {
		SpringApplication.from(TransferOrchestRatorApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
