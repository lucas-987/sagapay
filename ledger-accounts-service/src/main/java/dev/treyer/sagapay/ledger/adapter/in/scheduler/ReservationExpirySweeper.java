package dev.treyer.sagapay.ledger.adapter.in.scheduler;

import dev.treyer.sagapay.ledger.application.port.in.ExpireReservationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Adapter in — triggers {@link ExpireReservationsUseCase} on a clock rather than
 * an RPC/endpoint. Active in every profile, unlike {@code LocalAccountSeeder}: an
 * abandoned reservation is a production concern, not just a dev one.
 */
@Component
public class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final ExpireReservationsUseCase expireReservationsUseCase;

    public ReservationExpirySweeper(ExpireReservationsUseCase expireReservationsUseCase) {
        this.expireReservationsUseCase = expireReservationsUseCase;
    }

    /** {@code fixedDelay}, not {@code fixedRate}: the next run starts from the end
     * of the previous one, so a slow sweep can't overlap with itself. */
    @Scheduled(fixedDelayString = "${ledger.reservation.expiry-sweep-interval-ms:60000}")
    public void sweep() {
        int expired = expireReservationsUseCase.expireOverdueReservations();
        if (expired > 0) {
            log.info("Expired {} overdue reservation(s)", expired);
        }
    }
}
