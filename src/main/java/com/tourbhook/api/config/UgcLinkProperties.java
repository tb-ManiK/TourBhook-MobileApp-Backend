package com.tourbhook.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ugc-links")
public class UgcLinkProperties {

    private int maxSubmissionsPerHour = 10;

    private int maxPageSize = 50;
}