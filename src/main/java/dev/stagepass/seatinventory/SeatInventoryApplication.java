package dev.stagepass.seatinventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StagePass Seat Inventory Service — T1 Critical
 *
 * <p>gRPC-only service. No REST controllers. Two interfaces:
 * <ol>
 *   <li>gRPC server on port 9090: CheckSeatAvailability, HoldSeats, ExtendHold,
 *       CommitSeats, ReleaseSeats (ADR-005 §3.10, ADR-006 §3.2)</li>
 *   <li>Kafka consumer: flash-sale.hold-requests (seat-inventory-service-consumer)</li>
 * </ol>
 *
 * <p>Two-store architecture (ADR-006 §3.1):
 * <ul>
 *   <li>Redis: atomic mutual exclusion via Lua SETNX — fast path for hold creation</li>
 *   <li>PostgreSQL: durable source of truth — CommitSeats SERIALIZABLE transaction</li>
 * </ul>
 *
 * <p>Spring Actuator HTTP endpoints (health/live, health/ready, /metrics) are
 * served on server.port=8090. This port is never exposed through the API Gateway.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SeatInventoryApplication {

    public static void main(final String[] args) {
        SpringApplication.run(SeatInventoryApplication.class, args);
    }
}
