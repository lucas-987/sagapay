package dev.treyer.sagapay.ledger.adapter.in.seed;

import dev.treyer.sagapay.ledger.application.port.out.AccountPort;
import dev.treyer.sagapay.ledger.domain.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Populates {@code accounts} with demo data — active only under the {@code local}
 * profile, never by default.
 *
 * <p>A {@code CommandLineRunner @Profile("local")} rather than a REST endpoint
 * (e.g. {@code POST /internal/seed}): an endpoint stays reachable over the
 * network as long as nobody removes/protects it, whereas a {@code
 * @Profile("local")} bean doesn't exist in the Spring context at all when that
 * profile isn't active.
 *
 * <p>Calls {@link AccountPort} directly rather than an {@code in} port: this
 * isn't a business use case ({@code ledger.proto} has no {@code CreateAccount}
 * RPC), just bootstrap.
 */
@Component
@Profile("local")
class LocalAccountSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalAccountSeeder.class);

    /** Varied balances on purpose: a few near zero to trigger {@code
     * InsufficientFunds} on a modest reservation, and a handful of non-EUR
     * accounts to exercise the {@code requireSameCurrency} guard via a
     * cross-currency call. */
    private static final List<SeedAccount> ACCOUNTS = List.of(
            new SeedAccount("alice", "Alice Martin", "EUR", "0.00"),
            new SeedAccount("bob", "Bob Dupont", "EUR", "4.50"),
            new SeedAccount("chloe", "Chloé Bernard", "EUR", "12.00"),
            new SeedAccount("david", "David Petit", "EUR", "87.30"),
            new SeedAccount("emma", "Emma Robert", "EUR", "250.00"),
            new SeedAccount("piotr", "Piotr Kowalski", "EUR", "640.75"),
            new SeedAccount("gina", "Gina Rossi", "EUR", "1200.00"),
            new SeedAccount("hugo", "Hugo Leroy", "EUR", "3400.00"),
            new SeedAccount("ines", "Inès Moreau", "EUR", "8900.00"),
            new SeedAccount("julien", "Julien Simon", "EUR", "15000.00"),
            new SeedAccount("wojciech", "Wojciech Nowak", "EUR", "42000.00"),
            new SeedAccount("lina", "Lina Fontaine", "EUR", "99999.99"),
            new SeedAccount("marco", "Marco Bianchi", "EUR", "150000.00"),
            new SeedAccount("nora", "Nora Faure", "EUR", "0.05"),
            new SeedAccount("tomasz", "Tomasz Zieliński", "EUR", "310.20"),
            new SeedAccount("priya", "Priya Nair", "USD", "500.00"),
            new SeedAccount("quentin", "Quentin Roy", "USD", "27500.00"),
            new SeedAccount("rosa", "Rosa Alvarez", "USD", "0.00"),
            new SeedAccount("krzysztof", "Krzysztof Wiśniewski", "GBP", "1800.00"),
            new SeedAccount("tara", "Tara Wilson", "GBP", "64200.00")
    );

    private final AccountPort accounts;

    LocalAccountSeeder(AccountPort accounts) {
        this.accounts = accounts;
    }

    @Override
    public void run(String... args) {
        log.info("Seeding {} demo accounts (profile 'local')...", ACCOUNTS.size());
        for (SeedAccount seed : ACCOUNTS) {
            // Skip an already-seeded handle rather than re-insert: the local
            // profile's database persists across restarts (named Docker volume),
            // and `handle` is UNIQUE — replaying the seed would abort the whole
            // service's startup, not just this one account.
            if (accounts.findByHandle(seed.handle()).isPresent()) {
                log.info("  {} already seeded, skipping", seed.handle());
                continue;
            }
            Account account = accounts.create(new Account(
                    seed.handle(), seed.displayName(), seed.currency(), new BigDecimal(seed.balance())));
            log.info("  {} ({}) — {} {} — accountId={}",
                    account.getHandle(), account.getDisplayName(),
                    account.getBalance(), account.getCurrency(), account.getId());
        }
    }

    private record SeedAccount(String handle, String displayName, String currency, String balance) {}
}
