package com.ayush.onboarding.parser;

import com.ayush.onboarding.model.Customer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Sends raw unstructured content to the Claude API and extracts a validated Customer object.
 *
 * Strategy:
 * 1. First attempt: standard extraction prompt
 * 2. If validation fails: repair prompt with the validation errors fed back to the model
 * 3. After 2 failed extractions: throw exception → record goes to DLQ
 *
 * Using Claude claude-sonnet-4-20250514 for its reliable JSON instruction-following.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmParserAgent {

    private final WebClient anthropicWebClient;
    private final ObjectMapper objectMapper;
    private final CustomerExtractionPrompt promptBuilder;

    @Value("${anthropic.model}")
    private String model;

    @Value("${anthropic.max-tokens}")
    private int maxTokens;

    private static final int MAX_LLM_ATTEMPTS = 2;

    /**
     * Extracts a Customer from raw unstructured text.
     * Retries once with a repair prompt if the first extraction is invalid.
     */
    public Customer extract(String rawContent) {
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
            log.debug("LLM extraction attempt {}/{}", attempt, MAX_LLM_ATTEMPTS);

            String prompt = (attempt == 1)
                    ? promptBuilder.buildExtractionPrompt(rawContent)
                    : promptBuilder.buildRepairPrompt(rawContent, lastError);

            try {
                String jsonResponse = callClaudeApi(prompt);
                Customer customer = parseAndValidate(jsonResponse);
                log.info("LLM extraction succeeded on attempt {} — email={}",
                        attempt, customer.getEmail());
                return customer;
            } catch (ExtractionValidationException e) {
                lastError = e.getMessage();
                log.warn("Extraction attempt {} failed validation: {}", attempt, lastError);
            }
        }

        throw new ExtractionFailedException(
                "LLM extraction failed after " + MAX_LLM_ATTEMPTS + " attempts. Last error: " + lastError);
    }

    private String callClaudeApi(String userPrompt) {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "system", CustomerExtractionPrompt.SYSTEM_PROMPT,
                "messages", List.of(
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        JsonNode response = anthropicWebClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null || !response.has("content")) {
            throw new ExtractionValidationException("Empty response from Claude API");
        }

        // Claude returns content as an array of blocks; get first text block
        return response.path("content").get(0).path("text").asText();
    }

    private Customer parseAndValidate(String jsonText) {
        // Strip any markdown fences the model may have added
        String cleaned = jsonText
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("```", "")
                .trim();

        Customer customer;
        try {
            customer = objectMapper.readValue(cleaned, Customer.class);
        } catch (Exception e) {
            throw new ExtractionValidationException("Cannot parse JSON: " + e.getMessage());
        }

        // Jakarta Bean Validation
        var factory = jakarta.validation.Validation.buildDefaultValidatorFactory();
        var validator = factory.getValidator();
        var violations = validator.validate(customer);

        if (!violations.isEmpty()) {
            String errors = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("unknown");
            throw new ExtractionValidationException("Validation failed: " + errors);
        }

        return customer;
    }

    // ── Inner exceptions ───────────────────────────────────────────────────────

    public static class ExtractionValidationException extends RuntimeException {
        public ExtractionValidationException(String message) { super(message); }
    }

    public static class ExtractionFailedException extends RuntimeException {
        public ExtractionFailedException(String message) { super(message); }
    }
}
