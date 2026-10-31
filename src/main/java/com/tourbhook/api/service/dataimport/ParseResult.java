package com.tourbhook.api.service.dataimport;

import java.util.List;

public record ParseResult(List<ImportedItem> items, List<String> warnings) {
}