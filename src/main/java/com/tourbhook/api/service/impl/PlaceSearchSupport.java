package com.tourbhook.api.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PlaceSearchSupport {

    private static final double EARTH_RADIUS_KM = 6371.0088;
    private static final double KM_PER_DEGREE_LATITUDE = 111.32;

    private PlaceSearchSupport() {
    }

    private static final Set<String> AREA_TYPES = Set.of(
            "locality", "sublocality", "sublocality level 1", "sublocality level 2",
            "sublocality level 3", "sublocality level 4", "sublocality level 5",
            "neighborhood", "political", "colloquial area", "route", "postal code",
            "administrative area level 1", "administrative area level 2",
            "administrative area level 3", "country");

    static boolean isAreaOnly(String typeOrCategory) {
        return AREA_TYPES.contains(normalize(typeOrCategory).replace('_', ' '));
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    static List<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : normalize(text).split("[^\\p{L}\\p{N}]+")) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        return new ArrayList<>(tokens);
    }

    static List<String> significantTokens(String query, String city) {
        List<String> all = tokenize(query);
        Set<String> cityTokens = new LinkedHashSet<>(tokenize(city));
        List<String> withoutCity = new ArrayList<>();
        for (String token : all) {
            if (!cityTokens.contains(token)) {
                withoutCity.add(token);
            }
        }
        return withoutCity.isEmpty() ? all : withoutCity;
    }

    static boolean matchesAllTokens(List<String> tokens, String... fields) {
        if (tokens.isEmpty()) {
            return false;
        }
        StringBuilder haystack = new StringBuilder();
        for (String field : fields) {
            haystack.append(normalize(field)).append(' ');
        }
        String text = haystack.toString();
        for (String token : tokens) {
            if (!text.contains(token)) {
                return false;
            }
        }
        return true;
    }

    static int relevance(List<String> tokens, String name, String address, String category) {
        if (tokens.isEmpty()) {
            return 0;
        }
        int inName = 0;
        for (String token : tokens) {
            if (tokenInText(token, name)) {
                inName++;
            }
        }
        if (inName == tokens.size()) {
            return 3;
        }

        String everything = name + " " + address + " " + category;
        boolean allSomewhere = true;
        for (String token : tokens) {
            if (!tokenInText(token, everything)) {
                allSomewhere = false;
                break;
            }
        }
        if (allSomewhere) {
            return 2;
        }
        return inName > 0 ? 1 : 0;
    }

    static boolean tokenInText(String token, String text) {
        String normalized = normalize(text);
        if (normalized.contains(token)) {
            return true;
        }
        if (token.length() < 4) {
            return false;
        }
        for (String word : tokenize(normalized)) {
            if (Math.abs(word.length() - token.length()) <= 1 && editDistanceAtMostOne(word, token)) {
                return true;
            }
        }
        return false;
    }

    static boolean editDistanceAtMostOne(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1) {
            return false;
        }
        int i = 0;
        int j = 0;
        boolean usedEdit = false;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (usedEdit) {
                return false;
            }
            usedEdit = true;
            if (la > lb) {
                i++;
            } else if (lb > la) {
                j++;
            } else {
                i++;
                j++;
            }
        }
        return (la - i) + (lb - j) + (usedEdit ? 1 : 0) <= 1;
    }

    static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    static double latitudeDelta(double radiusKm) {
        return radiusKm / KM_PER_DEGREE_LATITUDE;
    }

    static double longitudeDelta(double radiusKm, double latitude) {
        double cos = Math.max(0.01, Math.cos(Math.toRadians(latitude)));
        return radiusKm / (KM_PER_DEGREE_LATITUDE * cos);
    }

    static boolean belongsToCity(
            Double placeLat, Double placeLng,
            Double centerLat, Double centerLng, double radiusKm,
            String city, String storedCity, String address
    ) {
        if (placeLat != null && placeLng != null && centerLat != null && centerLng != null) {
            return distanceKm(centerLat, centerLng, placeLat, placeLng) <= radiusKm;
        }
        String wanted = normalize(city);
        if (wanted.isEmpty()) {
            return true;
        }
        return normalize(storedCity).equals(wanted) || normalize(address).contains(wanted);
    }

    static String buildGoogleQuery(String query, String city) {
        String trimmed = query == null ? "" : query.trim();
        String wanted = normalize(city);
        if (wanted.isEmpty() || normalize(trimmed).contains(wanted)) {
            return trimmed;
        }
        return trimmed + " in " + city.trim();
    }
}