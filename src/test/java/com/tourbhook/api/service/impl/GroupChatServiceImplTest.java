package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.ChatProperties;
import com.tourbhook.api.dto.community.ChatEvent;
import com.tourbhook.api.dto.community.ChatEventType;
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
import com.tourbhook.api.repository.exception.TooManyRequestsException;
import com.tourbhook.api.service.ContentModerationEnforcer;
import com.tourbhook.api.websocket.ChatEventPublisher;
import com.tourbhook.api.websocket.ChatRateLimiter;
import com.tourbhook.api.websocket.ChatSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroupChatServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private CommunityGroupRepository groupRepository;
    @Mock private GroupMemberRepository memberRepository;
    @Mock private GroupMessageRepository messageRepository;
    @Mock private ContentModerationEnforcer moderationEnforcer;
    @Mock private ChatRateLimiter rateLimiter;
    @Mock private ChatEventPublisher eventPublisher;
    @Mock private ChatSessionRegistry sessionRegistry;

    private GroupChatServiceImpl service;
    private User sender;
    private CommunityGroup group;

    @BeforeEach
    void setUp() {
        service = new GroupChatServiceImpl(userRepository, groupRepository, memberRepository, messageRepository,
                moderationEnforcer, rateLimiter, eventPublisher, sessionRegistry, new ChatProperties());

        sender = User.builder().id("u1").name("Arun").build();
        group = CommunityGroup.builder().id("g1").name("Udaipur Nov").build();

        lenient().when(userRepository.findById("u1")).thenReturn(Optional.of(sender));
        lenient().when(groupRepository.findById("g1")).thenReturn(Optional.of(group));
        lenient().when(memberRepository.existsByGroupIdAndUserId("g1", "u1")).thenReturn(true);
        lenient().when(messageRepository.save(any(GroupMessage.class))).thenAnswer(call -> {
            GroupMessage message = call.getArgument(0);
            message.setId("m1");
            message.setCreatedAt(LocalDateTime.of(2026, 11, 12, 10, 0));
            return message;
        });
    }

    @Test
    void sendMessage_fromMember_storesTrimmedMessageAndPushesItToTheGroup() {
        ChatMessageResponse response = service.sendMessage("u1", "g1", "  Anyone up for sunrise?  ");

        assertThat(response.id()).isEqualTo("m1");
        assertThat(response.content()).isEqualTo("Anyone up for sunrise?");
        assertThat(response.senderName()).isEqualTo("Arun");

        ArgumentCaptor<ChatEvent> event = ArgumentCaptor.forClass(ChatEvent.class);
        verify(eventPublisher).publish(eq("g1"), event.capture());
        assertThat(event.getValue().type()).isEqualTo(ChatEventType.MESSAGE);
        assertThat(event.getValue().message()).isEqualTo(response);
    }

    @Test
    void sendMessage_flaggedContent_blocksTheSenderDisconnectsThemAndStoresNothing() {
        doThrow(new ContentModerationException("blocked")).when(moderationEnforcer).enforce(eq(sender), eq("pure scam"));

        assertThatThrownBy(() -> service.sendMessage("u1", "g1", "pure scam"))
                .isInstanceOf(ContentModerationException.class);

        verify(sessionRegistry).disconnectUser("u1");
        verify(messageRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any(), any());
    }

    @Test
    void sendMessage_byNonMember_isForbiddenAndNeverReachesModeration() {
        when(memberRepository.existsByGroupIdAndUserId("g1", "u1")).thenReturn(false);

        assertThatThrownBy(() -> service.sendMessage("u1", "g1", "hello"))
                .isInstanceOf(ForbiddenOperationException.class);

        verifyNoInteractions(moderationEnforcer);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void sendMessage_byBlockedUser_isForbidden() {
        sender.setBlocked(true);

        assertThatThrownBy(() -> service.sendMessage("u1", "g1", "hello"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("permanently blocked");

        verify(messageRepository, never()).save(any());
    }

    @Test
    void sendMessage_whenRateLimited_failsBeforeTouchingTheDatabase() {
        doThrow(new TooManyRequestsException("slow down")).when(rateLimiter).check("u1");

        assertThatThrownBy(() -> service.sendMessage("u1", "g1", "hello"))
                .isInstanceOf(TooManyRequestsException.class);

        verifyNoInteractions(userRepository, groupRepository, messageRepository);
    }

    @Test
    void sendMessage_emptyOrOversizedContent_isRejected() {
        assertThatThrownBy(() -> service.sendMessage("u1", "g1", "   "))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.sendMessage("u1", "g1", "x".repeat(2001)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2000");

        verify(messageRepository, never()).save(any());
    }

    @Test
    void getMessages_byNonMember_isForbidden() {
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(memberRepository.existsByGroupIdAndUserId("g1", "u1")).thenReturn(false);

        assertThatThrownBy(() -> service.getMessages("u1", "g1", null, null))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void getMessages_withACursorFromAnotherGroup_isRejected() {
        CommunityGroup other = CommunityGroup.builder().id("g2").build();
        GroupMessage foreign = GroupMessage.builder().id("m9").group(other).build();
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(messageRepository.findById("m9")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getMessages("u1", "g1", "m9", 10))
                .isInstanceOf(BadRequestException.class);
    }
}