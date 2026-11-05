package dev.treyer.sagapay.fraud.adapter.out.redis;

import dev.treyer.sagapay.fraud.application.port.out.SenderHistoryPort;
import dev.treyer.sagapay.fraud.config.FraudRulesProperties;
import dev.treyer.sagapay.fraud.domain.SenderActivity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
class RedisSenderHistoryAdapter implements SenderHistoryPort {

    // Fixed by the rule itself, velocity over one hour.
    private static final Duration VELOCITY_WINDOW = Duration.ofHours(1);

    // A sorted set scored by time, so that the count covers exactly the last hour: a counter whose
    // expiry restarts on each transfer never falls back for a regular sender. Keyed by transfer, so
    // that counting one twice changes nothing. One script so that a crash cannot leave a key
    // without its expiry.
    private static final RedisScript<Long> RECORD_TRANSFER = new DefaultRedisScript<>("""
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    private static final RedisScript<Long> RECORD_RECIPIENT = new DefaultRedisScript<>("""
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final long recipientMemorySeconds;

    @Autowired
    RedisSenderHistoryAdapter(StringRedisTemplate redis, Clock clock, FraudRulesProperties properties) {
        this(redis, clock, properties.newRecipient().memorySeconds());
    }

    RedisSenderHistoryAdapter(StringRedisTemplate redis, Clock clock, long recipientMemorySeconds) {
        this.redis = redis;
        this.clock = clock;
        this.recipientMemorySeconds = recipientMemorySeconds;
    }

    @Override
    public SenderActivity read(UUID senderId) {
        long now = clock.millis();
        Long count = redis.opsForZSet().count(velocityKey(senderId), now - VELOCITY_WINDOW.toMillis(), now);
        Set<String> recipients = redis.opsForSet().members(recipientsKey(senderId));
        return new SenderActivity(
                count == null ? 0 : count.intValue(),
                recipients.stream().map(UUID::fromString).collect(Collectors.toSet()));
    }

    @Override
    public void recordTransfer(UUID senderId, UUID transferId) {
        long now = clock.millis();
        redis.execute(
                RECORD_TRANSFER,
                List.of(velocityKey(senderId)),
                String.valueOf(now),
                transferId.toString(),
                String.valueOf(now - VELOCITY_WINDOW.toMillis()),
                String.valueOf(VELOCITY_WINDOW.toSeconds()));
    }

    @Override
    public void recordRecipient(UUID senderId, UUID recipientId) {
        redis.execute(
                RECORD_RECIPIENT,
                List.of(recipientsKey(senderId)),
                recipientId.toString(),
                String.valueOf(recipientMemorySeconds));
    }

    private static String velocityKey(UUID senderId) {
        return "vel:" + senderId + ":1h";
    }

    private static String recipientsKey(UUID senderId) {
        return "recipients:" + senderId;
    }
}
