package com.tourbhook.api.controller;

import com.tourbhook.api.dto.common.ApiResponse;
import com.tourbhook.api.dto.community.CreateGroupRequest;
import com.tourbhook.api.dto.community.GroupMemberResponse;
import com.tourbhook.api.dto.community.GroupResponse;
import com.tourbhook.api.service.CommunityGroupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.util.List;


@RestController
@RequestMapping("/api/v1/community/groups")
@RequiredArgsConstructor
public class CommunityGroupController {

    private final CommunityGroupService groupService;

    @PostMapping
    public ResponseEntity<ApiResponse<GroupResponse>> createGroup(
            @Valid @RequestBody CreateGroupRequest body,
            HttpServletRequest request) {
        return respond(HttpStatus.CREATED, "Group created", groupService.createGroup(body), request);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<GroupResponse>>> discoverGroups(
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return respond(HttpStatus.OK, "Groups fetched", groupService.discoverGroups(city, page, size), request);
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<List<GroupResponse>>> getMyGroups(HttpServletRequest request) {
        return respond(HttpStatus.OK, "Your groups fetched", groupService.getMyGroups(), request);
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<ApiResponse<GroupResponse>> getGroup(
            @PathVariable String groupId,
            HttpServletRequest request) {
        return respond(HttpStatus.OK, "Group fetched", groupService.getGroup(groupId), request);
    }

    @PostMapping("/{groupId}/join")
    public ResponseEntity<ApiResponse<GroupResponse>> joinGroup(
            @PathVariable String groupId,
            HttpServletRequest request) {
        return respond(HttpStatus.OK, "Joined group", groupService.joinGroup(groupId), request);
    }

    @DeleteMapping("/{groupId}/members/me")
    public ResponseEntity<ApiResponse<Void>> leaveGroup(
            @PathVariable String groupId,
            HttpServletRequest request) {
        groupService.leaveGroup(groupId);
        return this.<Void>respond(HttpStatus.OK, "Left group", null, request);
    }

    @GetMapping("/{groupId}/members")
    public ResponseEntity<ApiResponse<List<GroupMemberResponse>>> getMembers(
            @PathVariable String groupId,
            HttpServletRequest request) {
        return respond(HttpStatus.OK, "Members fetched", groupService.getMembers(groupId), request);
    }

    private <T> ResponseEntity<ApiResponse<T>> respond(HttpStatus status, String message, T data,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .path(request.getRequestURI())
                .build());
    }
}