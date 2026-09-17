package dev.treyer.sagapay.ledger.domain;

import java.util.List;

/** {@code currency} carried separately from the items rather than on each {@link
 * Posting}: the {@code postings} table only stores a raw {@code BigDecimal}, the
 * currency comes from the account, the same for every row in a page (a single
 * account per request). {@code next} is {@code null} when there's no next page. */
public record PostingPage(List<Posting> items, String currency, PostingCursor next) {}
