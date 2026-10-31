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
@ConfigurationProperties(prefix = "events")
public class EventProperties {

    private String zone = "Asia/Kolkata";

    // ---- feed
    private double defaultRadiusKm = 25;
    private double maxRadiusKm = 100;
    private int defaultWindowDays = 7;
    private int maxWindowDays = 90;
    private int maxPageSize = 50;
    private int maxItineraryTrips = 10;

    // ---- sources
    private List<Area> areas = new ArrayList<>();
    private Sync sync = new Sync();
    private Ticketmaster ticketmaster = new Ticketmaster();
    private List<IcsFeed> icsFeeds = new ArrayList<>();

    @Getter
    @Setter
    public static class Area {
        private String city;
        private double latitude;
        private double longitude;
    }

    @Getter
    @Setter
    public static class Sync {
        private boolean enabled = true;

        private String cron = "0 0 */6 * * *";

        private String purgeCron = "0 30 3 * * *";

        private boolean runOnStartup = true;

        private int lookaheadDays = 60;

        private int retentionDays = 7;

        private int maxTripAreas = 25;
    }

    @Getter
    @Setter
    public static class Ticketmaster {
        private boolean enabled = false;
        private String apiKey = "";
        private String baseUrl = "https://app.ticketmaster.com/discovery/v2/events.json";
        private int radiusKm = 50;
        private int pageSize = 100;
        private int maxPages = 3;
    }

    @Getter
    @Setter
    public static class IcsFeed {
        private String city;
        private String url;
        private String category = "Local event";
    }
}