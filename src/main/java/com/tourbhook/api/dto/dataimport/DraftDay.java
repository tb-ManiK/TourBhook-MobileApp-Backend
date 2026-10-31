package com.tourbhook.api.dto.dataimport;

import java.time.LocalDate;
import java.util.List;

public record DraftDay(
        int dayNumber,
        LocalDate date,
        List<DraftActivity> activities
) {
}