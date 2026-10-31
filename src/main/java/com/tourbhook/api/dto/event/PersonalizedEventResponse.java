package com.tourbhook.api.dto.event;

import java.util.List;

public record PersonalizedEventResponse(EventResponse event, List<EventRelevance> relevance) {
}