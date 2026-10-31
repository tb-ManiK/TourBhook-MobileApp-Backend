package com.tourbhook.api.dto.community;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(
        ChatEventType type,
        ChatMessageResponse message,
        GroupMemberResponse member
) {

    public static ChatEvent message(ChatMessageResponse message) {
        return new ChatEvent(ChatEventType.MESSAGE, message, null);
    }

    public static ChatEvent memberJoined(GroupMemberResponse member) {
        return new ChatEvent(ChatEventType.MEMBER_JOINED, null, member);
    }

    public static ChatEvent memberLeft(GroupMemberResponse member) {
        return new ChatEvent(ChatEventType.MEMBER_LEFT, null, member);
    }
}