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
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Sample accounts for local runs. A profile-gated bean rather than a seed endpoint:
 * outside the {@code local} profile it does not exist at all, so nothing is reachable
 * over the network.
 */
@Component
@Profile("local")
class LocalAccountSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalAccountSeeder.class);

    /**
     * Each account's id is written out so that the same handle gets the same id on every
     * start: the identity provider's realm gives its users these ids, and a user id is an
     * account id.
     *
     * <p>Some balances near zero and some non-EUR accounts, to exercise insufficient
     * funds and the currency guard.
     */
    private static final List<SeedAccount> ACCOUNTS = List.of(
            new SeedAccount("00000000-0000-4000-a000-000000000001", "alice", "Alice Martin", "EUR", "0.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000002", "bob", "Bob Dupont", "EUR", "4.50"),
            new SeedAccount("00000000-0000-4000-a000-000000000003", "chloe", "Chloé Bernard", "EUR", "12.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000004", "david", "David Petit", "EUR", "87.30"),
            new SeedAccount("00000000-0000-4000-a000-000000000005", "emma", "Emma Robert", "EUR", "250.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000006", "piotr", "Piotr Kowalski", "EUR", "640.75"),
            new SeedAccount("00000000-0000-4000-a000-000000000007", "gina", "Gina Rossi", "EUR", "1200.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000008", "hugo", "Hugo Leroy", "EUR", "3400.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000009", "ines", "Inès Moreau", "EUR", "8900.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000010", "julien", "Julien Simon", "EUR", "15000.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000011", "wojciech", "Wojciech Nowak", "EUR", "42000.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000012", "lina", "Lina Fontaine", "EUR", "99999.99"),
            new SeedAccount("00000000-0000-4000-a000-000000000013", "marco", "Marco Bianchi", "EUR", "150000.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000014", "nora", "Nora Faure", "EUR", "0.05"),
            new SeedAccount("00000000-0000-4000-a000-000000000015", "tomasz", "Tomasz Zieliński", "EUR", "310.20"),
            new SeedAccount("00000000-0000-4000-a000-000000000016", "priya", "Priya Nair", "USD", "500.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000017", "quentin", "Quentin Roy", "USD", "27500.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000018", "rosa", "Rosa Alvarez", "USD", "0.00"),
            new SeedAccount(
                    "00000000-0000-4000-a000-000000000019", "krzysztof", "Krzysztof Wiśniewski", "GBP", "1800.00"),
            new SeedAccount("00000000-0000-4000-a000-000000000020", "tara", "Tara Wilson", "GBP", "64200.00"));

    private final AccountPort accounts;

    LocalAccountSeeder(AccountPort accounts) {
        this.accounts = accounts;
    }

    @Override
    public void run(String... args) {
        log.info("seeding sample accounts", kv("seed.accounts.count", ACCOUNTS.size()));
        for (SeedAccount seed : ACCOUNTS) {
            // The local database survives restarts and handle is unique:
            // re-inserting would make startup fail.
            if (accounts.findByHandle(seed.handle()).isPresent()) {
                log.info("sample account already seeded, skipped", kv("account.handle", seed.handle()));
                continue;
            }
            Account account = accounts.create(new Account(
                    UUID.fromString(seed.id()),
                    seed.handle(),
                    seed.displayName(),
                    seed.currency(),
                    new BigDecimal(seed.balance())));
            log.info(
                    "sample account seeded",
                    kv("account.id", account.getId()),
                    kv("account.handle", account.getHandle()));
        }
    }

    private record SeedAccount(String id, String handle, String displayName, String currency, String balance) {}
}
