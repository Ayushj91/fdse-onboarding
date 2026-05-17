package com.ayush.onboarding.parser;

import org.springframework.stereotype.Component;

/**
 * Prompt templates for the LLM parser agent.
 *
 * Design notes:
 * - System prompt enforces JSON-only output (no prose, no markdown)
 * - Schema is embedded in the prompt so the model has a contract to follow
 * - Repair prompt feeds validation errors back to the model for self-correction
 * - Confidence score (0.0-1.0) lets downstream code route low-confidence records
 *   to a human review queue without blocking the pipeline
 */
@Component
public class CustomerExtractionPrompt {

    public static final String SYSTEM_PROMPT = """
            You are a data extraction assistant. Your job is to extract structured customer \
            information from unstructured text documents. These documents may be emails, \
            PDF extracts, CSV rows, or free-form notes.

            RULES:
            - Respond ONLY with a valid JSON object matching the schema below. No prose, no markdown fences.
            - If a field is not present in the document, use null.
            - email is required. If you cannot find a valid email address, set extractionConfidence to 0.0.
            - extractionConfidence is a float between 0.0 and 1.0 representing how confident you are.
            - Do not invent or hallucinate values. Only extract what is explicitly in the document.

            JSON SCHEMA:
            {
              "firstName": "string | null",
              "lastName": "string | null",
              "email": "string (required, must be valid email format)",
              "phone": "string | null",
              "company": "string | null",
              "address": "string | null",
              "city": "string | null",
              "country": "string | null",
              "planTier": "FREE | PRO | ENTERPRISE | null",
              "extractionConfidence": "float 0.0-1.0"
            }
            """;

    public String buildExtractionPrompt(String rawContent) {
        return """
                Extract customer information from the following document.
                Respond with only the JSON object.

                DOCUMENT:
                ---
                %s
                ---
                """.formatted(rawContent);
    }

    public String buildRepairPrompt(String rawContent, String validationErrors) {
        return """
                Your previous extraction attempt failed schema validation with these errors:
                %s

                Please re-extract from the document below, fixing the errors.
                Respond with only the corrected JSON object.

                DOCUMENT:
                ---
                %s
                ---
                """.formatted(validationErrors, rawContent);
    }
}
