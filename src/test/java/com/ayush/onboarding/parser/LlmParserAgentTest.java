package com.ayush.onboarding.parser;

import com.ayush.onboarding.model.Customer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests LlmParserAgent against a mocked Anthropic API (WireMock).
 * Covers: successful extraction, repair on validation failure, final failure → exception.
 */
@SpringBootTest
class LlmParserAgentTest {

    static WireMockServer wireMock = new WireMockServer(
            WireMockConfiguration.options().port(8090));

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("anthropic.api-url", () -> "http://localhost:8090/v1/messages");
    }

    @Autowired
    LlmParserAgent llmParserAgent;

    @BeforeAll
    static void start() { wireMock.start(); configureFor("localhost", 8090); }

    @AfterAll
    static void stop() { wireMock.stop(); }

    @BeforeEach
    void reset() { wireMock.resetAll(); }

    // ── Test 1: Clean extraction on first attempt ─────────────────────────────

    @Test
    void extract_success_firstAttempt() {
        String claudeResponse = claudeApiResponse("""
            {
              "firstName": "Jane",
              "lastName": "Doe",
              "email": "jane.doe@acme.com",
              "phone": "+1-555-1234",
              "company": "Acme Corp",
              "planTier": "ENTERPRISE",
              "extractionConfidence": 0.95
            }
            """);

        stubFor(post(urlEqualTo("/v1/messages")).willReturn(okJson(claudeResponse)));

        Customer customer = llmParserAgent.extract("Jane Doe, jane.doe@acme.com, ENTERPRISE plan");

        assertThat(customer.getEmail()).isEqualTo("jane.doe@acme.com");
        assertThat(customer.getFirstName()).isEqualTo("Jane");
        assertThat(customer.getPlanTier()).isEqualTo("ENTERPRISE");
        assertThat(customer.getExtractionConfidence()).isEqualTo(0.95);

        verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    // ── Test 2: Bad JSON on first attempt, valid JSON on repair attempt ────────

    @Test
    void extract_repairSucceeds_onSecondAttempt() {
        // First attempt: returns invalid JSON (missing required email)
        String badResponse = claudeApiResponse("""
            {
              "firstName": "Bob",
              "lastName": "Smith",
              "extractionConfidence": 0.4
            }
            """);

        // Second attempt (repair): returns valid JSON
        String goodResponse = claudeApiResponse("""
            {
              "firstName": "Bob",
              "lastName": "Smith",
              "email": "bob.smith@example.com",
              "extractionConfidence": 0.85
            }
            """);

        stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("repair")
                .whenScenarioStateIs("Started")
                .willReturn(okJson(badResponse))
                .willSetStateTo("repaired"));

        stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("repair")
                .whenScenarioStateIs("repaired")
                .willReturn(okJson(goodResponse)));

        Customer customer = llmParserAgent.extract("Bob Smith at example company");

        assertThat(customer.getEmail()).isEqualTo("bob.smith@example.com");
        verify(2, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    // ── Test 3: Both attempts fail → ExtractionFailedException ───────────────

    @Test
    void extract_allAttemptsFail_throwsException() {
        String invalidResponse = claudeApiResponse("{ \"firstName\": \"NoEmail\" }");

        stubFor(post(urlEqualTo("/v1/messages")).willReturn(okJson(invalidResponse)));

        assertThatThrownBy(() -> llmParserAgent.extract("Garbage document with no email"))
                .isInstanceOf(LlmParserAgent.ExtractionFailedException.class)
                .hasMessageContaining("2 attempts");

        verify(2, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    // ── Helper: wrap content in Claude API response envelope ──────────────────

    private String claudeApiResponse(String content) {
        return """
            {
              "id": "msg_test",
              "type": "message",
              "role": "assistant",
              "content": [
                { "type": "text", "text": %s }
              ],
              "model": "claude-sonnet-4-20250514",
              "stop_reason": "end_turn"
            }
            """.formatted("\"" + content.replace("\"", "\\\"").replace("\n", "\\n") + "\"");
    }
}
