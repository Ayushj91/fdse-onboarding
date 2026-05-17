package com.ayush.onboarding.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured customer record extracted by the LLM parser from unstructured S3 data.
 * Jakarta Validation annotations enforce schema correctness before CRM update.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    private String phone;
    private String company;
    private String address;
    private String city;
    private String country;
    private String planTier;        // e.g. FREE, PRO, ENTERPRISE

    // Raw LLM confidence score (0.0 - 1.0) for audit / review queue routing
    private double extractionConfidence;
}
