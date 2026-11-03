package com.tourbhook.api.controller;

import com.tourbhook.api.service.image.ImageUrlResolver;
import com.tourbhook.api.service.image.PlacePhotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
public class PlaceImageController {

    private static final String DEFAULT_IMAGE_RESOURCE = "images/default-place.png";

    private final PlacePhotoService placePhotoService;
    private final ImageUrlResolver imageUrlResolver;

    private volatile byte[] defaultImage;

    @GetMapping("/places/{placeId}")
    public ResponseEntity<Void> placeImage(@PathVariable String placeId) {
        Optional<String> photoUrl = placePhotoService.findPhotoUrl(placeId);
        if (photoUrl.isPresent()) {
            return redirect(photoUrl.get(), Duration.ofHours(1));
        }
        return redirect(imageUrlResolver.defaultImage(), Duration.ofMinutes(5));
    }

    @GetMapping("/default")
    public ResponseEntity<byte[]> defaultPlaceImage() {
        byte[] bytes = loadDefaultImage();
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(bytes);
    }

    private ResponseEntity<Void> redirect(String target, Duration cacheFor) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.maxAge(cacheFor).cachePublic())
                .build();
    }

    private byte[] loadDefaultImage() {
        byte[] bytes = defaultImage;
        if (bytes == null) {
            try (InputStream in = new ClassPathResource(DEFAULT_IMAGE_RESOURCE).getInputStream()) {
                bytes = in.readAllBytes();
                defaultImage = bytes;
            } catch (IOException e) {
                log.error("Default image {} could not be read", DEFAULT_IMAGE_RESOURCE);
                return null;
            }
        }
        return bytes;
    }
}