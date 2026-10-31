package com.tourbhook.api.dto.dataimport;

import java.math.BigDecimal;

public record DraftActivity(
        String time,
        String title,
        String duration,
        BigDecimal cost,
        String icon,
        ImportedItemType type,
        String confirmationCode
) {
}