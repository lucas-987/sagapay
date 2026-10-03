package dev.treyer.sagapay.ledger.domain;

import java.util.List;

/** Postings store no currency: it is the account's, shared by the whole page.
 * {@code next} is {@code null} on the last page. */
public record PostingPage(List<Posting> items, String currency, PostingCursor next) {}
