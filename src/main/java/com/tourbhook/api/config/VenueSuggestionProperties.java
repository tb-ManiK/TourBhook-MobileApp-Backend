package com.tourbhook.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;


@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "venue-suggestion")
public class VenueSuggestionProperties {

    private List<String> venueTypes = new ArrayList<>(List.of("restaurant", "cafe"));

    private int defaultRadiusMeters = 1500;
    private int minRadiusMeters = 100;
    private int maxRadiusMeters = 5000;

    private double expansionFactor = 2.0;
    private int maxExpandedRadiusMeters = 10_000;

    private int minReviews = 10;

    private BigDecimal fallbackMinRating = new BigDecimal("4.0");

    private int maxAlternatives = 3;
}