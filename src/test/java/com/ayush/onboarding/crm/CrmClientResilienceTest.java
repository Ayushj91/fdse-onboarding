package com.ayush.onboarding.crm;

import com.ayush.onboarding.model.Customer;
import com.ayush.onboarding.resilience.RateLimitHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resilience tests for CrmClient using WireMock to simulate:
 * - Rate limiting (429 with Retry-After header)
 * - Server errors (500) followed by eventual success
 * - Circuit breaker opening after sustained failures
 * - Idempotency key on retried CREATE requests
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrmClientResilienceTest {

    static WireMockServer wireMock = new WireMockServer(
            WireMockConfiguration.options().port(8089));

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("crm.base-url", () -> "http://localhost:8089");
    }

    @Autowired
    CrmClient crmClient;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeAll
    static void startWireMock() {
        wireMock.start();
        WireMock.configureFor("localhost", 8089);
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
        // Reset circuit breaker state between tests
        circuitBreakerRegistry.circuitBreaker("crm-cb").reset();
    }

    // ── Test 1: Successful create ─────────────────────────────────────────────

    @Test
    @Order(1)
    void createCustomer_success() {
        stubFor(post(urlEqualTo("/customers"))
                .willReturn(okJson("{\"id\": \"crm-001\"}")));

        Customer customer = sampleCustomer();
        String crmId = crmClient.createCustomer(customer, "hash-abc");

        assertThat(crmId).isEqualTo("crm-001");
        verify(1, postRequestedFor(urlEqualTo("/customers")));
    }

    // ── Test 2: Retry on 500, succeeds on 3rd attempt ────────────────────────

    @Test
    @Order(2)
    void createCustomer_retriesOn500_eventuallySucceeds() {
        stubFor(post(urlEqualTo("/customers"))
                .inScenario("retry-scenario")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError())
                .willSetStateTo("first-fail"));

        stubFor(post(urlEqualTo("/customers"))
                .inScenario("retry-scenario")
                .whenScenarioStateIs("first-fail")
                .willReturn(serverError())
                .willSetStateTo("second-fail"));

        stubFor(post(urlEqualTo("/customers"))
                .inScenario("retry-scenario")
                .whenScenarioStateIs("second-fail")
                .willReturn(okJson("{\"id\": \"crm-002\"}")));

        Customer customer = sampleCustomer();
        String crmId = crmClient.createCustomer(customer, "hash-def");

        assertThat(crmId).isEqualTo("crm-002");
        // Should have been called 3 times (2 failures + 1 success)
        verify(3, postRequestedFor(urlEqualTo("/customers")));
    }

    // ── Test 3: Idempotency key present on all retry attempts ────────────────

    @Test
    @Order(3)
    void createCustomer_idempotencyKeyPresentOnRetries() {
        stubFor(post(urlEqualTo("/customers"))
                .inScenario("idempotency-test")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError())
                .willSetStateTo("retry"));

        stubFor(post(urlEqualTo("/customers"))
                .inScenario("idempotency-test")
                .whenScenarioStateIs("retry")
                .willReturn(okJson("{\"id\": \"crm-003\"}")));

        String idempotencyKey = "unique-hash-xyz";
        crmClient.createCustomer(sampleCustomer(), idempotencyKey);

        // Both the original and retry must carry the same Idempotency-Key
        verify(2, postRequestedFor(urlEqualTo("/customers"))
                .withHeader("Idempotency-Key", equalTo(idempotencyKey)));
    }

    // ── Test 4: Rate limit (429) with Retry-After handling ───────────────────

    @Test
    @Order(4)
    void searchByEmail_handlesRateLimit_thenSucceeds() {
        stubFor(get(urlPathEqualTo("/customers"))
                .inScenario("rate-limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(status(429)
                        .withHeader("Retry-After", "1")
                        .withBody("Rate limit exceeded"))
                .willSetStateTo("after-backoff"));

        stubFor(get(urlPathEqualTo("/customers"))
                .inScenario("rate-limit")
                .whenScenarioStateIs("after-backoff")
                .willReturn(okJson("{\"id\": \"crm-004\", \"email\": \"test@example.com\"}")));

        Optional<String> result = crmClient.searchByEmail("test@example.com");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("crm-004");
    }

    // ── Test 5: Circuit breaker opens after sustained failures ────────────────

    @Test
    @Order(5)
    void circuitBreaker_opensAfterSustainedFailures() {
        // Return 500 for all requests
        stubFor(post(urlEqualTo("/customers"))
                .willReturn(serverError()));

        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("crm-cb");

        // Exhaust retries enough times to open the circuit breaker
        // CB configured: 50% failure rate in 20-call window
        for (int i = 0; i < 25; i++) {
            try {
                crmClient.createCustomer(sampleCustomer(), "hash-" + i);
            } catch (Exception ignored) {
                // Expected failures
            }
        }

        assertThat(cb.getState())
                .as("Circuit breaker should be OPEN after sustained failures")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ── Test 6: 404 on search = not found (not an error) ─────────────────────

    @Test
    @Order(6)
    void searchByEmail_notFound_returnsEmpty() {
        stubFor(get(urlPathEqualTo("/customers"))
                .willReturn(notFound()));

        Optional<String> result = crmClient.searchByEmail("unknown@example.com");

        assertThat(result).isEmpty();
    }

    // ── Test 7: 400 throws non-retryable exception ───────────────────────────

    @Test
    @Order(7)
    void createCustomer_badRequest_throwsValidationException_notRetried() {
        stubFor(post(urlEqualTo("/customers"))
                .willReturn(badRequest().withBody("Invalid email format")));

        assertThatThrownBy(() -> crmClient.createCustomer(sampleCustomer(), "hash-xyz"))
                .isInstanceOf(CrmValidationException.class);

        // Should only have been called once — validation errors are not retried
        verify(1, postRequestedFor(urlEqualTo("/customers")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Customer sampleCustomer() {
        return Customer.builder()
                .firstName("Ayush")
                .lastName("Sharma")
                .email("ayush@example.com")
                .company("Acme Corp")
                .planTier("PRO")
                .extractionConfidence(0.95)
                .build();
    }
}
