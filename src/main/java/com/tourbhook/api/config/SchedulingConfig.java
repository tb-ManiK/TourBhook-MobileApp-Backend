package com.tourbhook.api.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SchedulingConfig {

    private final EventProperties eventProperties;

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of(eventProperties.getZone()));
    }
}