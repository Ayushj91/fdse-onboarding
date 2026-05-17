package com.ayush.onboarding.model;

public enum ProcessingStatus {
    RECEIVED,       // S3 event consumed, not yet processed
    PARSING,        // LLM extraction in progress
    PARSED,         // Customer object extracted and validated
    CRM_UPDATING,   // Writing to legacy CRM
    COMPLETED,      // Successfully written to CRM
    FAILED,         // All retries exhausted — record in DLQ
    DUPLICATE       // Content hash already processed — skipped
}
