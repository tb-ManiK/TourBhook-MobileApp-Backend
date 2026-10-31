package com.tourbhook.api.service.event;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class IcsParser {

    record IcsEvent(
            String uid,
            String summary,
            String description,
            String location,
            String url,
            Double latitude,
            Double longitude,
            LocalDate startDate,
            LocalTime startTime,
            LocalDate endDate,
            boolean cancelled
    ) {
    }

    private record Property(String value, Map<String, String> params) {
    }

    private IcsParser() {
    }

    static List<IcsEvent> parse(String ics, ZoneId zone) {
        List<IcsEvent> events = new ArrayList<>();
        Map<String, Property> current = null;

        for (String line : unfold(ics).split("\n")) {
            if (line.equalsIgnoreCase("BEGIN:VEVENT")) {
                current = new HashMap<>();
            } else if (line.equalsIgnoreCase("END:VEVENT")) {
                if (current != null) {
                    toEvent(current, zone).ifPresent(events::add);
                }
                current = null;
            } else if (current != null) {
                parseLine(line, current);
            }
        }
        return events;
    }

    private static String unfold(String ics) {
        return ics.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n[ \t]", "");
    }

    private static void parseLine(String line, Map<String, Property> target) {
        int colon = indexOfValueSeparator(line);
        if (colon <= 0) {
            return;
        }
        String[] nameAndParams = line.substring(0, colon).split(";");
        String name = nameAndParams[0].toUpperCase();

        Map<String, String> params = new HashMap<>();
        for (int i = 1; i < nameAndParams.length; i++) {
            int equals = nameAndParams[i].indexOf('=');
            if (equals > 0) {
                params.put(nameAndParams[i].substring(0, equals).toUpperCase(),
                        nameAndParams[i].substring(equals + 1).replace("\"", ""));
            }
        }
        target.putIfAbsent(name, new Property(line.substring(colon + 1), params));
    }

    private static int indexOfValueSeparator(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ':' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    private static Optional<IcsEvent> toEvent(Map<String, Property> properties, ZoneId zone) {
        Property start = properties.get("DTSTART");
        String summary = text(properties.get("SUMMARY"));
        if (start == null || summary == null) {
            return Optional.empty();
        }

        boolean allDay = isDateOnly(start);
        LocalDate startDate;
        LocalTime startTime = null;
        try {
            if (allDay) {
                startDate = parseDate(start.value());
            } else {
                LocalDateTime startLocal = parseDateTime(start.value(), zone);
                startDate = startLocal.toLocalDate();
                startTime = startLocal.toLocalTime();
            }
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }

        LocalDate endDate = endDate(properties.get("DTEND"), allDay, startDate, startTime, zone);

        Double latitude = null;
        Double longitude = null;
        Property geo = properties.get("GEO");
        if (geo != null && geo.value().contains(";")) {
            String[] parts = geo.value().split(";");
            try {
                latitude = Double.parseDouble(parts[0].trim());
                longitude = Double.parseDouble(parts[1].trim());
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                latitude = null;
                longitude = null;
            }
        }

        Property status = properties.get("STATUS");
        return Optional.of(new IcsEvent(
                text(properties.get("UID")),
                summary,
                text(properties.get("DESCRIPTION")),
                text(properties.get("LOCATION")),
                text(properties.get("URL")),
                latitude,
                longitude,
                startDate,
                startTime,
                endDate,
                status != null && status.value().trim().equalsIgnoreCase("CANCELLED")));
    }

    private static LocalDate endDate(Property end, boolean allDay, LocalDate startDate, LocalTime startTime, ZoneId zone) {
        if (end == null) {
            return startDate;
        }
        try {
            LocalDate result;
            if (allDay) {
                result = parseDate(end.value()).minusDays(1); // exclusive end
            } else {
                LocalDateTime endLocal = parseDateTime(end.value(), zone);
                result = endLocal.toLocalDate();
                // An event that ends at exactly midnight did not really happen on that next day.
                if (endLocal.toLocalTime().equals(LocalTime.MIDNIGHT) && result.isAfter(startDate)) {
                    result = result.minusDays(1);
                }
            }
            return result.isBefore(startDate) ? startDate : result;
        } catch (DateTimeException | NumberFormatException e) {
            return startDate;
        }
    }

    private static boolean isDateOnly(Property property) {
        return "DATE".equalsIgnoreCase(property.params().get("VALUE")) || property.value().trim().length() == 8;
    }

    private static LocalDate parseDate(String value) {
        String digits = value.trim();
        return LocalDate.of(Integer.parseInt(digits.substring(0, 4)),
                Integer.parseInt(digits.substring(4, 6)), Integer.parseInt(digits.substring(6, 8)));
    }

    private static LocalDateTime parseDateTime(String value, ZoneId zone) {
        String trimmed = value.trim();
        boolean utc = trimmed.endsWith("Z");
        String digits = utc ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        LocalDateTime dateTime = LocalDateTime.parse(digits, DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss"));
        return utc ? dateTime.atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime() : dateTime;
    }

    private static String text(Property property) {
        if (property == null) {
            return null;
        }
        String unescaped = property.value()
                .replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
                .trim();
        return unescaped.isEmpty() ? null : unescaped;
    }
}