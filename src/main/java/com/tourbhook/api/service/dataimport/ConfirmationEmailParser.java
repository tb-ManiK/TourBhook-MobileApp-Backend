package com.tourbhook.api.service.dataimport;

import com.tourbhook.api.dto.dataimport.ImportedItemType;
import com.tourbhook.api.entity.ImportSourceType;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.service.dataimport.ImportFieldParser.ParsedDateTime;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ConfirmationEmailParser implements ImportParser {

    private enum Field {
        AIRLINE, FLIGHT_NUMBER, FROM, TO, DEPARTURE, ARRIVAL,
        HOTEL_NAME, CHECK_IN, CHECK_OUT, CITY,
        CONFIRMATION, TOTAL
    }

    private static final Map<String, Field> LABELS = buildLabelMap();

    private static final Pattern LABEL_VALUE =
            Pattern.compile("^\\s*([\\p{L}][\\p{L}\\p{N} /#._()-]{0,40}?)\\s*:\\s*(\\S.*?)\\s*$");

    private static final Pattern MIME_HEADER =
            Pattern.compile("(?im)^(MIME-Version|Content-Type|Message-ID|Received):");

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#([xX][0-9a-fA-F]+|\\d+);");
    private static final int HEADER_SCAN_LIMIT = 4_000;
    private static final int MAX_CONFIRMATION_LENGTH = 40;

    private static final String NOTHING_FOUND_MESSAGE =
            "No flight or hotel booking details were recognised in this email. "
                    + "Supported emails list details as 'Label: value' lines, for example "
                    + "'Flight: 6E 204', 'From: Chennai', 'To: Delhi', 'Departure: 12 Nov 2026 06:30' "
                    + "or 'Hotel: ...', 'Check-in: 12 Nov 2026', 'Check-out: 14 Nov 2026'.";

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.CONFIRMATION_EMAIL;
    }

    @Override
    public ParseResult parse(String content) {
        String text = extractText(content);
        List<String> warnings = new ArrayList<>();

        EnumMap<Field, String> bookingFields = new EnumMap<>(Field.class);
        List<EnumMap<Field, String>> segments = collectSegments(text, bookingFields);
        String confirmationCode = limit(clean(bookingFields.get(Field.CONFIRMATION)), MAX_CONFIRMATION_LENGTH);

        List<ImportedItem> items = new ArrayList<>();
        for (EnumMap<Field, String> segment : segments) {
            addFlight(segment, confirmationCode, items, warnings);
            addHotel(segment, confirmationCode, items, warnings);
        }

        if (items.isEmpty()) {
            throw new BadRequestException(NOTHING_FOUND_MESSAGE);
        }
        applyTotalToFirstItem(bookingFields.get(Field.TOTAL), items, warnings);
        return new ParseResult(items, warnings);
    }

    // ---------------------------------------------------------------- segmentation

    private List<EnumMap<Field, String>> collectSegments(String text, EnumMap<Field, String> bookingFields) {
        List<EnumMap<Field, String>> segments = new ArrayList<>();
        EnumMap<Field, String> current = new EnumMap<>(Field.class);

        for (String line : text.split("\\R")) {
            Matcher matcher = LABEL_VALUE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            Field field = LABELS.get(normaliseLabel(matcher.group(1)));
            if (field == null) {
                continue;
            }
            String value = matcher.group(2);

            if (field == Field.CONFIRMATION || field == Field.TOTAL) {
                bookingFields.putIfAbsent(field, value);
                continue;
            }
            if (current.containsKey(field)) {
                // Same label again: the previous block is complete, this starts the next one.
                segments.add(current);
                current = new EnumMap<>(Field.class);
            }
            current.put(field, value);
        }
        if (!current.isEmpty()) {
            segments.add(current);
        }
        return segments;
    }

    // ---------------------------------------------------------------- flights

    private void addFlight(Map<Field, String> segment, String confirmationCode,
                           List<ImportedItem> items, List<String> warnings) {
        boolean hasFlightData = segment.containsKey(Field.DEPARTURE)
                || segment.containsKey(Field.FLIGHT_NUMBER)
                || (segment.containsKey(Field.FROM) && segment.containsKey(Field.TO));
        if (!hasFlightData) {
            return;
        }

        ParsedDateTime departure = ImportFieldParser.parseDateTime(segment.get(Field.DEPARTURE));
        if (departure.date() == null) {
            warnings.add("Skipped a flight because its departure date could not be read.");
            return;
        }
        ParsedDateTime arrival = ImportFieldParser.parseDateTime(segment.get(Field.ARRIVAL));

        String from = ImportFieldParser.cleanCity(segment.get(Field.FROM));
        String to = ImportFieldParser.cleanCity(segment.get(Field.TO));

        items.add(new ImportedItem(
                ImportedItemType.FLIGHT,
                flightTitle(clean(segment.get(Field.AIRLINE)), clean(segment.get(Field.FLIGHT_NUMBER)), from, to),
                departure.date(),
                departure.time(),
                flightDurationMinutes(departure, arrival),
                BigDecimal.ZERO,
                to,
                confirmationCode));
    }

    private String flightTitle(String airline, String number, String from, String to) {
        String base = (airline != null ? airline : "Flight") + (number != null ? " " + number : "");
        if (from != null && to != null) {
            return base + ": " + from + " to " + to;
        }
        return to != null ? base + " to " + to : base;
    }

    private Integer flightDurationMinutes(ParsedDateTime departure, ParsedDateTime arrival) {
        if (departure.time() == null || arrival.time() == null) {
            return null;
        }
        boolean arrivalHasDate = arrival.date() != null;
        LocalDateTime start = LocalDateTime.of(departure.date(), departure.time());
        LocalDateTime end = LocalDateTime.of(arrivalHasDate ? arrival.date() : departure.date(), arrival.time());

        long minutes = Duration.between(start, end).toMinutes();
        if (minutes < 0 && !arrivalHasDate) {
            minutes += 24 * 60; // landed after midnight and the email only gave a time
        }
        return (minutes >= 15 && minutes <= 24 * 60) ? (int) minutes : null;
    }

    // ---------------------------------------------------------------- hotels

    private void addHotel(Map<Field, String> segment, String confirmationCode,
                          List<ImportedItem> items, List<String> warnings) {
        boolean hasHotelData = segment.containsKey(Field.HOTEL_NAME)
                || segment.containsKey(Field.CHECK_IN)
                || segment.containsKey(Field.CHECK_OUT);
        if (!hasHotelData) {
            return;
        }

        String name = clean(segment.get(Field.HOTEL_NAME));
        String city = ImportFieldParser.cleanCity(segment.get(Field.CITY));
        ParsedDateTime checkIn = ImportFieldParser.parseDateTime(segment.get(Field.CHECK_IN));
        ParsedDateTime checkOut = ImportFieldParser.parseDateTime(segment.get(Field.CHECK_OUT));
        String label = name != null ? name : "your hotel";

        if (checkIn.date() == null && checkOut.date() == null) {
            warnings.add("Skipped " + label + " because no check-in or check-out date could be read.");
            return;
        }
        if (checkIn.date() != null && checkOut.date() != null && checkOut.date().isBefore(checkIn.date())) {
            warnings.add("The check-out date for " + label + " is before its check-in date. Please double-check them.");
        }

        if (checkIn.date() != null) {
            items.add(new ImportedItem(ImportedItemType.HOTEL_CHECK_IN, hotelTitle("check-in", name),
                    checkIn.date(), checkIn.time(), null, BigDecimal.ZERO, city, confirmationCode));
        } else {
            warnings.add("No check-in date found for " + label + "; only the check-out was imported.");
        }
        if (checkOut.date() != null) {
            items.add(new ImportedItem(ImportedItemType.HOTEL_CHECK_OUT, hotelTitle("check-out", name),
                    checkOut.date(), checkOut.time(), null, BigDecimal.ZERO, city, confirmationCode));
        } else {
            warnings.add("No check-out date found for " + label + "; only the check-in was imported.");
        }
    }

    private String hotelTitle(String event, String hotelName) {
        return hotelName != null ? "Hotel " + event + ": " + hotelName : "Hotel " + event;
    }

    // ---------------------------------------------------------------- cost

    private void applyTotalToFirstItem(String rawTotal, List<ImportedItem> items, List<String> warnings) {
        if (rawTotal == null) {
            return;
        }
        BigDecimal cost = ImportFieldParser.parseInrAmount(rawTotal, "booking total", warnings);
        items.set(0, items.get(0).withCost(cost));
    }

    // ---------------------------------------------------------------- email text extraction

    private String extractText(String raw) {
        if (!looksLikeMimeMessage(raw)) {
            return raw;
        }
        try {
            MimeMessage message = new MimeMessage(
                    Session.getInstance(new Properties()),
                    new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
            String text = textOf(message);
            return text.isBlank() ? raw : text;
        } catch (MessagingException | IOException e) {
            log.warn("Could not parse input as a MIME message, falling back to raw text: {}", e.getMessage());
            return raw;
        }
    }

    private boolean looksLikeMimeMessage(String raw) {
        String head = raw.length() > HEADER_SCAN_LIMIT ? raw.substring(0, HEADER_SCAN_LIMIT) : raw;
        return MIME_HEADER.matcher(head).find();
    }

    private String textOf(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        }
        if (part.isMimeType("text/html")) {
            return htmlToText((String) part.getContent());
        }
        if (part.isMimeType("message/rfc822")) {
            return textOf((Part) part.getContent()); // a forwarded message attached as .eml
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            StringBuilder plain = new StringBuilder();
            StringBuilder html = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(child.getDisposition())) {
                    continue;
                }
                String text = textOf(child);
                (child.isMimeType("text/html") ? html : plain).append(text).append('\n');
            }
            return plain.toString().isBlank() ? html.toString() : plain.toString();
        }
        return "";
    }

    private String htmlToText(String html) {
        String text = decodeNumericEntities(html)
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)</t[dh]\\s*>", " : ")
                .replaceAll("(?i)<br\\s*/?>|</(p|div|tr|li|h[1-6]|table)\\s*>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
        return text
                .replaceAll("[ \\t\\u00A0]+", " ")
                .replaceAll(":[ \\t]*:", ":")       // "Check-in: : 12 Nov"  ->  "Check-in: 12 Nov"
                .replaceAll("(?m)[ \\t:]+$", "")    // dangling separator after the last cell of a row
                .trim();
    }

    private static String decodeNumericEntities(String text) {
        Matcher matcher = NUMERIC_ENTITY.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (matcher.find()) {
            String code = matcher.group(1);
            String replacement = " ";
            try {
                int codePoint = (code.charAt(0) == 'x' || code.charAt(0) == 'X')
                        ? Integer.parseInt(code.substring(1), 16)
                        : Integer.parseInt(code);
                if (Character.isValidCodePoint(codePoint)) {
                    replacement = new String(Character.toChars(codePoint));
                }
            } catch (NumberFormatException ignored) {
                // absurdly long number: treat as a space
            }
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(decoded);
        return decoded.toString();
    }

    // ---------------------------------------------------------------- small helpers

    private static String normaliseLabel(String label) {
        return label.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_]+", " ")
                .replaceAll("\\.$", "")
                .trim();
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String limit(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }

    private static Map<String, Field> buildLabelMap() {
        Map<String, Field> labels = new HashMap<>();
        register(labels, Field.AIRLINE, "airline", "carrier");
        register(labels, Field.FLIGHT_NUMBER, "flight", "flight number", "flight no", "flight #");
        register(labels, Field.FROM, "from", "origin", "departure city");
        register(labels, Field.TO, "to", "destination", "arrival city");
        register(labels, Field.DEPARTURE, "departure", "departs", "depart", "departure time", "departure date");
        register(labels, Field.ARRIVAL, "arrival", "arrives", "arrive", "arrival time", "arrival date");
        register(labels, Field.HOTEL_NAME, "hotel", "hotel name", "property", "property name", "accommodation");
        register(labels, Field.CHECK_IN, "check-in", "check in", "checkin", "check-in date", "check in date");
        register(labels, Field.CHECK_OUT, "check-out", "check out", "checkout", "check-out date", "check out date");
        register(labels, Field.CITY, "city", "location");
        register(labels, Field.CONFIRMATION, "confirmation number", "confirmation no", "confirmation code",
                "confirmation #", "booking reference", "booking ref", "booking id", "booking number",
                "reservation number", "pnr");
        register(labels, Field.TOTAL, "total", "total amount", "total price", "amount paid", "grand total",
                "total fare");
        return Map.copyOf(labels);
    }

    private static void register(Map<String, Field> target, Field field, String... aliases) {
        for (String alias : aliases) {
            target.put(alias, field);
        }
    }
}