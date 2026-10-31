package com.tourbhook.api.service.event;

import com.tourbhook.api.repository.exception.ExternalServiceException;
import java.time.LocalDate;
import java.util.List;


public interface EventProvider {

    String sourceName();

    boolean isEnabled();

    List<ExternalEvent> fetch(SyncArea area, LocalDate from, LocalDate to);
}