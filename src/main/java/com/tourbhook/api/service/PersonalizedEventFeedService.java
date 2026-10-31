package com.tourbhook.api.service;

import com.tourbhook.api.dto.event.PersonalizedEventFeedResponse;
import com.tourbhook.api.dto.event.PersonalizedFeedQuery;

public interface PersonalizedEventFeedService {

    PersonalizedEventFeedResponse getFeedForMe(PersonalizedFeedQuery query);
}