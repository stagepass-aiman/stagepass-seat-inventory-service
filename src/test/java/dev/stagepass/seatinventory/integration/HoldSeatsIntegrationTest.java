package dev.stagepass.seatinventory.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test scaffold.
 *
 * <p>Uses Testcontainers for PostgreSQL and Redis (no embedded mocks).
 * Tests verify the full two-store write path:
 * Redis Lua SETNX succeeds → PostgreSQL seat_holds + seat_state written atomically.
 *
 * <p>Note: Do NOT use @TestInstance(PER_CLASS) with @Testcontainers + @DynamicPropertySource.
 * Phase 3 build log §8: PER_CLASS causes containers to start AFTER Spring context,
 * breaking @DynamicPropertySource (mapped port not yet available).
 *
 * TODO Phase 4 impl: implement full integration test suite covering:
 *   - holdSeats success: Redis key set + PG rows created
 *   - holdSeats UNAVAILABLE: concurrent hold attempt rejected
 *   - holdSeats idempotency: retry returns same holdId without resetting TTL
 *   - commitSeats SERIALIZABLE: two concurrent commits — one succeeds, one fails
 *   - releaseSeats: Redis key deleted + PG rows updated
 *   - reconciliation: expired holds detected and released
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayName("HoldSeats integration tests")
class HoldSeatsIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("seat_inventory_db")
                    .withUsername("seat_inventory")
                    .withPassword("seat_inventory_secret");

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4-alpine")
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // RULE-25: build URI from mapped port (not getConnectionString()) — Windows compat
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379).toString());
        // Kafka is handled by @ActiveProfiles("test") — application-test.yml sets
        // bootstrap-servers=localhost:9999. Do NOT exclude KafkaAutoConfiguration here;
        // OutboxPublisher requires KafkaTemplate which that auto-config provides.
    }

    @Test
    @DisplayName("application context loads — Flyway migrations applied, Redis connected")
    void contextLoads() {
        // Spring Boot will fail to start if Flyway migrations don't match entity schema.
        // This is the most valuable scaffold test: it validates ddl-auto=validate.
    }
}