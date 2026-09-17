package dev.treyer.sagapay.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Required for ReservationExpirySweeper's @Scheduled method — not enabled by
// default by spring-boot-starter.
@EnableScheduling
@SpringBootApplication
public class LedgerAccountsServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(LedgerAccountsServiceApplication.class, args);
	}

}
