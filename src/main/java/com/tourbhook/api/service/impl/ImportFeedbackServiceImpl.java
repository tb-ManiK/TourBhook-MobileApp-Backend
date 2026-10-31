package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.DataImportProperties;
import com.tourbhook.api.dto.dataimport.ImportFeedbackRequest;
import com.tourbhook.api.dto.dataimport.ImportFeedbackResponse;
import com.tourbhook.api.entity.FeatureFeedback;
import com.tourbhook.api.entity.FeedbackFeature;
import com.tourbhook.api.entity.ImportDraft;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.FeatureFeedbackRepository;
import com.tourbhook.api.repository.ImportDraftRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.repository.exception.TooManyRequestsException;
import com.tourbhook.api.service.AnalyticsService;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.ContentModerationEnforcer;
import com.tourbhook.api.service.ImportFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ImportFeedbackServiceImpl implements ImportFeedbackService {

    private static final String THANKS = "Thank you! Your feedback helps us improve data import.";

    private final FeatureFeedbackRepository feedbackRepository;
    private final ImportDraftRepository importDraftRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final ContentModerationEnforcer moderationEnforcer;
    private final AnalyticsService analyticsService;
    private final DataImportProperties properties;

    @Override
    public ImportFeedbackResponse submitFeedback(ImportFeedbackRequest request) {
        User user = authenticatedUserService.getCurrentUser();
        String comment = normaliseComment(request.comment());

        Optional<ImportDraft> draft = findOwnDraft(request.draftId(), user);
        Optional<FeatureFeedback> earlier = draft.flatMap(found -> feedbackRepository
                .findByUserIdAndFeatureAndReferenceId(user.getId(), FeedbackFeature.DATA_IMPORT, found.getId()));

        // Changing what you already said is always fine; only brand-new entries count towards the limit.
        if (earlier.isEmpty()) {
            enforceDailyLimit(user);
        }
        // A comment is user-written content like a review: the same zero-tolerance rule applies.
        moderationEnforcer.enforce(user, comment);

        FeatureFeedback feedback = earlier.orElseGet(() -> FeatureFeedback.builder()
                .user(user)
                .feature(FeedbackFeature.DATA_IMPORT)
                .referenceId(draft.map(ImportDraft::getId).orElse(null))
                .build());
        feedback.setRating(request.rating());
        feedback.setComment(comment);
        feedback.setContext(draft.map(found -> found.getSourceType() + "/" + found.getStatus()).orElse(null));
        FeatureFeedback saved = feedbackRepository.save(feedback);

        trackSubmission(saved);
        // The comment itself is not logged; only that feedback arrived.
        log.info("Import feedback {} saved for user {} (rating {})", saved.getId(), user.getId(), saved.getRating());
        return new ImportFeedbackResponse(saved.getId(), saved.getRating(), THANKS);
    }

    // ------------------------------------------------------------------ helpers

    private Optional<ImportDraft> findOwnDraft(String draftId, User user) {
        if (draftId == null || draftId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(importDraftRepository.findByIdAndUser(draftId.trim(), user)
                .orElseThrow(() -> new ResourceNotFoundException("Import draft not found")));
    }

    private void enforceDailyLimit(User user) {
        long sentToday = feedbackRepository.countByUserIdAndFeatureAndCreatedAtAfter(
                user.getId(), FeedbackFeature.DATA_IMPORT, LocalDateTime.now().minusHours(24));
        if (sentToday >= properties.getFeedbackDailyLimit()) {
            throw new TooManyRequestsException("You have sent a lot of feedback today. Please try again tomorrow.");
        }
    }

    private String normaliseComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        if (trimmed.length() > properties.getMaxCommentLength()) {
            throw new BadRequestException("comment must be at most " + properties.getMaxCommentLength() + " characters.");
        }
        return trimmed;
    }

    private void trackSubmission(FeatureFeedback feedback) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("feature", feedback.getFeature().name());
        payload.put("rating", feedback.getRating());
        payload.put("hasComment", feedback.getComment() != null);
        payload.put("context", feedback.getContext());
        analyticsService.track("beta_feedback_submitted", payload);
    }
}