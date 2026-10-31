package com.tourbhook.api.service;

import com.tourbhook.api.dto.place.AddPlaceToTripRequest;
import com.tourbhook.api.dto.place.PlaceResponse;

import java.util.List;

public interface PlaceService {
    List<PlaceResponse> getPlaces(String city);
    List<PlaceResponse> getSuggestedPlaces(String city);
    List<PlaceResponse> search(String q);
    List<PlaceResponse> suggested();
    List<PlaceResponse> search(String q, String city, String tripId);
    PlaceResponse getPlace(String id);
    void addToTrip(AddPlaceToTripRequest request);
    void removeFromTrip(AddPlaceToTripRequest request);
}