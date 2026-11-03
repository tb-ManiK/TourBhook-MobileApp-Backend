package com.tourbhook.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "device-limit")
public class DeviceLimitProperties {

    private boolean enabled = false;

    private boolean requireDeviceId = false;

    private int maxAccountsPerDevice = 1;

    private String hashPepper = "";

    private int minIdLength = 8;

    private int maxIdLength = 200;
}