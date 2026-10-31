package com.tourbhook.api.service;

import com.tourbhook.api.dto.community.ChatMessageResponse;
import java.util.List;


public interface GroupChatService {

    ChatMessageResponse sendMessage(String senderId, String groupId, String content);

    List<ChatMessageResponse> getMessages(String userId, String groupId, String beforeMessageId, Integer limit);
}