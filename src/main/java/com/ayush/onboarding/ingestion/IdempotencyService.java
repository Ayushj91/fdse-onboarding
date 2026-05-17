package com.ayush.onboarding.ingestion;

import com.ayush.onboarding.model.OnboardingRecord;
import com.ayush.onboarding.model.ProcessingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Guards against duplicate processing using the SHA-256 content hash as a key.
 * Uses PostgreSQL (OnboardingRecord table) as the durable store — Redis would be
 * faster but less reliable for this critical check.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final OnboardingRecordRepository repository;

    /**
     * Returns true if this exact content was already successfully processed.
     * COMPLETED and DUPLICATE records are considered "already done".
     * FAILED records are re-processed to allow recovery.
     */
    public boolean alreadyProcessed(String contentHash) {
        Optional<OnboardingRecord> existing = repository.findByContentHash(contentHash);
        if (existing.isEmpty()) {
            return false;
        }
        ProcessingStatus status = existing.get().getStatus();
        boolean skip = status == ProcessingStatus.COMPLETED || status == ProcessingStatus.DUPLICATE;
        if (skip) {
            log.debug("Idempotency check: hash={} status={} — skipping", contentHash, status);
        } else {
            log.info("Idempotency check: hash={} status={} — re-processing (previous attempt failed)",
                    contentHash, status);
        }
        return skip;
    }
}
