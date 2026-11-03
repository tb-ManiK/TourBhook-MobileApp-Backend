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
@ConfigurationProperties(prefix = "phone-rules")
public class PhoneRulesProperties {

    public enum Mode {
        OFF,
        MONITOR,
        ENFORCE
    }

    private Mode mode = Mode.OFF;

    private String defaultRegion = "IN";

    private List<String> allowedTypes = new ArrayList<>(List.of("MOBILE", "FIXED_LINE_OR_MOBILE"));

    private boolean checkExistingAccounts = true;
}