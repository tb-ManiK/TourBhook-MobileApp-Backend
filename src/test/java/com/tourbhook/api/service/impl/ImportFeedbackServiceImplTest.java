package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.DataImportProperties;
import com.tourbhook.api.dto.dataimport.ImportFeedbackRequest;
import com.tourbhook.api.dto.dataimport.ImportFeedbackResponse;
import com.tourbhook.api.entity.FeatureFeedback;
import com.tourbhook.api.entity.FeedbackFeature;
import com.tourbhook.api.entity.ImportDraft;
import com.tourbhook.api.entity.ImportDraftStatus;
import com.tourbhook.api.entity.ImportSourceType;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.FeatureFeedbackRepository;
import com.tourbhook.api.repository.ImportDraftRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ContentModerationException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.repository.exception.TooManyRequestsException;
import com.tourbhook.api.service.AnalyticsService;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.ContentModerationEnforcer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImportFeedbackServiceImplTest {

    @Mock private FeatureFeedbackRepository feedbackRepository;
    @Mock private ImportDraftRepository importDraftRepository;
    @Mock private AuthenticatedUserService authenticatedUserService;
    @Mock private ContentModerationEnforcer moderationEnforcer;
    @Mock private AnalyticsService analyticsService;

    private final DataImportProperties properties = new DataImportProperties();
    private ImportFeedbackServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new ImportFeedbackServiceImpl(feedbackRepository, importDraftRepository,
                authenticatedUserService, moderationEnforcer, analyticsService, properties);
        user = User.builder().id("u1").build();
        lenient().when(authenticatedUserService.getCurrentUser()).thenReturn(user);
        lenient().when(feedbackRepository.save(any(FeatureFeedback.class))).thenAnswer(call -> {
            FeatureFeedback feedback = call.getArgument(0);
            if (feedback.getId() == null) {
                feedback.setId("fb-1");
            }
            return feedback;
        });
    }

    private ImportDraft draft(String id, ImportSourceType type, ImportDraftStatus status) {
        return ImportDraft.builder().id(id).user(user).sourceType(type).status(status).build();
    }

    @Test
    void submitFeedback_withoutAnImport_savesTheRatingAndComment() {
        ImportFeedbackResponse response = service.submitFeedback(new ImportFeedbackRequest(4, "  Worked well  ", null));

        ArgumentCaptor<FeatureFeedback> saved = ArgumentCaptor.forClass(FeatureFeedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(user);
        assertThat(saved.getValue().getFeature()).isEqualTo(FeedbackFeature.DATA_IMPORT);
        assertThat(saved.getValue().getRating()).isEqualTo(4);
        assertThat(saved.getValue().getComment()).isEqualTo("Worked well");
        assertThat(saved.getValue().getReferenceId()).isNull();
        assertThat(saved.getValue().getContext()).isNull();
        assertThat(response.id()).isEqualTo("fb-1");
        assertThat(response.rating()).isEqualTo(4);
        assertThat(response.message()).contains("Thank you");
    }

    @Test
    void submitFeedback_aboutAnImport_recordsWhichImportAndHowItWentForTriage() {
        when(importDraftRepository.findByIdAndUser("d1", user))
                .thenReturn(Optional.of(draft("d1", ImportSourceType.CSV_FILE, ImportDraftStatus.COMMITTED)));
        when(feedbackRepository.findByUserIdAndFeatureAndReferenceId("u1", FeedbackFeature.DATA_IMPORT, "d1"))
                .thenReturn(Optional.empty());

        service.submitFeedback(new ImportFeedbackRequest(2, "Dates were wrong", " d1 "));

        ArgumentCaptor<FeatureFeedback> saved = ArgumentCaptor.forClass(FeatureFeedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getReferenceId()).isEqualTo("d1");
        assertThat(saved.getValue().getContext()).isEqualTo("CSV_FILE/COMMITTED");
    }

    @Test
    void submitFeedback_aboutSomeoneElsesImport_looksLikeItDoesNotExist() {
        when(importDraftRepository.findByIdAndUser("d1", user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitFeedback(new ImportFeedbackRequest(5, null, "d1")))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void submitFeedback_again_aboutTheSameImport_updatesTheEarlierEntryInsteadOfAddingOne() {
        FeatureFeedback earlier = FeatureFeedback.builder().id("fb-old").user(user)
                .feature(FeedbackFeature.DATA_IMPORT).rating(2).comment("meh").referenceId("d1").build();
        when(importDraftRepository.findByIdAndUser("d1", user))
                .thenReturn(Optional.of(draft("d1", ImportSourceType.CONFIRMATION_EMAIL, ImportDraftStatus.DRAFT)));
        when(feedbackRepository.findByUserIdAndFeatureAndReferenceId("u1", FeedbackFeature.DATA_IMPORT, "d1"))
                .thenReturn(Optional.of(earlier));

        ImportFeedbackResponse response = service.submitFeedback(new ImportFeedbackRequest(5, "Much better", "d1"));

        assertThat(response.id()).isEqualTo("fb-old");
        assertThat(earlier.getRating()).isEqualTo(5);
        assertThat(earlier.getComment()).isEqualTo("Much better");
        // changing your mind is not "new feedback", so the daily limit is not consulted
        verify(feedbackRepository, never()).countByUserIdAndFeatureAndCreatedAtAfter(anyString(), any(), any());
    }

    @Test
    void submitFeedback_stopsAtTheDailyLimitOfNewEntries() {
        when(feedbackRepository.countByUserIdAndFeatureAndCreatedAtAfter(eq("u1"), eq(FeedbackFeature.DATA_IMPORT), any(LocalDateTime.class)))
                .thenReturn(10L);

        assertThatThrownBy(() -> service.submitFeedback(new ImportFeedbackRequest(3, "again", null)))
                .isInstanceOf(TooManyRequestsException.class);

        verify(feedbackRepository, never()).save(any());
        verify(moderationEnforcer, never()).enforce(any(), anyString());
    }

    @Test
    void submitFeedback_justUnderTheDailyLimit_isStillAccepted() {
        when(feedbackRepository.countByUserIdAndFeatureAndCreatedAtAfter(eq("u1"), eq(FeedbackFeature.DATA_IMPORT), any(LocalDateTime.class)))
                .thenReturn(9L);

        assertThat(service.submitFeedback(new ImportFeedbackRequest(3, "ninth", null)).id()).isEqualTo("fb-1");
    }

    @Test
    void submitFeedback_aCommentThatBreaksTheContentRules_blocksTheAuthorAndStoresNothing() {
        doThrow(new ContentModerationException("blocked")).when(moderationEnforcer).enforce(eq(user), eq("this is a scam"));

        assertThatThrownBy(() -> service.submitFeedback(new ImportFeedbackRequest(1, "this is a scam", null)))
                .isInstanceOf(ContentModerationException.class);

        verify(feedbackRepository, never()).save(any());
        verify(analyticsService, never()).track(anyString(), anyMap());
    }

    @Test
    void submitFeedback_aBlankComment_isStoredAsNoComment() {
        service.submitFeedback(new ImportFeedbackRequest(5, "   ", null));

        ArgumentCaptor<FeatureFeedback> saved = ArgumentCaptor.forClass(FeatureFeedback.class);
        verify(feedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getComment()).isNull();
    }

    @Test
    void submitFeedback_aCommentOverTheConfiguredLength_isRejected() {
        properties.setMaxCommentLength(5);

        assertThatThrownBy(() -> service.submitFeedback(new ImportFeedbackRequest(3, "far too long", null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("5");
    }

    @Test
    void submitFeedback_reportsTheSubmissionToAnalytics_withoutTheCommentText() {
        service.submitFeedback(new ImportFeedbackRequest(4, "secret words", null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(analyticsService).track(eq("beta_feedback_submitted"), payload.capture());
        assertThat(payload.getValue()).containsEntry("feature", "DATA_IMPORT").containsEntry("rating", 4)
                .containsEntry("hasComment", true).doesNotContainValue("secret words");
    }
}