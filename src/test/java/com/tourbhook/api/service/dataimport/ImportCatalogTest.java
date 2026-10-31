package com.tourbhook.api.service.dataimport;

import com.tourbhook.api.config.DataImportProperties;
import com.tourbhook.api.dto.dataimport.BetaNotice;
import com.tourbhook.api.dto.dataimport.ImportInfoResponse;
import com.tourbhook.api.entity.ImportSourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportCatalogTest {

    private final DataImportProperties properties = new DataImportProperties();
    private final ImportCatalog catalog = new ImportCatalog(properties);

    @Test
    void betaNotice_flagsTheFeatureAsBetaAndPointsToTheFeedbackEndpoint() {
        BetaNotice notice = catalog.betaNotice();

        assertThat(notice.beta()).isTrue();
        assertThat(notice.label()).isEqualTo("Beta");
        assertThat(notice.message()).contains("beta").contains("review the draft");
        assertThat(notice.feedbackEndpoint()).isEqualTo("/api/v1/imports/feedback");
    }

    @Test
    void betaNotice_afterGraduation_dropsTheWordingButKeepsTheFeedbackEndpoint() {
        properties.setBeta(false);

        BetaNotice notice = catalog.betaNotice();

        assertThat(notice.beta()).isFalse();
        assertThat(notice.label()).isNull();
        assertThat(notice.message()).isNull();
        assertThat(notice.feedbackEndpoint()).isEqualTo("/api/v1/imports/feedback");
    }

    @Test
    void betaNotice_usesTheConfiguredWording() {
        properties.setBetaLabel("Early access");
        properties.setBetaMessage("Help us test this.");

        assertThat(catalog.betaNotice().label()).isEqualTo("Early access");
        assertThat(catalog.betaNotice().message()).isEqualTo("Help us test this.");
    }

    @Test
    void info_describesBothSourcesWithWhereToSendThem() {
        ImportInfoResponse info = catalog.info();

        assertThat(info.enabled()).isTrue();
        assertThat(info.beta().beta()).isTrue();
        assertThat(info.sources()).extracting(ImportInfoResponse.SourceInfo::type)
                .containsExactly(ImportSourceType.CONFIRMATION_EMAIL, ImportSourceType.CSV_FILE);
        assertThat(info.sources().get(0).endpoint()).isEqualTo("/api/v1/imports/email");
        assertThat(info.sources().get(0).templateEndpoint()).isNull();
        assertThat(info.sources().get(1).endpoint()).isEqualTo("/api/v1/imports/file");
        assertThat(info.sources().get(1).templateEndpoint()).isEqualTo("/api/v1/imports/templates/csv");
    }

    @Test
    void info_reportsTheLimitsThatAreActuallyEnforced() {
        properties.setMaxFileSizeBytes(2048);

        ImportInfoResponse.Limits limits = catalog.info().limits();

        assertThat(limits.maxFileSizeBytes()).isEqualTo(2048);
        assertThat(limits.maxCsvRows()).isEqualTo(CsvItineraryParser.MAX_DATA_ROWS);
        assertThat(limits.maxEmailCharacters()).isEqualTo(200_000);
    }

    @Test
    void info_showsWhenTheFeatureHasBeenSwitchedOff() {
        properties.setEnabled(false);

        assertThat(catalog.info().enabled()).isFalse();
    }

    @Test
    void csvTemplate_importsCleanlyWithoutAnyWarnings() {
        ParseResult parsed = new CsvItineraryParser().parse(catalog.csvTemplate());

        var draft = new ItineraryDraftMapper().toDraft(parsed, null);

        assertThat(draft.warnings()).isEmpty();
        assertThat(draft.city()).isEqualTo("Udaipur");
        assertThat(draft.days()).hasSize(3);
        assertThat(draft.days().stream().mapToInt(day -> day.activities().size()).sum()).isEqualTo(4);
    }
}