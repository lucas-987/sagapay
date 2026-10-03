package dev.treyer.sagapay.ledger.adapter.in.scheduler;

import dev.treyer.sagapay.ledger.application.port.in.ExpireReservationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Active in every profile: an abandoned reservation is a production concern. */
@Component
public class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final ExpireReservationsUseCase expireReservationsUseCase;

    public ReservationExpirySweeper(ExpireReservationsUseCase expireReservationsUseCase) {
        this.expireReservationsUseCase = expireReservationsUseCase;
    }

    /** {@code fixedDelay}, so a slow sweep cannot overlap with the next one. */
    @Scheduled(fixedDelayString = "${ledger.reservation.expiry-sweep-interval-ms:60000}")
    public void sweep() {
        int expired = expireReservationsUseCase.expireOverdueReservations();
        if (expired > 0) {
            log.info("Expired {} overdue reservation(s)", expired);
        }
    }
}
