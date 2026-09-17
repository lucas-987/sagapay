package dev.treyer.sagapay.ledger.domain;

/** Direction of a ledger entry — mapped to {@code VARCHAR(8)} via @Enumerated(STRING). */
public enum PostingLeg {
    DEBIT,
    CREDIT
}
