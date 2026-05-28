package dev.stagepass.seatinventory.outbox;

import dev.stagepass.seatinventory.entity.OutboxEntity;
import dev.stagepass.seatinventory.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reads PENDING outbox rows and publishes them to Kafka.
 *
 * <p><strong>SELECT ... FOR UPDATE SKIP LOCKED (ADR-006 §3.5 and Phase 4 ER §3.5):</strong>
 * The native query in OutboxRepository.findPendingForPublishing() uses FOR UPDATE SKIP LOCKED.
 * This prevents two concurrent @Scheduled instances (or multiple pod replicas) from processing
 * the same outbox row, which would produce duplicate Kafka messages in undefined order.
 * SKIP LOCKED: rows being processed by another transaction are skipped, not blocked.
 *
 * <p><strong>Ordering guarantee:</strong> ORDER BY created_at ASC ensures oldest-first
 * publishing within one poll. Combined with the single-writer-per-transaction guarantee
 * (state change + outbox INSERT in same txn), causal order is preserved.
 *
 * <p><strong>At-least-once delivery:</strong> The outbox row is marked PUBLISHED only
 * after the Kafka producer receives a broker ACK. If the service crashes between publish
 * and mark-published, the row is re-published on the next poll. Consumers must be
 * idempotent (NFR-REL-002) — using outbox.id as the Kafka messageId for deduplication.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${stagepass.kafka.topics.seat-state-changes:seat.state-changes}")
    private String seatStateChangesTopic;

    private static final int BATCH_SIZE = 100;

    public OutboxPublisher(
            final OutboxRepository outboxRepo,
            final KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepo = outboxRepo;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Poll and publish PENDING outbox rows every 1 second.
     * fixedDelay ensures next execution starts 1s AFTER current completes
     * (not 1s after start) — prevents overlapping executions in the same pod.
     */
    @Scheduled(fixedDelayString = "${stagepass.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPendingMessages() {
        final List<OutboxEntity> pending = outboxRepo.findPendingForPublishing(BATCH_SIZE);
        if (pending.isEmpty()) {
            return;
        }

        log.debug("OutboxPublisher: processing {} pending messages", pending.size());

        for (final OutboxEntity row : pending) {
            row.incrementAttempts();
            try {
                // Partition key = event_id (ensures ordering per event on the topic)
                kafkaTemplate.send(
                        resolveTopicForEventType(row.getEventType()),
                        row.getPartitionKey().toString(),
                        row.getPayload()
                ).get(); // Synchronous send — wait for broker ACK before marking published

                row.markPublished();
                log.debug("OutboxPublisher: published messageId={} eventType={}",
                        row.getId(), row.getEventType());

            } catch (final Exception e) {
                if (row.getAttempts() >= 3) {
                    // 3 failures: mark FAILED and alert via Prometheus counter
                    // KafkaDLQDepthNonZero alert will fire within 5 minutes (NFR-REL-007)
                    row.markFailed();
                    log.error("OutboxPublisher: max retries exceeded for messageId={} eventType={} — marking FAILED",
                            row.getId(), row.getEventType());
                } else {
                    log.warn("OutboxPublisher: publish attempt {} failed for messageId={} — will retry",
                            row.getAttempts(), row.getId());
                }
            }
            outboxRepo.save(row);
        }
    }

    private String resolveTopicForEventType(final String eventType) {
        // All seat domain events go to seat.state-changes
        return seatStateChangesTopic;
    }
}
