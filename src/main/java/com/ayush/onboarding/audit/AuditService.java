package com.ayush.onboarding.audit;

import com.ayush.onboarding.ingestion.OnboardingRecordRepository;
import com.ayush.onboarding.model.OnboardingRecord;
import com.ayush.onboarding.model.ProcessingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Manages audit trail for every onboarding record.
 * Every state transition is persisted — enables replay, debugging, and reporting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final OnboardingRecordRepository repository;

    @Transactional
    public OnboardingRecord createRecord(String bucket, String key,
                                          String contentHash, String correlationId) {
        OnboardingRecord record = OnboardingRecord.builder()
                .s3Bucket(bucket)
                .s3Key(key)
                .contentHash(contentHash)
                .correlationId(correlationId)
                .status(ProcessingStatus.RECEIVED)
                .build();
        return repository.save(record);
    }

    @Transactional
    public void updateStatus(OnboardingRecord record, ProcessingStatus status) {
        record.setStatus(status);
        repository.save(record);
        log.debug("Record {} → {}", record.getCorrelationId(), status);
    }

    @Transactional
    public void setCustomerEmail(OnboardingRecord record, String email) {
        record.setCustomerEmail(email);
        repository.save(record);
    }

    @Transactional
    public void markCompleted(OnboardingRecord record, String crmCustomerId) {
        record.setStatus(ProcessingStatus.COMPLETED);
        record.setCrmCustomerId(crmCustomerId);
        record.setCompletedAt(Instant.now());
        repository.save(record);
        log.info("AUDIT COMPLETED correlationId={} crmId={} email={}",
                record.getCorrelationId(), crmCustomerId, record.getCustomerEmail());
    }

    @Transactional
    public void markFailed(OnboardingRecord record, String errorMessage) {
        record.setStatus(ProcessingStatus.FAILED);
        record.setErrorMessage(errorMessage);
        record.setCompletedAt(Instant.now());
        repository.save(record);
        log.error("AUDIT FAILED correlationId={} error={}", record.getCorrelationId(), errorMessage);
    }
}
