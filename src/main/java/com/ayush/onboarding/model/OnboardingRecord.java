package com.ayush.onboarding.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persisted audit + idempotency record for each S3 object processed.
 * The contentHash field acts as the idempotency key to prevent double-processing.
 */
@Entity
@Table(name = "onboarding_records",
       indexes = @Index(name = "idx_content_hash", columnList = "contentHash", unique = true))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnboardingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String contentHash;         // SHA-256 of S3 object content

    @Column(nullable = false)
    private String s3Bucket;

    @Column(nullable = false)
    private String s3Key;

    @Column(nullable = false)
    private String correlationId;       // UUID for log tracing

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessingStatus status;

    private String customerEmail;       // Extracted email for quick lookup
    private String crmCustomerId;       // CRM-assigned ID after successful write
    private String errorMessage;        // Last error (if status = FAILED)

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
