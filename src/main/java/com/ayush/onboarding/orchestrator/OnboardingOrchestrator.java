package com.ayush.onboarding.orchestrator;

import com.ayush.onboarding.audit.AuditService;
import com.ayush.onboarding.crm.CrmClient;
import com.ayush.onboarding.model.Customer;
import com.ayush.onboarding.model.OnboardingRecord;
import com.ayush.onboarding.model.ProcessingStatus;
import com.ayush.onboarding.parser.LlmParserAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Orchestrates the full onboarding pipeline for a single S3 object.
 *
 * Acts as a simple agentic loop:
 * 1. Parse raw content via LLM
 * 2. Search CRM to decide action (tool: crm.search)
 * 3. Execute action — CREATE or UPDATE (tools: crm.create / crm.update)
 * 4. Update audit record
 *
 * Exceptions bubble up to S3SqsConsumer which handles DLQ routing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingOrchestrator {

    private final LlmParserAgent llmParser;
    private final CrmClient crmClient;
    private final AuditService auditService;

    private static final double CONFIDENCE_REVIEW_THRESHOLD = 0.6;

    public void process(String rawContent, OnboardingRecord record) {
        log.info("Orchestrator starting — correlationId={}", record.getCorrelationId());

        // Step 1: Parse with LLM
        auditService.updateStatus(record, ProcessingStatus.PARSING);
        Customer customer = llmParser.extract(rawContent);

        log.info("Extracted customer email={} confidence={}",
                customer.getEmail(), customer.getExtractionConfidence());

        // Low-confidence records still proceed but are flagged in the audit log
        if (customer.getExtractionConfidence() < CONFIDENCE_REVIEW_THRESHOLD) {
            log.warn("Low extraction confidence ({}) for correlationId={} — flagging for review",
                    customer.getExtractionConfidence(), record.getCorrelationId());
        }

        auditService.updateStatus(record, ProcessingStatus.PARSED);
        auditService.setCustomerEmail(record, customer.getEmail());

        // Step 2: Search CRM to decide action (agentic tool call)
        auditService.updateStatus(record, ProcessingStatus.CRM_UPDATING);
        Optional<String> existingCrmId = crmClient.searchByEmail(customer.getEmail());

        String crmCustomerId;
        if (existingCrmId.isPresent()) {
            // Tool: crm.update
            log.info("Customer exists in CRM (id={}) — updating", existingCrmId.get());
            crmClient.updateCustomer(existingCrmId.get(), customer);
            crmCustomerId = existingCrmId.get();
        } else {
            // Tool: crm.create
            log.info("Customer not found in CRM — creating");
            crmCustomerId = crmClient.createCustomer(customer, record.getContentHash());
        }

        // Step 3: Mark completed
        auditService.markCompleted(record, crmCustomerId);
        log.info("Orchestrator completed — correlationId={} crmId={}",
                record.getCorrelationId(), crmCustomerId);
    }
}
