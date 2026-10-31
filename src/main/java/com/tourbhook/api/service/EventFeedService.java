package com.tourbhook.api.service;

import com.tourbhook.api.dto.event.EventFeedQuery;
import com.tourbhook.api.dto.event.EventFeedResponse;

public interface EventFeedService {

    EventFeedResponse getFeed(EventFeedQuery query);

    EventFeedResponse getFeedForTrip(String tripId, String category, int page, int size);
}