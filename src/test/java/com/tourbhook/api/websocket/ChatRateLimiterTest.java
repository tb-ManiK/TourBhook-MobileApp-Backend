package com.tourbhook.api.websocket;

import com.tourbhook.api.config.ChatProperties;
import com.tourbhook.api.repository.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRateLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final ChatRateLimiter limiter = new ChatRateLimiter(properties(3, 10), clock);

    private static ChatProperties properties(int messages, int windowSeconds) {
        ChatProperties properties = new ChatProperties();
        properties.setRateLimitMessages(messages);
        properties.setRateLimitWindowSeconds(windowSeconds);
        return properties;
    }

    @Test
    void check_allowsUpToTheLimitThenRejects() {
        limiter.check("u1");
        limiter.check("u1");
        limiter.check("u1");

        assertThatThrownBy(() -> limiter.check("u1")).isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void check_allowsAgainOnceTheWindowHasPassed() {
        limiter.check("u1");
        limiter.check("u1");
        limiter.check("u1");

        clock.advance(Duration.ofSeconds(10));

        assertThatCode(() -> limiter.check("u1")).doesNotThrowAnyException();
    }

    @Test
    void check_countsEachUserSeparately() {
        limiter.check("u1");
        limiter.check("u1");
        limiter.check("u1");

        assertThatCode(() -> limiter.check("u2")).doesNotThrowAnyException();
    }

    @Test
    void check_aRejectedAttemptDoesNotExtendTheBlock() {
        limiter.check("u1");
        limiter.check("u1");
        limiter.check("u1");
        clock.advance(Duration.ofSeconds(5));
        assertThatThrownBy(() -> limiter.check("u1")).isInstanceOf(TooManyRequestsException.class);

        clock.advance(Duration.ofSeconds(5)); // the first three are now 10s old

        assertThatCode(() -> limiter.check("u1")).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-12T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}