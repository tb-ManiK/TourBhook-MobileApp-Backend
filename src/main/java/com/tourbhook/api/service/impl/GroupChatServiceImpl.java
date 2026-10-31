package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.ChatProperties;
import com.tourbhook.api.dto.community.ChatEvent;
import com.tourbhook.api.dto.community.ChatMessageResponse;
import com.tourbhook.api.entity.CommunityGroup;
import com.tourbhook.api.entity.GroupMessage;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.CommunityGroupRepository;
import com.tourbhook.api.repository.GroupMemberRepository;
import com.tourbhook.api.repository.GroupMessageRepository;
import com.tourbhook.api.repository.UserRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ContentModerationException;
import com.tourbhook.api.repository.exception.ForbiddenOperationException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.service.ContentModerationEnforcer;
import com.tourbhook.api.service.GroupChatService;
import com.tourbhook.api.websocket.ChatEventPublisher;
import com.tourbhook.api.websocket.ChatRateLimiter;
import com.tourbhook.api.websocket.ChatSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GroupChatServiceImpl implements GroupChatService {

    private final UserRepository userRepository;
    private final CommunityGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupMessageRepository messageRepository;
    private final ContentModerationEnforcer moderationEnforcer;
    private final ChatRateLimiter rateLimiter;
    private final ChatEventPublisher eventPublisher;
    private final ChatSessionRegistry sessionRegistry;
    private final ChatProperties chatProperties;

    @Override
    public ChatMessageResponse sendMessage(String senderId, String groupId, String rawContent) {
        rateLimiter.check(senderId);
        String content = normaliseContent(rawContent);

        User sender = loadActiveUser(senderId);
        CommunityGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Community group not found"));
        requireMembership(groupId, senderId);

        try {
            moderationEnforcer.enforce(sender, content);
        } catch (ContentModerationException e) {
            // The block is already saved. Cut the user's live connections too, so they cannot
            // keep reading the groups they were subscribed to.
            sessionRegistry.disconnectUser(senderId);
            throw e;
        }

        GroupMessage saved = messageRepository.save(GroupMessage.builder()
                .group(group)
                .sender(sender)
                .content(content)
                .build());

        ChatMessageResponse response = toResponse(saved, sender);
        eventPublisher.publish(groupId, ChatEvent.message(response));
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(String userId, String groupId, String beforeMessageId, Integer limit) {
        if (!groupRepository.existsById(groupId)) {
            throw new ResourceNotFoundException("Community group not found");
        }
        requireMembership(groupId, userId);

        Pageable page = PageRequest.of(0, resolveLimit(limit));
        List<GroupMessage> messages;
        if (beforeMessageId == null || beforeMessageId.isBlank()) {
            messages = messageRepository.findLatest(groupId, page);
        } else {
            GroupMessage cursor = messageRepository.findById(beforeMessageId)
                    .filter(message -> message.getGroup().getId().equals(groupId))
                    .orElseThrow(() -> new BadRequestException("Unknown message to page from."));
            messages = messageRepository.findOlderThan(groupId, cursor.getCreatedAt(), cursor.getId(), page);
        }
        return messages.stream().map(message -> toResponse(message, message.getSender())).toList();
    }

    // ------------------------------------------------------------------ helpers

    private String normaliseContent(String rawContent) {
        String content = rawContent == null ? "" : rawContent.trim();
        if (content.isEmpty()) {
            throw new BadRequestException("A message cannot be empty.");
        }
        if (content.length() > chatProperties.getMaxMessageLength()) {
            throw new BadRequestException(
                    "A message can be at most " + chatProperties.getMaxMessageLength() + " characters.");
        }
        return content;
    }

    private User loadActiveUser(String userId) {
        User user = userRepository.findById(userId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.isBlocked()) {
            throw new ForbiddenOperationException("This account has been permanently blocked.");
        }
        return user;
    }

    private void requireMembership(String groupId, String userId) {
        if (!memberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ForbiddenOperationException("You are not a member of this group.");
        }
    }

    private int resolveLimit(Integer requested) {
        if (requested == null) {
            return chatProperties.getHistoryDefaultPageSize();
        }
        return Math.min(Math.max(requested, 1), chatProperties.getHistoryMaxPageSize());
    }

    private ChatMessageResponse toResponse(GroupMessage message, User sender) {
        return new ChatMessageResponse(
                message.getId(),
                message.getGroup().getId(),
                sender.getId(),
                sender.getName(),
                sender.getAvatarUrl(),
                message.getContent(),
                message.getCreatedAt());
    }
}