package com.tourbhook.api.websocket;

import com.tourbhook.api.config.ChatProperties;
import com.tourbhook.api.repository.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatRateLimiter {

    private static final int SWEEP_THRESHOLD = 5_000;

    private final Map<String, Deque<Long>> sentAtByUser = new ConcurrentHashMap<>();
    private final int maxMessages;
    private final long windowMillis;
    private final Clock clock;

    @Autowired
    public ChatRateLimiter(ChatProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ChatRateLimiter(ChatProperties properties, Clock clock) {
        this.maxMessages = properties.getRateLimitMessages();
        this.windowMillis = properties.getRateLimitWindowSeconds() * 1000L;
        this.clock = clock;
    }

    public void check(String userId) {
        long now = clock.millis();
        if (sentAtByUser.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }

        Deque<Long> timestamps = sentAtByUser.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMillis) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxMessages) {
                throw new TooManyRequestsException("You are sending messages too quickly. Please slow down.");
            }
            timestamps.addLast(now);
        }
    }

    private void sweep(long now) {
        sentAtByUser.entrySet().removeIf(entry -> {
            Deque<Long> timestamps = entry.getValue();
            synchronized (timestamps) {
                return timestamps.isEmpty() || now - timestamps.peekLast() >= windowMillis;
            }
        });
    }
}