package com.ayush.onboarding.ingestion;

import com.ayush.onboarding.audit.AuditService;
import com.ayush.onboarding.model.OnboardingRecord;
import com.ayush.onboarding.model.ProcessingStatus;
import com.ayush.onboarding.orchestrator.OnboardingOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Polls SQS for S3 event notifications and kicks off the ingestion pipeline.
 *
 * Flow: SQS message → download S3 object → idempotency check → orchestrator
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3SqsConsumer {

    private final SqsClient sqsClient;
    private final S3Client s3Client;
    private final IdempotencyService idempotencyService;
    private final OnboardingOrchestrator orchestrator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Value("${aws.sqs.queue-url}")
    private String queueUrl;

    @Value("${aws.sqs.dlq-url}")
    private String dlqUrl;

    @Value("${aws.sqs.max-messages:10}")
    private int maxMessages;

    /**
     * Long-poll SQS every second. Spring @Scheduled handles the thread pool.
     */
    @Scheduled(fixedDelayString = "${aws.sqs.poll-interval-ms:1000}")
    public void poll() {
        ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages)
                .waitTimeSeconds(5)           // Long poll — reduces empty receives
                .visibilityTimeout(60)        // 60s to finish processing before re-queue
                .build();

        List<Message> messages = sqsClient.receiveMessage(receiveRequest).messages();

        for (Message message : messages) {
            processMessage(message);
        }
    }

    private void processMessage(Message message) {
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);

        try {
            // Parse S3 event notification from SQS body
            JsonNode body = objectMapper.readTree(message.body());
            JsonNode s3Record = body.path("Records").get(0).path("s3");
            String bucket = s3Record.path("bucket").path("name").asText();
            String key = s3Record.path("object").path("key").asText();

            log.info("Processing S3 event: bucket={} key={}", bucket, key);

            // Download object from S3
            byte[] content = downloadS3Object(bucket, key);
            String contentHash = sha256Hex(content);

            // Idempotency check — skip if already processed
            if (idempotencyService.alreadyProcessed(contentHash)) {
                log.info("Duplicate content hash {} — skipping key={}", contentHash, key);
                deleteMessage(message);
                return;
            }

            // Save initial record
            OnboardingRecord record = auditService.createRecord(
                    bucket, key, contentHash, correlationId);

            // Hand off to orchestrator (LLM parse → CRM update)
            orchestrator.process(new String(content, StandardCharsets.UTF_8), record);

            // Success — delete from SQS
            deleteMessage(message);

        } catch (Exception e) {
            log.error("Failed to process SQS message — sending to DLQ: {}", e.getMessage(), e);
            sendToDlq(message, e.getMessage());
            deleteMessage(message);
        } finally {
            MDC.clear();
        }
    }

    private byte[] downloadS3Object(String bucket, String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(request);
        return response.asByteArray();
    }

    private String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        return HexFormat.of().formatHex(hash);
    }

    private void deleteMessage(Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }

    private void sendToDlq(Message message, String errorReason) {
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(dlqUrl)
                .messageBody(message.body())
                .messageAttributes(java.util.Map.of(
                        "errorReason", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(errorReason)
                                .build(),
                        "correlationId", MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(MDC.get("correlationId"))
                                .build()
                ))
                .build());
    }
}
