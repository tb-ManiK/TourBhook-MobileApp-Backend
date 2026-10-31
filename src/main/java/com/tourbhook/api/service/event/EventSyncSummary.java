package com.tourbhook.api.service.event;

public record EventSyncSummary(int areas, int succeeded, int failed, int created, int updated, int deactivated) {

    static final EventSyncSummary EMPTY = new EventSyncSummary(0, 0, 0, 0, 0, 0);
}