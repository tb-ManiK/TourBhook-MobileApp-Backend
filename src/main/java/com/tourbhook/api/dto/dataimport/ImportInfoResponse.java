package com.tourbhook.api.dto.dataimport;

import com.tourbhook.api.entity.ImportSourceType;
import java.util.List;

public record ImportInfoResponse(
        boolean enabled,
        BetaNotice beta,
        List<SourceInfo> sources,
        Limits limits
) {

    public record SourceInfo(
            ImportSourceType type,
            String title,
            String description,
            String endpoint,
            String templateEndpoint
    ) {
    }

    public record Limits(long maxFileSizeBytes, int maxCsvRows, int maxEmailCharacters) {
    }
}