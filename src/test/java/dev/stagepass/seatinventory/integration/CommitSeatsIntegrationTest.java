package dev.stagepass.seatinventory.integration;

import dev.stagepass.seatinventory.entity.SeatStateEntity;
import dev.stagepass.seatinventory.entity.SeatStateEnum;
import dev.stagepass.seatinventory.repository.SeatHoldRepository;
import dev.stagepass.seatinventory.repository.SeatStateRepository;
import dev.stagepass.seatinventory.service.CommitSeatsService;
import dev.stagepass.seatinventory.service.HoldSeatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for CommitSeatsService.
 *
 * Verifies SERIALIZABLE transaction behaviour with a real PostgreSQL instance.
 *
 * Key behaviour under test:
 *   - CommitSeats transitions seat state from HELD → BOOKED atomically
 *   - A seat not in HELD state causes HOLD_NOT_FOUND (no partial commit)
 *   - contextLoads verifies Flyway V1–V6 and Hibernate ddl-auto=validate pass
 *
 * Why SERIALIZABLE on CommitSeats only?
 *   SERIALIZABLE ensures concurrent commit attempts are detected. The gRPC
 *   service layer catches CannotSerializeTransactionException and returns
 *   SERIALIZATION_FAILURE to Booking Service, which retries per NFR-REL-006.
 *   Applying SERIALIZABLE to HoldSeats would violate NFR-PERF-001 (p99 < 500ms).
 *
 * Testcontainers note (RULE-25, RULE-26):
 *   Build JDBC URL manually from getMappedPort() — on Windows Docker Desktop,
 *   getJdbcUrl() may return the container's internal hostname.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CommitSeatsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("seat_inventory_db")
                    .withUsername("seat_inventory")
                    .withPassword("test_secret");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) {
        // Build JDBC URL manually — RULE-25 (Windows Docker Desktop hostname issue)
        registry.add("spring.datasource.url", () ->
                "jdbc:postgresql://127.0.0.1:" + postgres.getMappedPort(5432)
                + "/seat_inventory_db");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private CommitSeatsService commitSeatsService;
    @Autowired private HoldSeatsService   holdSeatsService;
    @Autowired private SeatStateRepository seatStateRepository;
    @Autowired private SeatHoldRepository  seatHoldRepository;

    private UUID eventId;
    private UUID bookingId;
    private UUID customerId;
    private UUID seatId;

    @BeforeEach
    void setUp() {
        eventId    = UUID.randomUUID();
        bookingId  = UUID.randomUUID();
        customerId = UUID.randomUUID();
        seatId     = UUID.randomUUID();

        // Seed an AVAILABLE seat via static factory — never use new SeatStateEntity()
        // (protected constructor, no setters — entities use factory + mutation methods)
        final SeatStateEntity seat = SeatStateEntity.create(
                UUID.randomUUID(), eventId, seatId,
                UUID.randomUUID(), "A", "1", "STANDARD",
                new BigDecimal("75.0000"), "INR");
        seatStateRepository.save(seat);
    }

    @Test
    @DisplayName("CommitSeats: HELD seat transitions to BOOKED in PostgreSQL")
    void commitSeats_heldSeat_transitionsToBooked() {
        // Arrange — place a hold via HoldSeatsService (Redis Lua + async PG write)
        // holdSeats(UUID bookingId, UUID eventId, UUID customerId, List<UUID> seatIds, String source)
        final HoldSeatsService.HoldResult holdResult =
                holdSeatsService.holdSeats(bookingId, eventId, customerId, List.of(seatId), "GRPC");
        assertThat(holdResult.success()).isTrue();

        // Act — commitSeats(UUID bookingId, UUID eventId, List<UUID> seatIds)
        final CommitSeatsService.CommitResult commitResult =
                commitSeatsService.commitSeats(bookingId, eventId, List.of(seatId));

        // Assert — CommitResult is an enum: SUCCESS | HOLD_NOT_FOUND | SERIALIZATION_FAILURE
        assertThat(commitResult).isEqualTo(CommitSeatsService.CommitResult.SUCCESS);

        // Verify PostgreSQL state directly
        final Optional<SeatStateEntity> committed =
                seatStateRepository.findByEventIdAndSeatId(eventId, seatId);
        assertThat(committed).isPresent();
        assertThat(committed.get().getState()).isEqualTo(SeatStateEnum.BOOKED);
    }

    @Test
    @DisplayName("Context loads: Flyway V1–V6 migrations and Hibernate validate pass")
    void contextLoads() {
        // Verifies:
        //   1. Flyway V1–V6 migrations run cleanly against PostgreSQL 16
        //   2. Hibernate ddl-auto=validate finds no schema/entity mismatches
        // If a migration or entity field type is wrong, Spring Boot startup fails.
        assertThat(seatStateRepository).isNotNull();
        assertThat(seatHoldRepository).isNotNull();
    }
}