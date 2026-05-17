package com.ayush.onboarding.crm;

import com.ayush.onboarding.model.Customer;
import com.ayush.onboarding.resilience.RateLimitHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Resilient client for the legacy CRM REST API.
 *
 * Resilience stack (applied in order):
 * 1. CircuitBreaker — opens after sustained failures, preventing thundering herd
 * 2. Retry — exponential backoff with jitter for transient errors
 * 3. Rate limit handler — respects Retry-After header on 429s
 * 4. Idempotency key — ensures safe retries on CREATE operations
 *
 * The CRM API is undocumented, so this client assumes:
 * - GET /customers?email={email} → search by email
 * - POST /customers → create customer (returns { "id": "..." })
 * - PUT /customers/{id} → update customer
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrmClient {

    private final WebClient crmWebClient;
    private final RateLimitHandler rateLimitHandler;
    private final ObjectMapper objectMapper;

    @Value("${crm.base-url}")
    private String baseUrl;

    /**
     * Search for an existing customer by email.
     * Returns the CRM customer ID if found, empty if not found.
     */
    @Retry(name = "crm-retry")
    @CircuitBreaker(name = "crm-cb", fallbackMethod = "searchFallback")
    public Optional<String> searchByEmail(String email) {
        log.debug("CRM search: email={}", email);

        try {
            JsonNode response = crmWebClient.get()
                    .uri("/customers?email={email}", email)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response != null && response.has("id")) {
                return Optional.of(response.get("id").asText());
            }
            return Optional.empty();

        } catch (WebClientResponseException e) {
            return handleHttpError(e, "search");
        }
    }

    /**
     * Create a new customer in the CRM.
     * Includes Idempotency-Key header so retries are safe.
     *
     * @param idempotencyKey SHA-256 hash of the source S3 object — prevents duplicates on retry
     */
    @Retry(name = "crm-retry")
    @CircuitBreaker(name = "crm-cb", fallbackMethod = "createFallback")
    public String createCustomer(Customer customer, String idempotencyKey) {
        log.info("CRM create: email={} idempotencyKey={}", customer.getEmail(), idempotencyKey);

        try {
            Map<String, Object> payload = buildPayload(customer);

            JsonNode response = crmWebClient.post()
                    .uri("/customers")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (response == null || !response.has("id")) {
                throw new CrmApiException("CRM create returned no ID in response");
            }

            String crmId = response.get("id").asText();
            log.info("CRM create succeeded: crmId={}", crmId);
            return crmId;

        } catch (WebClientResponseException e) {
            return handleHttpError(e, "create");
        }
    }

    /**
     * Update an existing customer record.
     */
    @Retry(name = "crm-retry")
    @CircuitBreaker(name = "crm-cb", fallbackMethod = "updateFallback")
    public void updateCustomer(String crmId, Customer customer) {
        log.info("CRM update: crmId={} email={}", crmId, customer.getEmail());

        try {
            Map<String, Object> payload = buildPayload(customer);

            crmWebClient.put()
                    .uri("/customers/{id}", crmId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("CRM update succeeded: crmId={}", crmId);

        } catch (WebClientResponseException e) {
            handleHttpError(e, "update");
        }
    }

    // ── HTTP error handling ────────────────────────────────────────────────────

    private <T> T handleHttpError(WebClientResponseException e, String operation) {
        int status = e.getStatusCode().value();

        if (status == 429) {
            // Rate limited — check Retry-After header and back off
            String retryAfter = e.getHeaders().getFirst("Retry-After");
            Duration waitTime = rateLimitHandler.handleRateLimitResponse(retryAfter);
            log.warn("CRM rate limited (429) on {}. Retry-After={}s — waiting {}",
                    operation, retryAfter, waitTime);
            // Throw retryable exception so Resilience4j retry kicks in after the wait
            throw new CrmApiException("Rate limited: " + e.getMessage(), true);
        }

        if (status >= 500) {
            log.error("CRM server error ({}) on {}: {}", status, operation, e.getMessage());
            throw new CrmApiException("CRM server error " + status, true);  // retryable
        }

        if (status == 404 && "search".equals(operation)) {
            return (T) Optional.empty();  // 404 on search = not found, not an error
        }

        // 4xx (not 429, not 404) — validation error, don't retry
        log.error("CRM client error ({}) on {}: {}", status, operation, e.getMessage());
        throw new CrmValidationException("CRM rejected request: " + e.getResponseBodyAsString());
    }

    // ── Fallback methods (Circuit Breaker open) ───────────────────────────────

    public Optional<String> searchFallback(String email, Exception e) {
        log.error("Circuit breaker open — CRM search unavailable for email={}: {}", email, e.getMessage());
        throw new CrmApiException("CRM circuit breaker open — search unavailable");
    }

    public String createFallback(Customer customer, String idempotencyKey, Exception e) {
        log.error("Circuit breaker open — CRM create unavailable: {}", e.getMessage());
        throw new CrmApiException("CRM circuit breaker open — create unavailable");
    }

    public void updateFallback(String crmId, Customer customer, Exception e) {
        log.error("Circuit breaker open — CRM update unavailable for id={}: {}", crmId, e.getMessage());
        throw new CrmApiException("CRM circuit breaker open — update unavailable");
    }

    // ── Payload builder ───────────────────────────────────────────────────────

    private Map<String, Object> buildPayload(Customer customer) {
        return Map.of(
                "firstName", nullSafe(customer.getFirstName()),
                "lastName", nullSafe(customer.getLastName()),
                "email", customer.getEmail(),
                "phone", nullSafe(customer.getPhone()),
                "company", nullSafe(customer.getCompany()),
                "address", nullSafe(customer.getAddress()),
                "city", nullSafe(customer.getCity()),
                "country", nullSafe(customer.getCountry()),
                "planTier", nullSafe(customer.getPlanTier())
        );
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
