package com.tourbhook.api.service.dataimport;

import com.tourbhook.api.config.DataImportProperties;
import com.tourbhook.api.dto.dataimport.BetaNotice;
import com.tourbhook.api.dto.dataimport.ImportEmailRequest;
import com.tourbhook.api.dto.dataimport.ImportInfoResponse;
import com.tourbhook.api.entity.ImportSourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;


@Component
@RequiredArgsConstructor
public class ImportCatalog {

    public static final String FEEDBACK_ENDPOINT = "/api/v1/imports/feedback";
    public static final String CSV_TEMPLATE_ENDPOINT = "/api/v1/imports/templates/csv";

    private static final String CSV_TEMPLATE = String.join("\n",
            "date,time,title,type,duration_minutes,cost,city,confirmation_code",
            "2026-11-12,06:30,IndiGo 6E 204: Chennai to Delhi,flight,165,8400,Delhi,XY12AB",
            "2026-11-12,14:00,Hotel check-in: Lake View,hotel_check_in,30,0,Udaipur,HT-99120",
            "2026-11-13,10:00,Visit City Palace,activity,180,700,Udaipur,",
            "2026-11-14,11:00,Hotel check-out: Lake View,hotel_check_out,30,0,Udaipur,HT-99120") + "\n";

    private final DataImportProperties properties;

    public BetaNotice betaNotice() {
        if (!properties.isBeta()) {
            return new BetaNotice(false, null, null, FEEDBACK_ENDPOINT);
        }
        return new BetaNotice(true, properties.getBetaLabel(), properties.getBetaMessage(), FEEDBACK_ENDPOINT);
    }

    public ImportInfoResponse info() {
        return new ImportInfoResponse(
                properties.isEnabled(),
                betaNotice(),
                List.of(
                        new ImportInfoResponse.SourceInfo(
                                ImportSourceType.CONFIRMATION_EMAIL,
                                "Booking confirmation email",
                                "Paste the text of a flight or hotel confirmation email, or the full .eml source. "
                                        + "Details must be written as 'Label: value' lines, for example "
                                        + "'Flight: 6E 204' or 'Check-in: 12 Nov 2026'.",
                                "/api/v1/imports/email",
                                null),
                        new ImportInfoResponse.SourceInfo(
                                ImportSourceType.CSV_FILE,
                                "CSV file",
                                "Upload a .csv file with a header row. Required columns: date, title. "
                                        + "Optional: time, type, duration_minutes, cost, city, confirmation_code.",
                                "/api/v1/imports/file",
                                CSV_TEMPLATE_ENDPOINT)),
                new ImportInfoResponse.Limits(
                        properties.getMaxFileSizeBytes(),
                        CsvItineraryParser.MAX_DATA_ROWS,
                        ImportEmailRequest.MAX_RAW_EMAIL_LENGTH));
    }

    public String csvTemplate() {
        return CSV_TEMPLATE;
    }
}