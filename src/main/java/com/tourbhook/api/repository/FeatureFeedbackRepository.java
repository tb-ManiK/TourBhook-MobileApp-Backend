package com.tourbhook.api.repository;

import com.tourbhook.api.entity.FeatureFeedback;
import com.tourbhook.api.entity.FeedbackFeature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface FeatureFeedbackRepository extends JpaRepository<FeatureFeedback, String> {

    Optional<FeatureFeedback> findByUserIdAndFeatureAndReferenceId(String userId, FeedbackFeature feature, String referenceId);

    long countByUserIdAndFeatureAndCreatedAtAfter(String userId, FeedbackFeature feature, LocalDateTime since);
}