package com.tourbhook.api.service.dataimport;

import com.tourbhook.api.dto.dataimport.ImportedItemType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record ImportedItem(
        ImportedItemType type,
        String title,
        LocalDate date,
        LocalTime time,
        Integer durationMinutes,
        BigDecimal cost,
        String city,
        String confirmationCode
) {

    public ImportedItem withCost(BigDecimal newCost) {
        return new ImportedItem(type, title, date, time, durationMinutes, newCost, city, confirmationCode);
    }
}