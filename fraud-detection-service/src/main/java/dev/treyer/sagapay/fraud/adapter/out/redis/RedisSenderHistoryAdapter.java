package dev.treyer.sagapay.fraud.adapter.out.redis;

import dev.treyer.sagapay.fraud.application.port.out.SenderHistoryPort;
import dev.treyer.sagapay.fraud.domain.SenderActivity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
class RedisSenderHistoryAdapter implements SenderHistoryPort {

    private static final long COUNTER_TTL_SECONDS = 3600;
    private static final long RECIPIENTS_TTL_SECONDS = 30L * 24 * 3600;

    // One script so that a crash cannot leave a counter without its expiry.
    private static final RedisScript<Long> RECORD = new DefaultRedisScript<>("""
            redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], ARGV[1])
            redis.call('SADD', KEYS[2], ARGV[3])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    RedisSenderHistoryAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public SenderActivity read(UUID senderId) {
        String counter = redis.opsForValue().get(counterKey(senderId));
        Set<String> recipients = redis.opsForSet().members(recipientsKey(senderId));
        return new SenderActivity(
                counter == null ? 0 : Integer.parseInt(counter),
                recipients.stream().map(UUID::fromString).collect(Collectors.toSet()));
    }

    @Override
    public void record(UUID senderId, UUID recipientId) {
        redis.execute(
                RECORD,
                List.of(counterKey(senderId), recipientsKey(senderId)),
                String.valueOf(COUNTER_TTL_SECONDS),
                String.valueOf(RECIPIENTS_TTL_SECONDS),
                recipientId.toString());
    }

    private static String counterKey(UUID senderId) {
        return "vel:" + senderId + ":1h";
    }

    private static String recipientsKey(UUID senderId) {
        return "recipients:" + senderId;
    }
}
