package com.tourbhook.api.service.event;

import java.util.Locale;

public record SyncArea(String city, double latitude, double longitude) {

    public String key() {
        return city.trim().toLowerCase(Locale.ROOT);
    }
}