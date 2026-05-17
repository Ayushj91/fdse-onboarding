package com.ayush.onboarding.crm;

/**
 * Thrown for CRM API errors. retryable=true signals Resilience4j to attempt retry.
 */
public class CrmApiException extends RuntimeException {
    private final boolean retryable;

    public CrmApiException(String message) {
        super(message);
        this.retryable = true;
    }

    public CrmApiException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
