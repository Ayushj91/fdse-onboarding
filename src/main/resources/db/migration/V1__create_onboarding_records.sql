-- V1__create_onboarding_records.sql
-- Initial schema for idempotency store and audit log

CREATE TABLE onboarding_records (
    id               BIGSERIAL PRIMARY KEY,
    content_hash     VARCHAR(64)  NOT NULL UNIQUE,   -- SHA-256 hex, idempotency key
    s3_bucket        VARCHAR(255) NOT NULL,
    s3_key           VARCHAR(1024) NOT NULL,
    correlation_id   VARCHAR(36)  NOT NULL UNIQUE,   -- UUID for log correlation
    status           VARCHAR(20)  NOT NULL,
    customer_email   VARCHAR(255),
    crm_customer_id  VARCHAR(100),
    error_message    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMPTZ
);

CREATE INDEX idx_onboarding_content_hash ON onboarding_records (content_hash);
CREATE INDEX idx_onboarding_correlation_id ON onboarding_records (correlation_id);
CREATE INDEX idx_onboarding_status ON onboarding_records (status);
CREATE INDEX idx_onboarding_customer_email ON onboarding_records (customer_email);
