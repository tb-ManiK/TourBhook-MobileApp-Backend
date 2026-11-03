package com.tourbhook.api.service.ugc;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class UgcDestinations {

    private UgcDestinations() {
    }

    public static String key(String destination) {
        String legacy = legacyKey(destination);
        String main = legacy;
        int comma = main.indexOf(',');
        if (comma >= 0) {
            main = main.substring(0, comma);
        }
        // Remove accents on Latin letters only. Other scripts keep their signs, which are part of the word.
        main = Normalizer.normalize(main, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        main = Normalizer.normalize(main, Normalizer.Form.NFC);
        main = main.replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").trim();
        return main.isEmpty() ? legacy : main;
    }

    public static String legacyKey(String destination) {
        String cleaned = destination == null ? "" : destination.trim().replaceAll("\\s+", " ");
        return cleaned.toLowerCase(Locale.ROOT);
    }

    public static Set<String> lookupKeys(String destination) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(key(destination));
        keys.add(legacyKey(destination));
        return keys;
    }

    public static String legacyPrefix(String destination) {
        // % and _ are wildcards in a "like" search, so they are never allowed into the pattern.
        return key(destination).replace("%", "").replace("_", "") + ",%";
    }
}