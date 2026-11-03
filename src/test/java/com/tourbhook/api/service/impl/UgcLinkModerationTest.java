package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.ModerationProperties;
import com.tourbhook.api.config.UgcLinkProperties;
import com.tourbhook.api.dto.ugc.SubmitUgcLinkRequest;
import com.tourbhook.api.entity.UgcLink;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.PlaceRepository;
import com.tourbhook.api.repository.UgcLinkRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.service.AccountEnforcementService;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.ContentModerationEnforcer;
import com.tourbhook.api.service.ugc.UgcLinkParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.ArrayList;
import java.util.List;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UgcLinkModerationTest {

    private static final String CLEAN_LINK = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    @Mock private UgcLinkRepository linkRepository;
    @Mock private PlaceRepository placeRepository;
    @Mock private AuthenticatedUserService authenticatedUserService;

    private final RecordingEnforcement enforcement = new RecordingEnforcement();
    private UgcLinkServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        ModerationProperties moderation = new ModerationProperties();
        moderation.setBannedTerms(List.of("scam", "spam-link"));
        moderation.setBlockedDomains(List.of("malicious-example.test"));

        service = new UgcLinkServiceImpl(linkRepository, placeRepository, authenticatedUserService,
                new ContentModerationEnforcer(new ModerationServiceImpl(moderation), enforcement),
                new UgcLinkParser(), new UgcLinkProperties());
        user = User.builder().id("u1").name("Arun").build();
        lenient().when(authenticatedUserService.getCurrentUser()).thenReturn(user);
        lenient().when(linkRepository.save(any(UgcLink.class))).thenAnswer(call -> call.getArgument(0));
    }

    private void assertRejectedAndNothingStored(String url, String destination, String title) {
        assertThatThrownBy(() -> service.submitLink(new SubmitUgcLinkRequest(url, destination, null, title)))
                .as(url + " | " + destination + " | " + title)
                .isInstanceOf(BadRequestException.class);

        assertThat(enforcement.blocked).as("the account is left alone").isEmpty();
        verify(linkRepository, never()).save(any());
    }

    @Test
    void aCleanYoutubeLink_isStored_andNobodyIsBlocked() {
        service.submitLink(new SubmitUgcLinkRequest(CLEAN_LINK, "Udaipur", null, "Sunrise at the lake"));

        assertThat(enforcement.blocked).isEmpty();
        verify(linkRepository).save(any(UgcLink.class));
    }

    @Test
    void aLinkToABlockedDomain_isTurnedAway_andTheUserKeepsTheirAccount() {
        assertRejectedAndNothingStored("https://malicious-example.test/watch?v=dQw4w9WgXcQ", "Udaipur", null);
    }

    @Test
    void aBlockedDomainWithoutAScheme_isTurnedAway() {
        assertRejectedAndNothingStored("malicious-example.test/offer", "Udaipur", null);
    }

    @Test
    void aBlockedDomainHiddenInsideAYoutubeLink_neverReachesTheDatabase() {
        service.submitLink(new SubmitUgcLinkRequest(CLEAN_LINK + "&redirect=malicious-example.test", "Udaipur", null, null));

        ArgumentCaptor<UgcLink> captor = ArgumentCaptor.forClass(UgcLink.class);
        verify(linkRepository).save(captor.capture());
        assertThat(captor.getValue().getUrl()).isEqualTo(CLEAN_LINK).doesNotContain("malicious");
        assertThat(enforcement.blocked).isEmpty();
    }

    @Test
    void aBannedTermInTheTitle_isTurnedAway_andTheUserKeepsTheirAccount() {
        assertRejectedAndNothingStored(CLEAN_LINK, "Udaipur", "this tour is a scam");
    }

    @Test
    void aBlockedLinkWrittenInTheTitle_isTurnedAway() {
        assertRejectedAndNothingStored(CLEAN_LINK, "Udaipur", "better deals at https://malicious-example.test/x");
    }

    @Test
    void aBannedTermInTheDestination_isTurnedAway() {
        assertRejectedAndNothingStored(CLEAN_LINK, "scam city", null);
    }

    @Test
    void aBannedWordInThePastedCaption_doesNotMatter_becauseTheCaptionIsNotStored() {
        service.submitLink(new SubmitUgcLinkRequest("Avoid the scam, but see this " + CLEAN_LINK, "Udaipur", null, null));

        verify(linkRepository).save(any(UgcLink.class));
        assertThat(enforcement.blocked).isEmpty();
    }

    @Test
    void aTypoOrAnUnsupportedButHarmlessLink_isJustA400_theUserKeepsTheirAccount() {
        for (String url : List.of("hello world", "https://vimeo.com/123456789", "https://www.youtube.com/@creator")) {
            assertThatThrownBy(() -> service.submitLink(new SubmitUgcLinkRequest(url, "Udaipur", null, null)))
                    .as(url).isInstanceOf(BadRequestException.class);
        }

        assertThat(enforcement.blocked).isEmpty();
        verify(linkRepository, never()).save(any());
    }

    private static final class RecordingEnforcement implements AccountEnforcementService {
        private final List<User> blocked = new ArrayList<>();

        @Override
        public void blockPermanently(User user, String reason) {
            blocked.add(user);
        }
    }
}