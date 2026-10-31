package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.UgcLinkProperties;
import com.tourbhook.api.dto.ugc.SubmitUgcLinkRequest;
import com.tourbhook.api.dto.ugc.UgcLinkResponse;
import com.tourbhook.api.entity.Place;
import com.tourbhook.api.entity.UgcLink;
import com.tourbhook.api.entity.UgcLinkStatus;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.PlaceRepository;
import com.tourbhook.api.repository.UgcLinkRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.DuplicateResourceException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.repository.exception.TooManyRequestsException;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.ContentModerationEnforcer;
import com.tourbhook.api.service.UgcLinkService;
import com.tourbhook.api.service.ugc.ParsedLink;
import com.tourbhook.api.service.ugc.UgcLinkParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UgcLinkServiceImpl implements UgcLinkService {

    private final UgcLinkRepository linkRepository;
    private final PlaceRepository placeRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final ContentModerationEnforcer moderationEnforcer;
    private final UgcLinkParser linkParser;
    private final UgcLinkProperties properties;

    @Override
    public UgcLinkResponse submitLink(SubmitUgcLinkRequest request) {
        User user = authenticatedUserService.getCurrentUser();
        String rawUrl = request.url().trim();
        String destination = normaliseDestination(request.destination());
        String title = blankToNull(request.title());


        moderationEnforcer.enforce(user, rawUrl, destination, title);

        // 2. A link that is not a YouTube video or a Reel is a mistake, not a violation: 400, no block.
        ParsedLink link = linkParser.parse(rawUrl);

        // 3. The dedicated link check, on the clean, canonical address that will actually be stored.
        moderationEnforcer.enforceLink(user, link.canonicalUrl());

        Place place = resolvePlace(request.placeId());
        String destinationKey = destination.toLowerCase(Locale.ROOT);

        Optional<UgcLink> earlier = linkRepository.findByUserIdAndDestinationKeyAndPlatformAndExternalId(
                user.getId(), destinationKey, link.platform(), link.externalId());
        if (earlier.isPresent() && earlier.get().getStatus() == UgcLinkStatus.ACTIVE) {
            throw new DuplicateResourceException("You have already shared this video for this destination.");
        }

        UgcLink saved;
        if (earlier.isPresent()) {
            UgcLink revived = earlier.get();
            revived.setStatus(UgcLinkStatus.ACTIVE);
            revived.setTitle(title);
            revived.setPlace(place);
            revived.setModeratedAt(LocalDateTime.now());
            saved = linkRepository.save(revived);
        } else {
            enforceHourlyLimit(user);
            saved = linkRepository.save(UgcLink.builder()
                    .user(user)
                    .destination(destination)
                    .destinationKey(destinationKey)
                    .place(place)
                    .platform(link.platform())
                    .externalId(link.externalId())
                    .url(link.canonicalUrl())
                    .title(title)
                    .status(UgcLinkStatus.ACTIVE)
                    .moderatedAt(LocalDateTime.now())
                    .build());
        }

        log.info("User {} shared a {} link for {} ({})", user.getId(), saved.getPlatform(), destination, saved.getId());
        return toResponse(saved, user.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UgcLinkResponse> getLinksForDestination(String destination, int page, int size) {
        User user = authenticatedUserService.getCurrentUser();
        String destinationKey = normaliseDestination(destination).toLowerCase(Locale.ROOT);
        return linkRepository.findForDestination(destinationKey, UgcLinkStatus.ACTIVE, pageOf(page, size)).stream()
                .map(link -> toResponse(link, user.getId()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UgcLinkResponse> getMyLinks(int page, int size) {
        User user = authenticatedUserService.getCurrentUser();
        return linkRepository.findForUser(user.getId(), UgcLinkStatus.ACTIVE, pageOf(page, size)).stream()
                .map(link -> toResponse(link, user.getId()))
                .toList();
    }

    @Override
    public void removeLink(String linkId) {
        User user = authenticatedUserService.getCurrentUser();
        UgcLink link = linkRepository.findByIdAndUserId(linkId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Link not found"));
        if (link.getStatus() == UgcLinkStatus.ACTIVE) {
            link.setStatus(UgcLinkStatus.REMOVED);
            linkRepository.save(link);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void enforceHourlyLimit(User user) {
        long sharedLastHour = linkRepository.countByUserIdAndCreatedAtAfter(user.getId(), LocalDateTime.now().minusHours(1));
        if (sharedLastHour >= properties.getMaxSubmissionsPerHour()) {
            throw new TooManyRequestsException("You have shared a lot of links in the last hour. Please try again later.");
        }
    }

    private Place resolvePlace(String placeId) {
        if (placeId == null || placeId.isBlank()) {
            return null;
        }
        return placeRepository.findById(placeId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Place not found"));
    }

    private String normaliseDestination(String destination) {
        String cleaned = destination == null ? "" : destination.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            throw new BadRequestException("destination is required.");
        }
        return cleaned;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Pageable pageOf(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), properties.getMaxPageSize()));
    }

    private UgcLinkResponse toResponse(UgcLink link, String currentUserId) {
        return new UgcLinkResponse(
                link.getId(),
                link.getDestination(),
                link.getPlace() == null ? null : link.getPlace().getId(),
                link.getPlatform(),
                link.getUrl(),
                link.getTitle(),
                link.getUser().getName(),
                link.getUser().getId().equals(currentUserId),
                link.getCreatedAt());
    }
}