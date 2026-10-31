package com.tourbhook.api.service.dataimport;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ImportFieldParser {

    public record ParsedDateTime(LocalDate date, LocalTime time) {
        static final ParsedDateTime EMPTY = new ParsedDateTime(null, null);
    }

    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?");
    private static final Pattern ORDINAL_SUFFIX = Pattern.compile("(?<=\\d)(st|nd|rd|th)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKDAY = Pattern.compile("\\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILLER_WORDS = Pattern.compile("\\b(at|from|after|before|on|onwards)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile("(\\d[\\d,]*(?:\\.\\d{1,2})?)");
    private static final Pattern FOREIGN_CURRENCY =
            Pattern.compile("\\b(USD|EUR|GBP|AED|SGD|AUD|CAD)\\b|[$\\u20AC\\u00A3]", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARENTHETICAL = Pattern.compile("\\s*\\([^)]*\\)");

    /** Largest value that fits the DECIMAL(10,2) cost column. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            dateFormat("d MMM uuuu"),
            dateFormat("d MMMM uuuu"),
            dateFormat("MMM d uuuu"),
            dateFormat("MMMM d uuuu"),
            dateFormat("d-MMM-uuuu"),
            dateFormat("uuuu-M-d"),
            dateFormat("d/M/uuuu"),
            dateFormat("d-M-uuuu")
    );

    private ImportFieldParser() {
    }

    private static DateTimeFormatter dateFormat(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    public static ParsedDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedDateTime.EMPTY;
        }
        LocalTime time = null;
        String datePart = raw;

        Matcher matcher = TIME.matcher(raw);
        if (matcher.find()) {
            time = toLocalTime(matcher);
            datePart = raw.substring(0, matcher.start()) + " " + raw.substring(matcher.end());
        }
        return new ParsedDateTime(parseDate(datePart).orElse(null), time);
    }

    public static Optional<LocalTime> parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TIME.matcher(raw);
        return matcher.find() ? Optional.ofNullable(toLocalTime(matcher)) : Optional.empty();
    }

    public static Optional<LocalDate> parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = ORDINAL_SUFFIX.matcher(raw).replaceAll("");
        cleaned = WEEKDAY.matcher(cleaned).replaceAll(" ");
        cleaned = FILLER_WORDS.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replace(",", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(cleaned, format));
            } catch (DateTimeParseException ignored) {
                // try the next layout
            }
        }
        return Optional.empty();
    }

    public static BigDecimal parseInrAmount(String raw, String context, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        if (FOREIGN_CURRENCY.matcher(raw).find()) {
            warnings.add("Cost for '" + context + "' is not in INR ('" + raw.trim()
                    + "'), so it was left as 0. Please enter it manually.");
            return BigDecimal.ZERO;
        }
        Matcher matcher = AMOUNT.matcher(raw);
        if (!matcher.find()) {
            warnings.add("Cost for '" + context + "' could not be read ('" + raw.trim() + "'), so it was left as 0.");
            return BigDecimal.ZERO;
        }
        BigDecimal amount = new BigDecimal(matcher.group(1).replace(",", ""));
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            warnings.add("Cost for '" + context + "' is unrealistically large, so it was left as 0.");
            return BigDecimal.ZERO;
        }
        return amount;
    }

    /** "Chennai (MAA)" becomes "Chennai"; blank input becomes {@code null}. */
    public static String cleanCity(String raw) {
        if (raw == null) {
            return null;
        }
        String city = PARENTHETICAL.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
        return city.isEmpty() ? null : city;
    }

    private static LocalTime toLocalTime(Matcher matcher) {
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        String meridiem = matcher.group(3);
        try {
            if (meridiem != null) {
                if (hour < 1 || hour > 12) {
                    return null;
                }
                boolean pm = meridiem.equalsIgnoreCase("pm");
                hour = (hour % 12) + (pm ? 12 : 0);
            }
            return LocalTime.of(hour, minute);
        } catch (DateTimeException e) {
            return null;
        }
    }
}