package com.ayush.onboarding.crm;

/**
 * Thrown for 4xx validation errors from the CRM (not retryable).
 * Resilience4j config lists this in ignore-exceptions so it won't trigger retries.
 */
public class CrmValidationException extends RuntimeException {
    public CrmValidationException(String message) {
        super(message);
    }
}
