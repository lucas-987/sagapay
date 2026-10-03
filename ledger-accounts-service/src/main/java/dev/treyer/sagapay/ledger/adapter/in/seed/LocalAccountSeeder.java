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
 * Sample accounts for local runs. A profile-gated bean rather than a seed endpoint:
 * outside the {@code local} profile it does not exist at all, so nothing is reachable
 * over the network.
 */
@Component
@Profile("local")
class LocalAccountSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalAccountSeeder.class);

    /** Some balances near zero and some non-EUR accounts, to exercise insufficient
     * funds and the currency guard. */
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
        log.info("Seeding {} sample accounts (profile 'local')...", ACCOUNTS.size());
        for (SeedAccount seed : ACCOUNTS) {
            // The local database survives restarts and handle is unique:
            // re-inserting would make startup fail.
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
