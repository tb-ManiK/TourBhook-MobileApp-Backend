package com.tourbhook.api.websocket;

import com.tourbhook.api.dto.community.ChatEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class ChatEventPublisher {

    static final String GROUP_TOPIC_PREFIX = "/topic/groups/";

    private final SimpMessagingTemplate messagingTemplate;

    public void publish(String groupId, ChatEvent event) {
        Runnable push = () -> messagingTemplate.convertAndSend(GROUP_TOPIC_PREFIX + groupId, event);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push.run();
                }
            });
        } else {
            push.run();
        }
    }
}