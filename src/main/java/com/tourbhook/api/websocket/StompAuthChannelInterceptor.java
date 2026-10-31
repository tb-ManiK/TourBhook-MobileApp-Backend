package com.tourbhook.api.websocket;

import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.GroupMemberRepository;
import com.tourbhook.api.repository.UserRepository;
import com.tourbhook.api.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import java.security.Principal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USER_QUEUE_PREFIX = "/user/queue/";
    private static final Pattern GROUP_TOPIC = Pattern.compile("^/topic/groups/([^/]+)$");

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ChatSessionRegistry chatSessionRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        StompCommand command = accessor == null ? null : accessor.getCommand();
        if (command == null) {
            return message;
        }

        switch (command) {
            case CONNECT -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscription(accessor);
            case SEND -> requireAuthenticatedUser(accessor);
            default -> {
                // heartbeats, ACK, DISCONNECT and so on need no checks
            }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("Missing bearer token");
        }
        String token = header.substring(BEARER_PREFIX.length());
        if (!jwtService.isTokenValid(token) || jwtService.isRefreshToken(token)) {
            throw new MessagingException("Invalid or expired token");
        }
        String userId = jwtService.extractSubject(token);
        if (userId == null) {
            throw new MessagingException("Invalid or expired token");
        }

        User user = userRepository.findById(userId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new MessagingException("Unknown user"));
        if (user.isBlocked()) {
            throw new MessagingException("This account has been permanently blocked");
        }

        accessor.setUser(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        chatSessionRegistry.bindUser(accessor.getSessionId(), userId);
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String userId = requireAuthenticatedUser(accessor);
        String destination = accessor.getDestination();
        if (destination == null) {
            throw new MessagingException("Subscription needs a destination");
        }
        if (destination.startsWith(USER_QUEUE_PREFIX)) {
            return;
        }

        Matcher groupTopic = GROUP_TOPIC.matcher(destination);
        if (!groupTopic.matches()) {
            throw new MessagingException("Subscription not allowed");
        }
        boolean blocked = userRepository.findById(userId).map(User::isBlocked).orElse(true);
        if (blocked) {
            throw new MessagingException("This account has been permanently blocked");
        }
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupTopic.group(1), userId)) {
            throw new MessagingException("You are not a member of this group");
        }
    }

    private String requireAuthenticatedUser(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user == null) {
            throw new MessagingException("Not authenticated");
        }
        return user.getName();
    }
}