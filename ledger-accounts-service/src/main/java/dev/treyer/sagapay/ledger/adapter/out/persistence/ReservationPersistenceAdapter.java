package dev.treyer.sagapay.ledger.adapter.out.persistence;

import dev.treyer.sagapay.ledger.application.port.out.ReservationPort;
import dev.treyer.sagapay.ledger.domain.Reservation;
import dev.treyer.sagapay.ledger.domain.ReservationStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
class ReservationPersistenceAdapter implements ReservationPort {

    private final ReservationRepository repository;

    ReservationPersistenceAdapter(ReservationRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Reservation reservation) {
        repository.save(reservation);
    }

    @Override
    public BigDecimal sumAmountByAccountIdAndStatus(UUID accountId, ReservationStatus status) {
        return repository.sumAmountByAccountIdAndStatus(accountId, status);
    }

    @Override
    public int updateStatusByReservationId(
            String transferId, UUID reservationId, ReservationStatus fromStatus, ReservationStatus toStatus) {
        return repository.updateStatusByReservationId(transferId, reservationId, fromStatus, toStatus);
    }

    @Override
    public int consumeIfMatching(
            String transferId,
            UUID accountId,
            BigDecimal amount,
            ReservationStatus fromStatus,
            ReservationStatus toStatus) {
        return repository.consumeIfMatching(transferId, accountId, amount, fromStatus, toStatus);
    }

    @Override
    public int expireOverdue() {
        return repository.expireOverdue(ReservationStatus.ACTIVE, ReservationStatus.EXPIRED);
    }
}
