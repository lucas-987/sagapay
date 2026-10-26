package dev.treyer.sagapay.fraud.adapter.out.redis;

import dev.treyer.sagapay.fraud.domain.SenderActivity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RedisSenderHistoryAdapterTest {

    private static final long ONE_HOUR = 3600;
    private static final long THIRTY_DAYS = 30L * 24 * 3600;

    private static GenericContainer<?> redis;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    private final RedisSenderHistoryAdapter adapter = new RedisSenderHistoryAdapter(template);
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
    void readReturnsTheCounterAndTheRecipientsStoredForTheSender() {
        UUID other = UUID.randomUUID();
        template.opsForValue().set("vel:" + sender + ":1h", "3");
        template.opsForSet().add("recipients:" + sender, recipient.toString(), other.toString());

        assertThat(adapter.read(sender)).isEqualTo(new SenderActivity(3, Set.of(recipient, other)));
    }

    @Test
    void recordCountsTheTransferForAnHourAndRemembersTheRecipientForThirtyDays() {
        adapter.record(sender, recipient);

        assertThat(template.opsForValue().get("vel:" + sender + ":1h")).isEqualTo("1");
        assertThat(template.getExpire("vel:" + sender + ":1h")).isBetween(ONE_HOUR - 10, ONE_HOUR);
        assertThat(template.opsForSet().members("recipients:" + sender)).containsExactly(recipient.toString());
        assertThat(template.getExpire("recipients:" + sender)).isBetween(THIRTY_DAYS - 10, THIRTY_DAYS);
    }

    @Test
    void recordingTwoTransfersToTheSameRecipientCountsTwiceAndStoresTheRecipientOnce() {
        adapter.record(sender, recipient);
        adapter.record(sender, recipient);

        assertThat(adapter.read(sender)).isEqualTo(new SenderActivity(2, Set.of(recipient)));
    }

    @Test
    void recordLeavesTheOtherSendersUntouched() {
        UUID otherSender = UUID.randomUUID();

        adapter.record(sender, recipient);

        assertThat(adapter.read(otherSender)).isEqualTo(new SenderActivity(0, Set.of()));
    }
}
