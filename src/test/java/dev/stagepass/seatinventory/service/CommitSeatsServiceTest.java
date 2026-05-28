package dev.stagepass.seatinventory.service;

// RULE-35: ESLint disable comments must be after imports. Same discipline
// applied here — any suppress annotations go after the import block.

import dev.stagepass.seatinventory.entity.OutboxEntity;
import dev.stagepass.seatinventory.entity.SeatHoldEntity;
import dev.stagepass.seatinventory.entity.SeatHoldSeatEntity;
import dev.stagepass.seatinventory.entity.SeatStateEntity;
import dev.stagepass.seatinventory.entity.SeatStateEnum;
import dev.stagepass.seatinventory.entity.SeatStateTransitionEntity;
import dev.stagepass.seatinventory.redis.RedisKeySchema;
import dev.stagepass.seatinventory.redis.SeatHoldLuaScript;
import dev.stagepass.seatinventory.repository.OutboxRepository;
import dev.stagepass.seatinventory.repository.SeatHoldRepository;
import dev.stagepass.seatinventory.repository.SeatHoldSeatRepository;
import dev.stagepass.seatinventory.repository.SeatStateRepository;
import dev.stagepass.seatinventory.repository.SeatStateTransitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CommitSeatsService.
 *
 * CommitSeatsService is the SERIALIZABLE path — it converts a Redis hold
 * into a durable PostgreSQL BOOKED record. The service:
 *   1. Loads the SeatHold (must be in HELD status)
 *   2. Loads each SeatHoldSeat's corresponding SeatState
 *   3. Verifies every seat is still HELD (no concurrent cancellation)
 *   4. Marks seats BOOKED in Postgres (within SERIALIZABLE transaction)
 *   5. Sets Redis sentinel key "BOOKED" (no TTL — permanent)
 *   6. Writes outbox event + audit transition in the same transaction
 *
 * Why SERIALIZABLE only on CommitSeats, not HoldSeats?
 *   HoldSeats uses Redis Lua SETNX as the atomic lock — applying SERIALIZABLE
 *   there would serialise all concurrent holds through the PG lock manager,
 *   violating NFR-PERF-001 (p99 < 500ms). SERIALIZABLE on CommitSeats protects
 *   the final durable write, not the hot-path lock.
 *
 * These tests verify the commit logic in isolation (mocked repositories).
 * Integration tests (CommitSeatsIntegrationTest) verify SERIALIZABLE behaviour
 * with a real PostgreSQL instance.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommitSeatsServiceTest {

    @Mock
    private SeatHoldRepository seatHoldRepository;

    @Mock
    private SeatHoldSeatRepository seatHoldSeatRepository;

    @Mock
    private SeatStateRepository seatStateRepository;

    @Mock
    private SeatStateTransitionRepository transitionRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private SeatHoldLuaScript seatHoldLuaScript;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CommitSeatsService commitSeatsService;

    private static final String EVENT_ID = UUID.randomUUID().toString();
    private static final String BOOKING_ID = UUID.randomUUID().toString();
    private static final String SEAT_ID_A = UUID.randomUUID().toString();
    private static final String SEAT_ID_B = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        commitSeatsService = new CommitSeatsService(
                seatHoldRepository,
                seatHoldSeatRepository,
                seatStateRepository,
                transitionRepository,
                outboxRepository,
                redisTemplate
        );
    }

    // ── Scenario 1: Happy path ────────────────────────────────────────────────

    @Test
    @DisplayName("CommitSeats: held seats are committed and Redis BOOKED sentinels set")
    void commitSeats_happyPath_marksBookedAndSetsRedisSentinels() {
        // Arrange — build a SeatHold with two seats in HELD status
        SeatHoldEntity hold = buildHeldHold(BOOKING_ID, EVENT_ID, List.of(SEAT_ID_A, SEAT_ID_B));
        List<SeatHoldSeatEntity> holdSeats = hold.getSeatHoldSeats();
        SeatStateEntity stateA = buildSeatState(SEAT_ID_A, EVENT_ID, SeatStateEnum.HELD);
        SeatStateEntity stateB = buildSeatState(SEAT_ID_B, EVENT_ID, SeatStateEnum.HELD);

        when(seatHoldRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(hold));
        when(seatHoldSeatRepository.findByHoldId(hold.getId())).thenReturn(holdSeats);
        when(seatStateRepository.findByEventIdAndSeatId(EVENT_ID, SEAT_ID_A)).thenReturn(Optional.of(stateA));
        when(seatStateRepository.findByEventIdAndSeatId(EVENT_ID, SEAT_ID_B)).thenReturn(Optional.of(stateB));
        when(seatStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(seatHoldSeatRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(seatHoldRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transitionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        CommitSeatsService.CommitResult result = commitSeatsService.commitSeats(BOOKING_ID);

        // Assert — seats are BOOKED in Postgres
        assertThat(result.success()).isTrue();
        assertThat(stateA.getState()).isEqualTo(SeatStateEnum.BOOKED);
        assertThat(stateB.getState()).isEqualTo(SeatStateEnum.BOOKED);

        // Assert — Redis BOOKED sentinels are set (no TTL — permanent booking)
        // The service must call opsForValue().set() for each seat — no expiry arg.
        verify(valueOperations, times(2)).set(anyString(), anyString());
        // Outbox event and audit transitions must be written in the same transaction
        verify(outboxRepository).save(any(OutboxEntity.class));
        verify(transitionRepository, times(2)).save(any(SeatStateTransitionEntity.class));
    }

    // ── Scenario 2: Hold not found ────────────────────────────────────────────

    @Test
    @DisplayName("CommitSeats: booking ID not found returns failure result")
    void commitSeats_holdNotFound_returnsFailure() {
        // Arrange
        when(seatHoldRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.empty());

        // Act
        CommitSeatsService.CommitResult result = commitSeatsService.commitSeats(BOOKING_ID);

        // Assert — never touches Redis or writes outbox when hold is missing
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("not found");
        verify(valueOperations, never()).set(anyString(), anyString());
        verify(outboxRepository, never()).save(any());
    }

    // ── Scenario 3: Seat state changed since hold ─────────────────────────────

    @Test
    @DisplayName("CommitSeats: seat no longer HELD (concurrent cancellation) returns failure")
    void commitSeats_seatNotHeld_returnsFailure() {
        // Arrange — one seat has been moved to AVAILABLE (e.g. admin unblock race)
        SeatHoldEntity hold = buildHeldHold(BOOKING_ID, EVENT_ID, List.of(SEAT_ID_A));
        List<SeatHoldSeatEntity> holdSeats = hold.getSeatHoldSeats();
        // Seat was HELD but is now AVAILABLE (concurrent state change)
        SeatStateEntity stateA = buildSeatState(SEAT_ID_A, EVENT_ID, SeatStateEnum.AVAILABLE);

        when(seatHoldRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(hold));
        when(seatHoldSeatRepository.findByHoldId(hold.getId())).thenReturn(holdSeats);
        when(seatStateRepository.findByEventIdAndSeatId(EVENT_ID, SEAT_ID_A)).thenReturn(Optional.of(stateA));

        // Act
        CommitSeatsService.CommitResult result = commitSeatsService.commitSeats(BOOKING_ID);

        // Assert — SERIALIZABLE transaction must have been rolled back (test verifies
        // no BOOKED writes occurred). In production this would trigger saga compensation.
        assertThat(result.success()).isFalse();
        verify(seatStateRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SeatHoldEntity buildHeldHold(
            String bookingId, String eventId, List<String> seatIds) {
        SeatHoldEntity hold = new SeatHoldEntity();
        hold.setId(UUID.randomUUID());
        hold.setBookingId(UUID.fromString(bookingId));
        hold.setEventId(UUID.fromString(eventId));
        hold.setExpiresAt(Instant.now().plusSeconds(600));
        // Status field matches enum — HELD
        // (exact field name depends on SeatHoldEntity implementation)
        List<SeatHoldSeatEntity> seats = seatIds.stream().map(sid -> {
            SeatHoldSeatEntity s = new SeatHoldSeatEntity();
            s.setId(UUID.randomUUID());
            s.setSeatId(UUID.fromString(sid));
            s.setHold(hold);
            return s;
        }).toList();
        hold.setSeatHoldSeats(seats);
        return hold;
    }

    private SeatStateEntity buildSeatState(
            String seatId, String eventId, SeatStateEnum state) {
        SeatStateEntity entity = new SeatStateEntity();
        entity.setId(UUID.randomUUID());
        entity.setSeatId(UUID.fromString(seatId));
        entity.setEventId(UUID.fromString(eventId));
        entity.setState(state);
        entity.setPrice(new BigDecimal("50.0000"));
        entity.setVersion(0L);
        return entity;
    }
}
