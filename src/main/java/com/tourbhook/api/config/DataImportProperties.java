package com.tourbhook.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "data-import")
public class DataImportProperties {

    private boolean enabled = true;

    private boolean beta = true;
    private String betaLabel = "Beta";
    private String betaMessage = "Data import is in beta. It can miss or misread details, so please review "
            + "the draft before creating your trip, and tell us what went wrong.";

    private long maxFileSizeBytes = 1024 * 1024;

    private int feedbackDailyLimit = 10;
    private int maxCommentLength = 1000;
}