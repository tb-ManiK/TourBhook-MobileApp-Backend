package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.UgcLinkProperties;
import com.tourbhook.api.dto.ugc.SubmitUgcLinkRequest;
import com.tourbhook.api.dto.ugc.UgcLinkResponse;
import com.tourbhook.api.entity.Place;
import com.tourbhook.api.entity.UgcLink;
import com.tourbhook.api.entity.UgcLinkPlatform;
import com.tourbhook.api.entity.UgcLinkStatus;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.PlaceRepository;
import com.tourbhook.api.repository.UgcLinkRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ContentModerationException;
import com.tourbhook.api.repository.exception.DuplicateResourceException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.repository.exception.TooManyRequestsException;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.ContentModerationEnforcer;
import com.tourbhook.api.service.ugc.UgcLinkParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UgcLinkServiceImplTest {

    private static final String WATCH_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=43s";
    private static final String CANONICAL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    @Mock private UgcLinkRepository linkRepository;
    @Mock private PlaceRepository placeRepository;
    @Mock private AuthenticatedUserService authenticatedUserService;
    @Mock private ContentModerationEnforcer moderationEnforcer;

    private final UgcLinkProperties properties = new UgcLinkProperties();
    private UgcLinkServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new UgcLinkServiceImpl(linkRepository, placeRepository, authenticatedUserService,
                moderationEnforcer, new UgcLinkParser(), properties);
        user = User.builder().id("u1").name("Arun").build();
        lenient().when(authenticatedUserService.getCurrentUser()).thenReturn(user);
        lenient().when(linkRepository.save(any(UgcLink.class))).thenAnswer(call -> {
            UgcLink link = call.getArgument(0);
            if (link.getId() == null) {
                link.setId("link-1");
            }
            return link;
        });
    }

    private SubmitUgcLinkRequest request(String url, String destination, String placeId, String title) {
        return new SubmitUgcLinkRequest(url, destination, placeId, title);
    }

    private UgcLink savedLink() {
        ArgumentCaptor<UgcLink> captor = ArgumentCaptor.forClass(UgcLink.class);
        verify(linkRepository).save(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------ acceptance: stored, tied to destination and user

    @Test
    void submitLink_storesTheCanonicalLinkForTheUserAndTheDestination() {
        UgcLinkResponse response = service.submitLink(request(WATCH_URL, "  Udaipur   ", null, "  Sunrise at the lake  "));

        UgcLink saved = savedLink();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getDestination()).isEqualTo("Udaipur");
        assertThat(saved.getDestinationKey()).isEqualTo("udaipur");
        assertThat(saved.getPlatform()).isEqualTo(UgcLinkPlatform.YOUTUBE);
        assertThat(saved.getExternalId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(saved.getUrl()).isEqualTo(CANONICAL);                 // tracking parameters are gone
        assertThat(saved.getTitle()).isEqualTo("Sunrise at the lake");
        assertThat(saved.getStatus()).isEqualTo(UgcLinkStatus.ACTIVE);
        assertThat(saved.getModeratedAt()).isNotNull();

        assertThat(response.id()).isEqualTo("link-1");
        assertThat(response.url()).isEqualTo(CANONICAL);
        assertThat(response.destination()).isEqualTo("Udaipur");
        assertThat(response.submittedBy()).isEqualTo("Arun");
        assertThat(response.mine()).isTrue();
    }

    @Test
    void submitLink_anInstagramReel_isStoredToo() {
        service.submitLink(request("https://www.instagram.com/reel/C9xYz12AbCd/?igsh=abc", "Goa", null, null));

        UgcLink saved = savedLink();
        assertThat(saved.getPlatform()).isEqualTo(UgcLinkPlatform.INSTAGRAM);
        assertThat(saved.getExternalId()).isEqualTo("C9xYz12AbCd");
        assertThat(saved.getUrl()).isEqualTo("https://www.instagram.com/reel/C9xYz12AbCd/");
        assertThat(saved.getTitle()).isNull();
    }

    @Test
    void submitLink_canNarrowTheLinkToOneAttractionAtTheDestination() {
        Place place = Place.builder().id("place-1").name("City Palace").build();
        when(placeRepository.findById("place-1")).thenReturn(Optional.of(place));

        UgcLinkResponse response = service.submitLink(request(WATCH_URL, "Udaipur", " place-1 ", null));

        assertThat(savedLink().getPlace()).isSameAs(place);
        assertThat(response.placeId()).isEqualTo("place-1");
    }

    @Test
    void submitLink_aPlaceThatDoesNotExist_isNotFound() {
        when(placeRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitLink(request(WATCH_URL, "Udaipur", "nope", null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(linkRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ acceptance: moderation runs on submission

    @Test
    void submitLink_runsTheModerationCheckOnEverythingTheUserTyped_thenTheLinkCheck_inThatOrder() {
        service.submitLink(request(WATCH_URL, "Udaipur", null, "A title"));

        InOrder order = inOrder(moderationEnforcer);
        order.verify(moderationEnforcer).enforce(user, WATCH_URL, "Udaipur", "A title");
        order.verify(moderationEnforcer).enforceLink(user, CANONICAL);
    }

    @Test
    void submitLink_aViolationIsCaughtBeforeTheLinkIsEvenValidated() {
        String blockedSite = "https://malicious-example.test/video/1";
        doThrow(new ContentModerationException("blocked")).when(moderationEnforcer)
                .enforce(user, blockedSite, "Udaipur", null);

        assertThatThrownBy(() -> service.submitLink(request(blockedSite, "Udaipur", null, null)))
                .isInstanceOf(ContentModerationException.class);   // not BadRequestException

        verify(moderationEnforcer, never()).enforceLink(any(), anyString());
        verify(linkRepository, never()).save(any());
    }

    @Test
    void submitLink_aLinkThatFailsTheLinkCheck_storesNothing() {
        doThrow(new ContentModerationException("blocked")).when(moderationEnforcer).enforceLink(user, CANONICAL);

        assertThatThrownBy(() -> service.submitLink(request(WATCH_URL, "Udaipur", null, null)))
                .isInstanceOf(ContentModerationException.class);

        verify(linkRepository, never()).save(any());
    }

    @Test
    void submitLink_aMistakenLink_isA400_andNobodyIsBlockedForIt() {
        for (String notSupported : List.of("hello world", "https://vimeo.com/123456789",
                "https://www.youtube.com/@creator", "https://www.instagram.com/p/ABC12345/")) {
            assertThatThrownBy(() -> service.submitLink(request(notSupported, "Udaipur", null, null)))
                    .as(notSupported).isInstanceOf(BadRequestException.class);
        }

        verify(moderationEnforcer, never()).enforceLink(any(), anyString());
        verify(linkRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ duplicates, limits, taking down

    @Test
    void submitLink_theSameVideoForTheSameDestinationTwice_isRejectedAsADuplicate() {
        UgcLink existing = UgcLink.builder().id("old").user(user).status(UgcLinkStatus.ACTIVE).build();
        when(linkRepository.findByUserIdAndDestinationKeyAndPlatformAndExternalId(
                "u1", "udaipur", UgcLinkPlatform.YOUTUBE, "dQw4w9WgXcQ")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submitLink(request(WATCH_URL, "UDAIPUR", null, null)))
                .isInstanceOf(DuplicateResourceException.class);

        verify(linkRepository, never()).save(any());
    }

    @Test
    void submitLink_aLinkTheUserTookDownEarlier_comesBackInsteadOfBeingStoredTwice() {
        UgcLink removed = UgcLink.builder().id("old").user(user).destination("Udaipur").destinationKey("udaipur")
                .status(UgcLinkStatus.REMOVED).title("old title").build();
        when(linkRepository.findByUserIdAndDestinationKeyAndPlatformAndExternalId(
                "u1", "udaipur", UgcLinkPlatform.YOUTUBE, "dQw4w9WgXcQ")).thenReturn(Optional.of(removed));

        UgcLinkResponse response = service.submitLink(request(WATCH_URL, "Udaipur", null, "new title"));

        assertThat(savedLink()).isSameAs(removed);
        assertThat(removed.getStatus()).isEqualTo(UgcLinkStatus.ACTIVE);
        assertThat(removed.getTitle()).isEqualTo("new title");
        assertThat(removed.getModeratedAt()).isNotNull();
        assertThat(response.id()).isEqualTo("old");
        verify(linkRepository, never()).countByUserIdAndCreatedAtAfter(anyString(), any());
    }

    @Test
    void submitLink_stopsAtTheHourlyLimit_andJustUnderItIsFine() {
        when(linkRepository.countByUserIdAndCreatedAtAfter(eq("u1"), any(LocalDateTime.class))).thenReturn(10L, 9L);

        assertThatThrownBy(() -> service.submitLink(request(WATCH_URL, "Udaipur", null, null)))
                .isInstanceOf(TooManyRequestsException.class);
        verify(linkRepository, never()).save(any());

        assertThat(service.submitLink(request(WATCH_URL, "Udaipur", null, null)).id()).isEqualTo("link-1");
    }

    @Test
    void submitLink_aBlankDestination_isRejected() {
        assertThatThrownBy(() -> service.submitLink(request(WATCH_URL, "   ", null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("destination");
    }

    @Test
    void removeLink_takesTheUsersOwnLinkDown_butSomeoneElsesLooksLikeItDoesNotExist() {
        UgcLink own = UgcLink.builder().id("l1").user(user).status(UgcLinkStatus.ACTIVE).build();
        when(linkRepository.findByIdAndUserId("l1", "u1")).thenReturn(Optional.of(own));
        when(linkRepository.findByIdAndUserId("l2", "u1")).thenReturn(Optional.empty());

        service.removeLink("l1");

        assertThat(own.getStatus()).isEqualTo(UgcLinkStatus.REMOVED);
        assertThatThrownBy(() -> service.removeLink("l2")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void removeLink_aLinkAlreadyTakenDown_isANoOp() {
        UgcLink gone = UgcLink.builder().id("l1").user(user).status(UgcLinkStatus.REMOVED).build();
        when(linkRepository.findByIdAndUserId("l1", "u1")).thenReturn(Optional.of(gone));

        service.removeLink("l1");

        verify(linkRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ reading

    @Test
    void getLinksForDestination_isCaseInsensitive_showsActiveLinksOnly_andMarksTheUsersOwn() {
        User other = User.builder().id("u2").name("Meera").build();
        UgcLink mine = UgcLink.builder().id("a").user(user).destination("Udaipur").platform(UgcLinkPlatform.YOUTUBE)
                .url(CANONICAL).status(UgcLinkStatus.ACTIVE).build();
        UgcLink theirs = UgcLink.builder().id("b").user(other).destination("Udaipur").platform(UgcLinkPlatform.INSTAGRAM)
                .url("https://www.instagram.com/reel/C9xYz12AbCd/").status(UgcLinkStatus.ACTIVE).build();
        when(linkRepository.findForDestination(eq("udaipur"), eq(UgcLinkStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(List.of(mine, theirs));

        List<UgcLinkResponse> links = service.getLinksForDestination("  UDAIPUR ", 0, 20);

        assertThat(links).extracting(UgcLinkResponse::id).containsExactly("a", "b");
        assertThat(links).extracting(UgcLinkResponse::mine).containsExactly(true, false);
        assertThat(links).extracting(UgcLinkResponse::submittedBy).containsExactly("Arun", "Meera");
    }

    @Test
    void getLinks_clampsThePageSizeAndPageNumber() {
        when(linkRepository.findForDestination(any(), any(), any(Pageable.class))).thenReturn(List.of());
        when(linkRepository.findForUser(any(), any(), any(Pageable.class))).thenReturn(List.of());

        service.getLinksForDestination("Udaipur", -5, 5000);
        service.getMyLinks(-1, 0);

        ArgumentCaptor<Pageable> destinationPage = ArgumentCaptor.forClass(Pageable.class);
        verify(linkRepository).findForDestination(any(), any(), destinationPage.capture());
        assertThat(destinationPage.getValue().getPageNumber()).isZero();
        assertThat(destinationPage.getValue().getPageSize()).isEqualTo(50);

        ArgumentCaptor<Pageable> minePage = ArgumentCaptor.forClass(Pageable.class);
        verify(linkRepository).findForUser(eq("u1"), eq(UgcLinkStatus.ACTIVE), minePage.capture());
        assertThat(minePage.getValue().getPageSize()).isEqualTo(1);
    }
}