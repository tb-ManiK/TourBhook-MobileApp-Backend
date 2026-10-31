package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.ChatProperties;
import com.tourbhook.api.dto.community.ChatEvent;
import com.tourbhook.api.dto.community.ChatEventType;
import com.tourbhook.api.dto.community.CreateGroupRequest;
import com.tourbhook.api.dto.community.GroupResponse;
import com.tourbhook.api.entity.CommunityGroup;
import com.tourbhook.api.entity.GroupMember;
import com.tourbhook.api.entity.GroupMemberRole;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.CommunityGroupRepository;
import com.tourbhook.api.repository.GroupMemberRepository;
import com.tourbhook.api.repository.GroupMessageRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ContentModerationException;
import com.tourbhook.api.repository.exception.ForbiddenOperationException;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.ContentModerationEnforcer;
import com.tourbhook.api.websocket.ChatEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityGroupServiceImplTest {

    @Mock private CommunityGroupRepository groupRepository;
    @Mock private GroupMemberRepository memberRepository;
    @Mock private GroupMessageRepository messageRepository;
    @Mock private AuthenticatedUserService authenticatedUserService;
    @Mock private ContentModerationEnforcer moderationEnforcer;
    @Mock private ChatEventPublisher eventPublisher;

    private CommunityGroupServiceImpl service;
    private User user;
    private CommunityGroup group;

    @BeforeEach
    void setUp() {
        service = new CommunityGroupServiceImpl(groupRepository, memberRepository, messageRepository,
                authenticatedUserService, moderationEnforcer, eventPublisher, new ChatProperties());

        user = User.builder().id("u1").name("Arun").build();
        group = CommunityGroup.builder().id("g1").name("Udaipur Nov").city("Udaipur").maxMembers(2).build();

        lenient().when(authenticatedUserService.getCurrentUser()).thenReturn(user);
        lenient().when(groupRepository.save(any(CommunityGroup.class))).thenAnswer(call -> {
            CommunityGroup saved = call.getArgument(0);
            saved.setId("g1");
            return saved;
        });
        lenient().when(memberRepository.save(any(GroupMember.class))).thenAnswer(call -> call.getArgument(0));
    }

    private CreateGroupRequest request(String name, Integer maxMembers, LocalDate start, LocalDate end) {
        return new CreateGroupRequest(name, "  Sunrise treks  ", " Udaipur ", start, end, maxMembers);
    }

    private GroupMember member(String userId, GroupMemberRole role) {
        return GroupMember.builder()
                .group(group)
                .user(User.builder().id(userId).build())
                .role(role)
                .build();
    }

    @Test
    void createGroup_savesTrimmedGroupAndMakesTheCreatorItsOwner() {
        GroupResponse response = service.createGroup(request(" Udaipur Nov ", null, null, null));

        assertThat(response.name()).isEqualTo("Udaipur Nov");
        assertThat(response.city()).isEqualTo("Udaipur");
        assertThat(response.description()).isEqualTo("Sunrise treks");
        assertThat(response.maxMembers()).isEqualTo(20);
        assertThat(response.memberCount()).isEqualTo(1);
        assertThat(response.myRole()).isEqualTo(GroupMemberRole.OWNER);

        ArgumentCaptor<GroupMember> owner = ArgumentCaptor.forClass(GroupMember.class);
        verify(memberRepository).save(owner.capture());
        assertThat(owner.getValue().getRole()).isEqualTo(GroupMemberRole.OWNER);
        assertThat(owner.getValue().getUser()).isSameAs(user);
    }

    @Test
    void createGroup_withFlaggedName_blocksTheCreatorAndSavesNothing() {
        doThrow(new ContentModerationException("blocked")).when(moderationEnforcer)
                .enforce(eq(user), eq("scam trip"), eq("Sunrise treks"), eq("Udaipur"));

        assertThatThrownBy(() -> service.createGroup(request("scam trip", null, null, null)))
                .isInstanceOf(ContentModerationException.class);

        verify(groupRepository, never()).save(any());
        verify(memberRepository, never()).save(any());
    }

    @Test
    void createGroup_rejectsBadSizesAndBackwardsDates() {
        assertThatThrownBy(() -> service.createGroup(request("A", 1, null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.createGroup(request("A", 101, null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.createGroup(
                request("A", 5, LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 12))))
                .isInstanceOf(BadRequestException.class);

        verify(groupRepository, never()).save(any());
    }

    @Test
    void joinGroup_addsAMemberAndTellsTheGroup() {
        when(groupRepository.findByIdForUpdate("g1")).thenReturn(Optional.of(group));
        when(memberRepository.findByGroupIdAndUserId("g1", "u1")).thenReturn(Optional.empty());
        when(memberRepository.countByGroupId("g1")).thenReturn(1L);

        GroupResponse response = service.joinGroup("g1");

        assertThat(response.memberCount()).isEqualTo(2);
        assertThat(response.myRole()).isEqualTo(GroupMemberRole.MEMBER);
        ArgumentCaptor<ChatEvent> event = ArgumentCaptor.forClass(ChatEvent.class);
        verify(eventPublisher).publish(eq("g1"), event.capture());
        assertThat(event.getValue().type()).isEqualTo(ChatEventType.MEMBER_JOINED);
    }

    @Test
    void joinGroup_whenFull_isRejected() {
        when(groupRepository.findByIdForUpdate("g1")).thenReturn(Optional.of(group));
        when(memberRepository.findByGroupIdAndUserId("g1", "u1")).thenReturn(Optional.empty());
        when(memberRepository.countByGroupId("g1")).thenReturn(2L);

        assertThatThrownBy(() -> service.joinGroup("g1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("full");

        verify(memberRepository, never()).save(any());
    }

    @Test
    void joinGroup_whenAlreadyAMember_changesNothingAndSaysNothing() {
        when(groupRepository.findByIdForUpdate("g1")).thenReturn(Optional.of(group));
        when(memberRepository.findByGroupIdAndUserId("g1", "u1"))
                .thenReturn(Optional.of(member("u1", GroupMemberRole.MEMBER)));
        when(memberRepository.countByGroupId("g1")).thenReturn(2L);

        GroupResponse response = service.joinGroup("g1");

        assertThat(response.memberCount()).isEqualTo(2);
        verify(memberRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any(), any());
    }

    @Test
    void leaveGroup_asOwner_passesOwnershipToTheLongestStandingMember() {
        GroupMember owner = member("u1", GroupMemberRole.OWNER);
        GroupMember next = member("u2", GroupMemberRole.MEMBER);
        when(groupRepository.findByIdForUpdate("g1")).thenReturn(Optional.of(group));
        when(memberRepository.findByGroupIdAndUserId("g1", "u1")).thenReturn(Optional.of(owner));
        when(memberRepository.findFirstByGroupIdAndUserIdNotOrderByCreatedAtAsc("g1", "u1"))
                .thenReturn(Optional.of(next));

        service.leaveGroup("g1");

        assertThat(next.getRole()).isEqualTo(GroupMemberRole.OWNER);
        verify(memberRepository).delete(owner);
        verify(groupRepository, never()).delete(any());
        ArgumentCaptor<ChatEvent> event = ArgumentCaptor.forClass(ChatEvent.class);
        verify(eventPublisher).publish(eq("g1"), event.capture());
        assertThat(event.getValue().type()).isEqualTo(ChatEventType.MEMBER_LEFT);
    }

    @Test
    void leaveGroup_asTheLastMember_deletesTheGroupAndItsMessages() {
        GroupMember owner = member("u1", GroupMemberRole.OWNER);
        when(groupRepository.findByIdForUpdate("g1")).thenReturn(Optional.of(group));
        when(memberRepository.findByGroupIdAndUserId("g1", "u1")).thenReturn(Optional.of(owner));
        when(memberRepository.findFirstByGroupIdAndUserIdNotOrderByCreatedAtAsc("g1", "u1"))
                .thenReturn(Optional.empty());

        service.leaveGroup("g1");

        verify(messageRepository).deleteByGroupId("g1");
        verify(groupRepository).delete(group);
        verify(eventPublisher, never()).publish(any(), any());
    }

    @Test
    void leaveGroup_whenNotAMember_isRejected() {
        when(groupRepository.findByIdForUpdate("g1")).thenReturn(Optional.of(group));
        when(memberRepository.findByGroupIdAndUserId("g1", "u1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.leaveGroup("g1")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void getMembers_forNonMember_isForbidden() {
        when(groupRepository.existsById("g1")).thenReturn(true);
        when(memberRepository.existsByGroupIdAndUserId("g1", "u1")).thenReturn(false);

        assertThatThrownBy(() -> service.getMembers("g1")).isInstanceOf(ForbiddenOperationException.class);
    }
}