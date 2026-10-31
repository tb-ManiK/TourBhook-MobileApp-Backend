package com.tourbhook.api.service.event;

import java.util.List;

public record PageSlice<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <T> PageSlice<T> of(List<T> all, int requestedPage, int requestedSize, int maxPageSize) {
        int size = Math.min(Math.max(requestedSize, 1), maxPageSize);
        int page = Math.max(requestedPage, 0);

        int start = (int) Math.min((long) page * size, all.size());
        int end = Math.min(start + size, all.size());
        int totalPages = (int) Math.ceil(all.size() / (double) size);

        return new PageSlice<>(List.copyOf(all.subList(start, end)), page, size, all.size(), totalPages);
    }
}