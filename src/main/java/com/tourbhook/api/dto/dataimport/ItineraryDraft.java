package com.tourbhook.api.dto.dataimport;

import java.time.LocalDate;
import java.util.List;

public record ItineraryDraft(
        String city,
        LocalDate startDate,
        LocalDate endDate,
        List<DraftDay> days,
        List<String> warnings
) {
}