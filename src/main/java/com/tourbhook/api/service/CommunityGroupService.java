package com.tourbhook.api.service;

import com.tourbhook.api.dto.community.CreateGroupRequest;
import com.tourbhook.api.dto.community.GroupMemberResponse;
import com.tourbhook.api.dto.community.GroupResponse;

import java.util.List;

public interface CommunityGroupService {

    GroupResponse createGroup(CreateGroupRequest request);

    List<GroupResponse> discoverGroups(String city, int page, int size);

    List<GroupResponse> getMyGroups();

    GroupResponse getGroup(String groupId);

    GroupResponse joinGroup(String groupId);

    void leaveGroup(String groupId);

    List<GroupMemberResponse> getMembers(String groupId);
}