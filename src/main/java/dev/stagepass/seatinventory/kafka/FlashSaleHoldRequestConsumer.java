package dev.stagepass.seatinventory.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stagepass.seatinventory.redis.RedisKeySchema;
import dev.stagepass.seatinventory.service.HoldSeatsService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

/**
 * Consumes flash-sale.hold-requests and executes hold logic via the same
 * Redis Lua script used by the gRPC path (ADR-006 §3.3).
 *
 * <p><strong>Flash sale queue invariant (ADR-007):</strong>
 * The Booking Service publishes to this topic when flash sale mode is active.
 * Partition key = eventId → all requests for one event land on the same partition →
 * processed by the same consumer thread → total ordering per event.
 *
 * <p><strong>Consumer group:</strong> seat-inventory-service-consumer
 * Configured in application.yml as the default consumer group ID.
 * 24 partitions = 24 consumer threads can run concurrently without cross-event interference.
 *
 * <p><strong>Idempotency (ADR-006 §3.3 step 1):</strong>
 * Before executing the Lua script, check Redis flash-hold-processed:{bookingId}.
 * If present, re-publish the original result and commit offset without re-execution.
 * This prevents duplicate processing on consumer rebalance (Kafka at-least-once delivery,
 * NFR-REL-002).
 *
 * <p><strong>Result publishing:</strong> Result goes to flash-sale.hold-results (NOT
 * seat.commands — the AsyncAPI spec flash-sale.yaml is authoritative over the ADR-006
 * text which mentions seat.commands; that appears to be a typo in the ADR).
 */
@Component
public class FlashSaleHoldRequestConsumer {

    private static final Logger log =
            LoggerFactory.getLogger(FlashSaleHoldRequestConsumer.class);

    private final HoldSeatsService holdSeatsService;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${stagepass.kafka.topics.flash-sale-hold-results:flash-sale.hold-results}")
    private String holdResultsTopic;

    @Value("${stagepass.redis.idempotency-key-ttl-seconds:3600}")
    private long idempotencyTtlSeconds;

    public FlashSaleHoldRequestConsumer(
            final HoldSeatsService holdSeatsService,
            final StringRedisTemplate redis,
            final ObjectMapper objectMapper) {
        this.holdSeatsService = holdSeatsService;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${stagepass.kafka.topics.flash-sale-hold-requests:flash-sale.hold-requests}",
            groupId = "seat-inventory-service-consumer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeHoldRequest(
            final ConsumerRecord<String, String> record,
            final Acknowledgment acknowledgment) {

        UUID bookingId = null;
        try {
            final JsonNode payload = objectMapper.readTree(record.value());
            bookingId = UUID.fromString(payload.get("bookingId").asText());
            final UUID eventId   = UUID.fromString(payload.get("eventId").asText());
            final UUID customerId = UUID.fromString(payload.get("customerId").asText());
            final List<UUID> seatIds = StreamSupport.stream(
                    payload.get("seatIds").spliterator(), false)
                    .map(n -> UUID.fromString(n.asText()))
                    .toList();

            // ── Idempotency check ────────────────────────────────────────
            final String idempotencyKey = RedisKeySchema.flashHoldProcessedKey(bookingId);
            final String previousResult = redis.opsForValue().get(idempotencyKey);
            if (previousResult != null) {
                log.info("FlashSale idempotency hit — bookingId={} previousResult={}",
                        bookingId, previousResult);
                // TODO Phase 4 impl: re-publish original result to flash-sale.hold-results
                acknowledgment.acknowledge();
                return;
            }

            // ── Execute hold (same Redis Lua path as gRPC) ───────────────
            final HoldSeatsService.HoldResult result =
                    holdSeatsService.holdSeats(bookingId, eventId, customerId, seatIds, "FLASH_SALE");

            // ── Mark idempotency key ─────────────────────────────────────
            final String resultValue = result.success() ? "success"
                    : "failed:" + result.unavailableSeats();
            redis.opsForValue().set(
                    idempotencyKey, resultValue, Duration.ofSeconds(idempotencyTtlSeconds));

            // ── Publish result to flash-sale.hold-results ─────────────────
            // TODO Phase 4 impl: publish to holdResultsTopic with customerId as partition key
            log.info("FlashSale hold {} — bookingId={} result={}", result.success() ? "SUCCESS" : "UNAVAILABLE",
                    bookingId, resultValue);

            acknowledgment.acknowledge();

        } catch (final Exception e) {
            // Do NOT acknowledge — Kafka will redeliver. After 3 retries, ErrorHandler sends to DLQ.
            log.error("FlashSale hold processing failed — bookingId={} partition={} offset={} cause={}",
                    bookingId, record.partition(), record.offset(), e.getMessage(), e);
            // Acknowledgment withheld — ErrorHandlingDeserializer + SeekToCurrentErrorHandler
            // will retry, then publish to flash-sale.hold-requests.dlq after 3 attempts.
        }
    }
}
