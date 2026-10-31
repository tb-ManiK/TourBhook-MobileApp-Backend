    package com.tourbhook.api.controller;

    import com.tourbhook.api.dto.common.ApiResponse;
    import com.tourbhook.api.dto.place.AddPlaceToTripRequest;
    import com.tourbhook.api.dto.place.PlaceResponse;
    import com.tourbhook.api.service.PlaceService;
    import jakarta.validation.Valid;
    import lombok.RequiredArgsConstructor;
    import org.springframework.http.ResponseEntity;
    import org.springframework.web.bind.annotation.*;

    import java.util.List;

    @RestController
    @RequestMapping("/api/v1/places")
    @RequiredArgsConstructor
    public class PlaceController {

        private final PlaceService placeService;

        @GetMapping
        public ResponseEntity<ApiResponse<List<PlaceResponse>>> getPlaces(
                @RequestParam(required = false) String city
        ) {
            return ResponseEntity.ok(
                    ApiResponse.<List<PlaceResponse>>builder()
                            .success(true)
                            .message("Places fetched successfully")
                            .data(placeService.getPlaces(city))
                            .build()
            );
        }

        @GetMapping("/suggested")
        public ResponseEntity<ApiResponse<List<PlaceResponse>>> getSuggestedPlaces(
                @RequestParam(required = false) String city
        ) {
            return ResponseEntity.ok(
                    ApiResponse.<List<PlaceResponse>>builder()
                            .success(true)
                            .message("Suggested places fetched successfully")
                            .data(placeService.getSuggestedPlaces(city))
                            .build()
            );
        }

    //    @GetMapping("/search")
    //    public ResponseEntity<ApiResponse<List<PlaceResponse>>> searchPlaces(
    //            @RequestParam String q
    //    ) {
    //        return ResponseEntity.ok(
    //                ApiResponse.<List<PlaceResponse>>builder()
    //                        .success(true)
    //                        .message("Places fetched successfully")
    //                        .data(placeService.search(q))
    //                        .build()
    //        );
    //    }

        @GetMapping("/search")
        public ResponseEntity<ApiResponse<List<PlaceResponse>>> searchPlaces(
                @RequestParam String q,
                @RequestParam(required = false) String city,
                @RequestParam(required = false) String tripId
        ) {
            return ResponseEntity.ok(
                    ApiResponse.<List<PlaceResponse>>builder()
                            .success(true)
                            .message("Places fetched successfully")
                            .data(isScoped(city, tripId)
                                    ? placeService.search(q, city, tripId)
                                    : placeService.search(q))
                            .build()
            );
        }

        @GetMapping("/{id}")
        public ResponseEntity<ApiResponse<PlaceResponse>> getPlace(
                @PathVariable String id
        ) {
            return ResponseEntity.ok(
                    ApiResponse.<PlaceResponse>builder()
                            .success(true)
                            .message("Place fetched successfully")
                            .data(placeService.getPlace(id))
                            .build()
            );
        }

        @PostMapping("/trip/add")
        public ResponseEntity<ApiResponse<Void>> addToTrip(
                @Valid @RequestBody AddPlaceToTripRequest request
        ) {
            placeService.addToTrip(request);
            return ResponseEntity.ok(
                    ApiResponse.<Void>builder()
                            .success(true)
                            .message("Place added to trip successfully")
                            .build()
            );
        }

        @DeleteMapping("/trip/remove")
        public ResponseEntity<ApiResponse<Void>> removeFromTrip(
                @Valid @RequestBody AddPlaceToTripRequest request
        ) {
            placeService.removeFromTrip(request);
            return ResponseEntity.ok(
                    ApiResponse.<Void>builder()
                            .success(true)
                            .message("Place removed from trip successfully")
                            .build()
            );
        }
        private static boolean isScoped(String city, String tripId) {
            return (city != null && !city.isBlank()) || (tripId != null && !tripId.isBlank());
        }
    }