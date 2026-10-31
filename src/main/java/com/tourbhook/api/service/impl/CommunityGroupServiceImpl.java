package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.ChatProperties;
import com.tourbhook.api.dto.community.ChatEvent;
import com.tourbhook.api.dto.community.CreateGroupRequest;
import com.tourbhook.api.dto.community.GroupMemberResponse;
import com.tourbhook.api.dto.community.GroupResponse;
import com.tourbhook.api.entity.CommunityGroup;
import com.tourbhook.api.entity.GroupMember;
import com.tourbhook.api.entity.GroupMemberRole;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.CommunityGroupRepository;
import com.tourbhook.api.repository.GroupMemberRepository;
import com.tourbhook.api.repository.GroupMessageRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ForbiddenOperationException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.CommunityGroupService;
import com.tourbhook.api.service.ContentModerationEnforcer;
import com.tourbhook.api.websocket.ChatEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CommunityGroupServiceImpl implements CommunityGroupService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MIN_GROUP_SIZE = 2;

    private final CommunityGroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupMessageRepository messageRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final ContentModerationEnforcer moderationEnforcer;
    private final ChatEventPublisher eventPublisher;
    private final ChatProperties chatProperties;

    @Override
    public GroupResponse createGroup(CreateGroupRequest request) {
        User user = authenticatedUserService.getCurrentUser();

        String name = request.name().trim();
        String description = blankToNull(request.description());
        String city = request.city().trim();
        validateDates(request.startDate(), request.endDate());
        int maxMembers = resolveMaxMembers(request.maxMembers());

        // Group names and descriptions are user-written content like any other.
        moderationEnforcer.enforce(user, name, description, city);

        CommunityGroup group = groupRepository.save(CommunityGroup.builder()
                .name(name)
                .description(description)
                .city(city)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .maxMembers(maxMembers)
                .build());
        memberRepository.save(GroupMember.builder()
                .group(group)
                .user(user)
                .role(GroupMemberRole.OWNER)
                .build());

        log.info("User {} created community group {} in {}", user.getId(), group.getId(), city);
        return toResponse(group, 1, GroupMemberRole.OWNER);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupResponse> discoverGroups(String city, int page, int size) {
        User user = authenticatedUserService.getCurrentUser();
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

        List<CommunityGroup> groups = (city == null || city.isBlank())
                ? groupRepository.findLatest(pageable)
                : groupRepository.findLatestByCity(city.trim(), pageable);
        return toResponses(groups, user.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupResponse> getMyGroups() {
        User user = authenticatedUserService.getCurrentUser();
        List<CommunityGroup> groups = memberRepository.findAllWithGroupByUser(user.getId()).stream()
                .map(GroupMember::getGroup)
                .toList();
        return toResponses(groups, user.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public GroupResponse getGroup(String groupId) {
        User user = authenticatedUserService.getCurrentUser();
        CommunityGroup group = groupRepository.findById(groupId).orElseThrow(this::groupNotFound);
        return toResponses(List.of(group), user.getId()).get(0);
    }

    @Override
    public GroupResponse joinGroup(String groupId) {
        User user = authenticatedUserService.getCurrentUser();
        // The lock serialises joins, so the seat count below cannot be overtaken by another request.
        CommunityGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow(this::groupNotFound);

        Optional<GroupMember> existing = memberRepository.findByGroupIdAndUserId(groupId, user.getId());
        long memberCount = memberRepository.countByGroupId(groupId);
        if (existing.isPresent()) {
            return toResponse(group, memberCount, existing.get().getRole());
        }
        if (memberCount >= group.getMaxMembers()) {
            throw new BadRequestException("This group is full.");
        }

        GroupMember member = memberRepository.save(GroupMember.builder()
                .group(group)
                .user(user)
                .role(GroupMemberRole.MEMBER)
                .build());
        eventPublisher.publish(groupId, ChatEvent.memberJoined(toMemberResponse(member)));

        log.info("User {} joined community group {}", user.getId(), groupId);
        return toResponse(group, memberCount + 1, GroupMemberRole.MEMBER);
    }

    @Override
    public void leaveGroup(String groupId) {
        User user = authenticatedUserService.getCurrentUser();
        CommunityGroup group = groupRepository.findByIdForUpdate(groupId).orElseThrow(this::groupNotFound);
        GroupMember membership = memberRepository.findByGroupIdAndUserId(groupId, user.getId())
                .orElseThrow(() -> new BadRequestException("You are not a member of this group."));

        GroupMemberResponse leaving = toMemberResponse(membership);
        memberRepository.delete(membership);

        if (membership.getRole() == GroupMemberRole.OWNER) {
            Optional<GroupMember> successor =
                    memberRepository.findFirstByGroupIdAndUserIdNotOrderByCreatedAtAsc(groupId, user.getId());
            if (successor.isEmpty()) {
                deleteGroup(group);
                log.info("Community group {} deleted because its last member left", groupId);
                return;
            }
            // Longest-standing member takes over, so a group never ends up without an owner.
            successor.get().setRole(GroupMemberRole.OWNER);
            log.info("Ownership of community group {} passed to user {}", groupId, successor.get().getUser().getId());
        }

        eventPublisher.publish(groupId, ChatEvent.memberLeft(leaving));
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupMemberResponse> getMembers(String groupId) {
        User user = authenticatedUserService.getCurrentUser();
        if (!groupRepository.existsById(groupId)) {
            throw groupNotFound();
        }
        if (!memberRepository.existsByGroupIdAndUserId(groupId, user.getId())) {
            throw new ForbiddenOperationException("Join the group to see its members.");
        }
        return memberRepository.findAllWithUser(groupId).stream().map(this::toMemberResponse).toList();
    }

    // ------------------------------------------------------------------ helpers

    private void deleteGroup(CommunityGroup group) {
        messageRepository.deleteByGroupId(group.getId());
        memberRepository.flush(); // the membership row must be gone before its group is deleted
        groupRepository.delete(group);
    }

    private void validateDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new BadRequestException("endDate cannot be before startDate.");
        }
    }

    private int resolveMaxMembers(Integer requested) {
        if (requested == null) {
            return chatProperties.getDefaultMaxMembers();
        }
        if (requested < MIN_GROUP_SIZE || requested > chatProperties.getMaxMembersLimit()) {
            throw new BadRequestException("maxMembers must be between " + MIN_GROUP_SIZE
                    + " and " + chatProperties.getMaxMembersLimit() + ".");
        }
        return requested;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private List<GroupResponse> toResponses(List<CommunityGroup> groups, String userId) {
        if (groups.isEmpty()) {
            return List.of();
        }
        List<String> groupIds = groups.stream().map(CommunityGroup::getId).toList();

        Map<String, Long> counts = memberRepository.countByGroupIds(groupIds).stream()
                .collect(Collectors.toMap(
                        GroupMemberRepository.GroupMemberCount::getGroupId,
                        GroupMemberRepository.GroupMemberCount::getTotal));
        Map<String, GroupMemberRole> myRoles = memberRepository.findByUserAndGroupIds(userId, groupIds).stream()
                .collect(Collectors.toMap(member -> member.getGroup().getId(), GroupMember::getRole, (a, b) -> a));

        return groups.stream()
                .map(group -> toResponse(group, counts.getOrDefault(group.getId(), 0L), myRoles.get(group.getId())))
                .toList();
    }

    private GroupResponse toResponse(CommunityGroup group, long memberCount, GroupMemberRole myRole) {
        return new GroupResponse(
                group.getId(),
                group.getName(),
                group.getDescription(),
                group.getCity(),
                group.getStartDate(),
                group.getEndDate(),
                group.getMaxMembers(),
                memberCount,
                myRole,
                group.getCreatedAt());
    }

    private GroupMemberResponse toMemberResponse(GroupMember member) {
        User user = member.getUser();
        return new GroupMemberResponse(
                user.getId(), user.getName(), user.getAvatarUrl(), member.getRole(), member.getCreatedAt());
    }

    private ResourceNotFoundException groupNotFound() {
        return new ResourceNotFoundException("Community group not found");
    }
}