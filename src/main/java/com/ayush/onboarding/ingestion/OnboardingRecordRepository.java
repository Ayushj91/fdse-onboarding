package com.ayush.onboarding.ingestion;

import com.ayush.onboarding.model.OnboardingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OnboardingRecordRepository extends JpaRepository<OnboardingRecord, Long> {
    Optional<OnboardingRecord> findByContentHash(String contentHash);
    Optional<OnboardingRecord> findByCorrelationId(String correlationId);
}
