package com.tourbhook.api.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;


@Slf4j
@Component
public class ChatSessionRegistry {

    private static final long CLOSE_DELAY_MILLIS = 1_000;

    private final Map<String, WebSocketSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<String, String> userIdBySessionId = new ConcurrentHashMap<>();

    public void register(WebSocketSession session) {
        sessionsById.put(session.getId(), session);
    }

    public void bindUser(String sessionId, String userId) {
        userIdBySessionId.put(sessionId, userId);
    }

    public void unregister(String sessionId) {
        sessionsById.remove(sessionId);
        userIdBySessionId.remove(sessionId);
    }

    public void disconnectUser(String userId) {
        List<WebSocketSession> sessions = userIdBySessionId.entrySet().stream()
                .filter(entry -> entry.getValue().equals(userId))
                .map(entry -> sessionsById.get(entry.getKey()))
                .filter(Objects::nonNull)
                .toList();
        if (sessions.isEmpty()) {
            return;
        }
        log.info("Closing {} chat session(s) of blocked user {}", sessions.size(), userId);
        CompletableFuture.runAsync(
                () -> sessions.forEach(this::closeQuietly),
                CompletableFuture.delayedExecutor(CLOSE_DELAY_MILLIS, TimeUnit.MILLISECONDS));
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Account blocked"));
        } catch (IOException e) {
            log.debug("Session {} was already closed: {}", session.getId(), e.getMessage());
        }
    }
}