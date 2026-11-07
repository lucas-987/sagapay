package dev.treyer.sagapay.fraud.adapter.out.redis;

import dev.treyer.sagapay.fraud.domain.SenderActivity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RedisSenderHistoryAdapterTest {

    private static final long ONE_HOUR = 3600;
    private static final Duration WINDOW = Duration.ofSeconds(3600);
    private static final long MEMORY = 1234567;

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    private final MovableClock clock = new MovableClock();
    private final RedisSenderHistoryAdapter adapter = new RedisSenderHistoryAdapter(template, clock, WINDOW, MEMORY);
    private final UUID sender = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);
        redis.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        redis.stop();
    }

    @Test
    void aSenderWithNoHistoryReadsAsNoTransfersAndNoRecipients() {
        assertThat(adapter.read(sender)).isEqualTo(new SenderActivity(0, Set.of()));
    }

    @Test
    void readReturnsTheTransfersOfTheLastHourAndTheRecipientsOfTheSender() {
        UUID other = UUID.randomUUID();
        adapter.recordTransfer(sender, UUID.randomUUID());
        adapter.recordTransfer(sender, UUID.randomUUID());
        adapter.recordRecipient(sender, recipient);
        adapter.recordRecipient(sender, other);

        assertThat(adapter.read(sender)).isEqualTo(new SenderActivity(2, Set.of(recipient, other)));
    }

    @Test
    @Tag("M3-S46")
    @DisplayName("A transfer stops counting one hour after it was made")
    void aRegularSenderOnlyCountsTheLastHour() {
        for (int i = 0; i < 8; i++) {
            adapter.recordTransfer(sender, UUID.randomUUID());
            clock.advance(Duration.ofMinutes(50));
        }

        assertThat(adapter.read(sender).transfersInWindow()).isEqualTo(1);
    }

    @Test
    void aTransferExactlyOneHourOldStillCounts() {
        adapter.recordTransfer(sender, UUID.randomUUID());

        clock.advance(Duration.ofHours(1));
        assertThat(adapter.read(sender).transfersInWindow()).isEqualTo(1);

        clock.advance(Duration.ofMillis(1));
        assertThat(adapter.read(sender).transfersInWindow()).isZero();
    }

    @Test
    void transfersOlderThanTheWindowLeaveTheKey() {
        adapter.recordTransfer(sender, UUID.randomUUID());
        clock.advance(Duration.ofMinutes(61));

        adapter.recordTransfer(sender, UUID.randomUUID());

        assertThat(template.opsForZSet().zCard("vel:" + sender)).isEqualTo(1);
    }

    @Test
    void theSameTransferCountsOnce() {
        UUID transfer = UUID.randomUUID();

        adapter.recordTransfer(sender, transfer);
        adapter.recordTransfer(sender, transfer);

        assertThat(adapter.read(sender).transfersInWindow()).isEqualTo(1);
    }

    @Test
    void theWindowFollowsItsSetting() {
        RedisSenderHistoryAdapter shortWindow =
                new RedisSenderHistoryAdapter(template, clock, Duration.ofMinutes(10), MEMORY);
        shortWindow.recordTransfer(sender, UUID.randomUUID());

        clock.advance(Duration.ofMinutes(10));
        assertThat(shortWindow.read(sender).transfersInWindow()).isEqualTo(1);

        clock.advance(Duration.ofMillis(1));
        assertThat(shortWindow.read(sender).transfersInWindow()).isZero();
        assertThat(template.getExpire("vel:" + sender)).isBetween(590L, 600L);
    }

    @Test
    void theVelocityKeyExpiresWithItsWindowAndTheRecipientsWithTheirMemory() {
        adapter.recordTransfer(sender, UUID.randomUUID());
        adapter.recordRecipient(sender, recipient);

        assertThat(template.getExpire("vel:" + sender)).isBetween(ONE_HOUR - 10, ONE_HOUR);
        assertThat(template.getExpire("recipients:" + sender)).isBetween(MEMORY - 10, MEMORY);
    }

    @Test
    void aRecipientIsStoredOnce() {
        adapter.recordRecipient(sender, recipient);
        adapter.recordRecipient(sender, recipient);

        assertThat(adapter.read(sender)).isEqualTo(new SenderActivity(0, Set.of(recipient)));
    }

    @Test
    void recordingLeavesTheOtherSendersUntouched() {
        UUID otherSender = UUID.randomUUID();

        adapter.recordTransfer(sender, UUID.randomUUID());
        adapter.recordRecipient(sender, recipient);

        assertThat(adapter.read(otherSender)).isEqualTo(new SenderActivity(0, Set.of()));
    }

    /** Lets a test move time forward without waiting. */
    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-03-01T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
