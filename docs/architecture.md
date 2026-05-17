# Architecture Decisions

## 1. LLM Parser vs Rule-Based Extraction

**Chosen:** LLM (Claude claude-sonnet-4-20250514)

**Why:**
The unstructured data in S3 can be anything — PDFs, emails, CSV dumps, JSON blobs, handwritten notes in TXT files. Rule-based approaches (regex, field mappers) would require a separate extractor per document type, and the legacy CRM's "undocumented" nature suggests the source data is equally varied.

An LLM with a strict JSON output contract gives us:
- Single extraction path for all document types
- Self-correction via repair prompt (instead of complex fallback logic)
- Confidence scoring for routing low-quality records to human review
- Much faster iteration — prompt engineering vs. code changes

**Tradeoff:** Latency (1–3s per extraction) and cost (API call per document). Acceptable for onboarding volumes; would revisit for >10k documents/day.

---

## 2. Resilience4j vs Spring Retry

**Chosen:** Resilience4j

**Why:**
Spring Retry (`@Retryable`) is simpler but only handles retries. For a legacy API that may rate-limit, fail, or become entirely unavailable:
- We need **Retry** + **CircuitBreaker** + **RateLimiter** as composable units
- Resilience4j composes them cleanly with separate configuration per instance
- CircuitBreaker prevents hammering a struggling CRM — critical for undocumented APIs that may not handle thundering herd

Spring Retry would require manual circuit breaker implementation.

---

## 3. SQS vs Kafka

**Chosen:** AWS SQS

**Why:**
| Concern | SQS | Kafka |
|---------|-----|-------|
| Delivery guarantee | At-least-once ✓ | At-least-once ✓ |
| DLQ support | Native, zero config ✓ | Manual (KTable + DLQ topic) |
| Ordering | Per-FIFO queue | Per-partition |
| Replay | No (DLQ only) | Yes |
| Ops overhead | Zero | High (ZooKeeper/KRaft, cluster mgmt) |
| Cost | Pay-per-message | EC2 + EBS always-on |

This is an event-driven onboarding workflow, not a streaming pipeline. We need reliable delivery and DLQ, not stream processing or replay. SQS is operationally zero-cost to run and has native integration with S3 event notifications.

Kafka would be the right choice if we needed: event replay, exactly-once semantics across many consumers, or stream processing on the data.

---

## 4. Idempotency Strategy

**Chosen:** SHA-256 content hash stored in PostgreSQL

**Why:**
- Hash is computed from the S3 object content, not the key — protects against re-uploads with different names but identical content
- PostgreSQL gives ACID guarantees; Redis TTL could expire and allow re-processing
- The `Idempotency-Key` header on CRM CREATE requests ensures the CRM itself doesn't create duplicates even if our service retries after a timeout (where we don't know if the CRM actually saved the record)

---

## 5. Schema Validation Approach

**Two-layer validation:**
1. **LLM contract** — system prompt embeds a JSON schema; model is instructed to follow it
2. **Jakarta Bean Validation** — `@NotBlank`, `@Email` etc. on the `Customer` DTO

If layer 2 fails, we feed the exact violation messages back to the LLM as a repair prompt. This self-correction loop covers cases where the model misses a required field or formats an email incorrectly.

Only after 2 failed LLM attempts does the record go to the DLQ — at which point the document probably has genuinely missing data that needs human review.

---

## 6. Observability

**Correlation ID via SLF4J MDC**

Every SQS message gets a UUID correlation ID at the point of ingestion. This is placed in the SLF4J MDC (`correlationId`) and flows through every log line — ingestion → LLM call → CRM call → audit write — without passing it through method signatures everywhere.

Format: `2025-01-01 12:00:00 [abc-123-def] INFO CrmClient - CRM create succeeded`

This makes tracing a single customer's journey through the system trivial even without a distributed tracing backend.
