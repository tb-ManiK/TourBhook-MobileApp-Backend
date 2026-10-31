package com.tourbhook.api.service.dataimport;

import com.tourbhook.api.dto.dataimport.ImportedItemType;
import com.tourbhook.api.entity.ImportSourceType;
import com.tourbhook.api.repository.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CsvItineraryParser implements ImportParser {

    static final int MAX_DATA_ROWS = 500;

    private static final Set<String> REQUIRED_COLUMNS = Set.of("date", "title");

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()                      // take column names from the first line
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .setAllowDuplicateHeaderNames(false)
            .build();

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.CSV_FILE;
    }

    @Override
    public ParseResult parse(String content) {
        List<ImportedItem> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try (CSVParser parser = FORMAT.parse(new StringReader(content))) {
            requireColumns(parser);

            int rowNumber = 1; // the header is row 1, so the first data row reads as row 2 in a spreadsheet
            for (CSVRecord record : parser) {
                rowNumber++;
                if (rowNumber - 1 > MAX_DATA_ROWS) {
                    throw new BadRequestException("The file has more than " + MAX_DATA_ROWS
                            + " rows. Please split it into smaller files.");
                }
                toItem(record, rowNumber, warnings).ifPresent(items::add);
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            log.warn("Rejected malformed CSV import: {}", e.getMessage());
            throw new BadRequestException(
                    "The CSV file could not be read. Make sure it has a header row, comma-separated "
                            + "values and no duplicate column names.");
        }

        if (items.isEmpty()) {
            throw new BadRequestException("No importable rows were found in the file."
                    + (warnings.isEmpty() ? "" : " First problem: " + warnings.get(0)));
        }
        return new ParseResult(items, warnings);
    }

    private void requireColumns(CSVParser parser) {
        Set<String> present = parser.getHeaderNames().stream()
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<String> missing = REQUIRED_COLUMNS.stream().filter(c -> !present.contains(c)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new BadRequestException("The file is missing required column(s): " + String.join(", ", missing)
                    + ". Expected header, e.g.: date,time,title,type,duration_minutes,cost,city,confirmation_code");
        }
    }

    private Optional<ImportedItem> toItem(CSVRecord record, int row, List<String> warnings) {
        LocalDate date = ImportFieldParser.parseDate(value(record, "date")).orElse(null);
        if (date == null) {
            warnings.add("Row " + row + ": the date is missing or not recognised, so the row was skipped.");
            return Optional.empty();
        }
        String title = value(record, "title");
        if (title == null) {
            warnings.add("Row " + row + ": the title is empty, so the row was skipped.");
            return Optional.empty();
        }

        return Optional.of(new ImportedItem(
                readType(value(record, "type"), row, warnings),
                title,
                date,
                readTime(value(record, "time"), row, warnings),
                readDuration(value(record, "duration_minutes"), row, warnings),
                readCost(value(record, "cost"), title, warnings),
                ImportFieldParser.cleanCity(value(record, "city")),
                value(record, "confirmation_code")));
    }

    private ImportedItemType readType(String raw, int row, List<String> warnings) {
        if (raw == null) {
            return ImportedItemType.ACTIVITY;
        }
        return ImportedItemType.fromLabel(raw).orElseGet(() -> {
            warnings.add("Row " + row + ": unknown type '" + raw + "', imported as a general activity.");
            return ImportedItemType.ACTIVITY;
        });
    }

    private LocalTime readTime(String raw, int row, List<String> warnings) {
        if (raw == null) {
            return null;
        }
        Optional<LocalTime> time = ImportFieldParser.parseTime(raw);
        if (time.isEmpty()) {
            warnings.add("Row " + row + ": time '" + raw + "' was not recognised, so a default time will be used.");
        }
        return time.orElse(null);
    }

    private Integer readDuration(String raw, int row, List<String> warnings) {
        if (raw == null) {
            return null;
        }
        try {
            int minutes = Integer.parseInt(raw);
            if (minutes >= 1 && minutes <= 24 * 60) {
                return minutes;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        warnings.add("Row " + row + ": duration_minutes '" + raw + "' is not a number between 1 and 1440, "
                + "so a default duration will be used.");
        return null;
    }

    private BigDecimal readCost(String raw, String title, List<String> warnings) {
        return ImportFieldParser.parseInrAmount(raw, title, warnings);
    }

    private String value(CSVRecord record, String column) {
        if (!record.isMapped(column) || !record.isSet(column)) {
            return null;
        }
        String cell = record.get(column);
        return cell == null || cell.isBlank() ? null : cell.trim();
    }
}