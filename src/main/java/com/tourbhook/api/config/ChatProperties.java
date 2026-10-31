package com.tourbhook.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;


@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "chat")
public class ChatProperties {

    private int maxMessageLength = 2000;

    private int defaultMaxMembers = 20;
    private int maxMembersLimit = 100;

    private int historyDefaultPageSize = 30;
    private int historyMaxPageSize = 100;

    private int rateLimitMessages = 10;
    private int rateLimitWindowSeconds = 10;

    private List<String> allowedOriginPatterns = new ArrayList<>(List.of("*"));
}