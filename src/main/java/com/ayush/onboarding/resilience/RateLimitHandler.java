package com.ayush.onboarding.resilience;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Handles CRM API rate limiting in two ways:
 *
 * 1. Reactive: parses the Retry-After header from 429 responses and sleeps
 * 2. Proactive: Redis token bucket to pre-throttle requests before hitting the limit
 *
 * The token bucket key is "crm:rate_limit" in Redis with TTL = 1 second.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitHandler {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${rate-limit.crm.capacity:80}")
    private int bucketCapacity;

    @Value("${rate-limit.crm.refill-per-second:10}")
    private int refillPerSecond;

    private static final String RATE_LIMIT_KEY = "crm:rate_limit:tokens";
    private static final String LAST_REFILL_KEY = "crm:rate_limit:last_refill";
    private static final Duration MAX_WAIT = Duration.ofSeconds(60);

    /**
     * Called when CRM returns 429. Parses the Retry-After header and sleeps.
     * Returns the actual wait duration for logging.
     */
    public Duration handleRateLimitResponse(String retryAfterHeader) {
        Duration wait;

        if (retryAfterHeader != null) {
            try {
                long seconds = Long.parseLong(retryAfterHeader.trim());
                wait = Duration.ofSeconds(Math.min(seconds, MAX_WAIT.toSeconds()));
            } catch (NumberFormatException e) {
                // Retry-After can also be an HTTP date — default to 10s for simplicity
                wait = Duration.ofSeconds(10);
                log.debug("Could not parse Retry-After header '{}' — defaulting to 10s", retryAfterHeader);
            }
        } else {
            wait = Duration.ofSeconds(5);  // No header — conservative default
        }

        log.info("Rate limit backoff — sleeping {}ms", wait.toMillis());

        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        return wait;
    }

    /**
     * Proactive token bucket check — call before making a CRM request.
     * Blocks until a token is available (up to MAX_WAIT).
     *
     * Token bucket algorithm:
     * - Capacity: configured max tokens (default 80)
     * - Refill: refillPerSecond tokens every second
     * - If tokens available: consume one and proceed
     * - If empty: wait until next refill, then retry
     */
    public void acquireToken() {
        long deadline = Instant.now().toEpochMilli() + MAX_WAIT.toMillis();

        while (Instant.now().toEpochMilli() < deadline) {
            refillBucket();

            Long tokens = redisTemplate.opsForValue().increment(RATE_LIMIT_KEY, -1);

            if (tokens != null && tokens >= 0) {
                return;  // Token acquired
            }

            // Bucket empty — put the token back and wait for refill
            redisTemplate.opsForValue().increment(RATE_LIMIT_KEY, 1);
            log.debug("Token bucket empty — waiting 100ms");

            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        log.warn("Could not acquire rate limit token after {}s — proceeding anyway", MAX_WAIT.toSeconds());
    }

    private void refillBucket() {
        String lastRefillStr = redisTemplate.opsForValue().get(LAST_REFILL_KEY);
        long now = Instant.now().toEpochMilli();
        long lastRefill = lastRefillStr != null ? Long.parseLong(lastRefillStr) : now;
        long elapsedMs = now - lastRefill;

        if (elapsedMs >= 1000) {
            // Refill bucket
            long tokensToAdd = (elapsedMs / 1000) * refillPerSecond;
            String currentStr = redisTemplate.opsForValue().get(RATE_LIMIT_KEY);
            long current = currentStr != null ? Long.parseLong(currentStr) : 0;
            long newLevel = Math.min(current + tokensToAdd, bucketCapacity);
            redisTemplate.opsForValue().set(RATE_LIMIT_KEY, String.valueOf(newLevel));
            redisTemplate.opsForValue().set(LAST_REFILL_KEY, String.valueOf(now));
        }
    }
}
