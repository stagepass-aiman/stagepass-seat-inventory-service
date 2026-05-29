package dev.stagepass.seatinventory.service;

// RULE-35: suppress annotations after imports.

import dev.stagepass.seatinventory.entity.OutboxEntity;
import dev.stagepass.seatinventory.entity.SeatHoldEntity;
import dev.stagepass.seatinventory.entity.SeatHoldSeatEntity;
import dev.stagepass.seatinventory.entity.SeatStateEntity;
import dev.stagepass.seatinventory.entity.SeatStateEnum;
import dev.stagepass.seatinventory.entity.SeatStateTransitionEntity;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CommitSeatsService.
 *
 * CommitSeatsService converts a Redis hold into a durable BOOKED record.
 * The service:
 *   1. Loads the SeatHold by bookingId (must be in HELD status)
 *   2. Loads all SeatState rows for the event + seat IDs (findByEventIdAndSeatIdIn)
 *   3. Verifies every seat is in HELD state with the correct bookingId
 *   4. Marks seats BOOKED within a SERIALIZABLE transaction
 *   5. Sets Redis BOOKED sentinel key (no TTL — permanent booking)
 *   6. Writes outbox event + audit transition in the same transaction
 *
 * CommitResult is an enum: SUCCESS | HOLD_NOT_FOUND | SERIALIZATION_FAILURE.
 *
 * Constructor parameter order (CommitSeatsService):
 *   (SeatStateRepository, SeatHoldRepository, SeatHoldSeatRepository,
 *    SeatStateTransitionRepository, OutboxRepository, StringRedisTemplate)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommitSeatsServiceTest {

    // Constructor order matches CommitSeatsService(seatStateRepo, seatHoldRepo,
    // seatHoldSeatRepo, transitionRepo, outboxRepo, redis)
    @Mock private SeatStateRepository      seatStateRepository;
    @Mock private SeatHoldRepository       seatHoldRepository;
    @Mock private SeatHoldSeatRepository   seatHoldSeatRepository;
    @Mock private SeatStateTransitionRepository transitionRepository;
    @Mock private OutboxRepository         outboxRepository;
    @Mock private StringRedisTemplate      redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private CommitSeatsService commitSeatsService;

    private static final UUID EVENT_ID   = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID SEAT_ID_A  = UUID.randomUUID();
    private static final UUID SEAT_ID_B  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Match constructor parameter order in CommitSeatsService
        commitSeatsService = new CommitSeatsService(
                seatStateRepository,
                seatHoldRepository,
                seatHoldSeatRepository,
                transitionRepository,
                outboxRepository,
                redisTemplate
        );
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CommitSeats: held seats are committed and Redis BOOKED sentinels set")
    void commitSeats_happyPath_marksBookedAndSetsRedisSentinels() {
        // Arrange — build a SeatHold with two HELD seats via static factory
        final SeatHoldEntity hold = SeatHoldEntity.create(
                UUID.randomUUID(), BOOKING_ID, EVENT_ID, UUID.randomUUID(),
                "GRPC", Instant.now().plusSeconds(600), 2);

        // SeatStateEntities must be in HELD state with matching bookingId so that
        // the service's row-count guard passes: heldCount == seatIds.size()
        final SeatStateEntity stateA = buildHeldSeat(SEAT_ID_A, EVENT_ID, BOOKING_ID);
        final SeatStateEntity stateB = buildHeldSeat(SEAT_ID_B, EVENT_ID, BOOKING_ID);

        when(seatHoldRepository.findByBookingId(BOOKING_ID))
                .thenReturn(Optional.of(hold));
        // Service calls findByEventIdAndSeatIdIn — not individual findByEventIdAndSeatId
        when(seatStateRepository.findByEventIdAndSeatIdIn(EVENT_ID, List.of(SEAT_ID_A, SEAT_ID_B)))
                .thenReturn(List.of(stateA, stateB));
        when(seatStateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transitionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(seatHoldRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act — commitSeats(UUID bookingId, UUID eventId, List<UUID> seatIds)
        final CommitSeatsService.CommitResult result =
                commitSeatsService.commitSeats(BOOKING_ID, EVENT_ID, List.of(SEAT_ID_A, SEAT_ID_B));

        // Assert — seats are BOOKED in Postgres
        assertThat(result).isEqualTo(CommitSeatsService.CommitResult.SUCCESS);
        assertThat(stateA.getState()).isEqualTo(SeatStateEnum.BOOKED);
        assertThat(stateB.getState()).isEqualTo(SeatStateEnum.BOOKED);

        // Assert — Redis BOOKED sentinels are set (no TTL — permanent booking)
        verify(valueOperations, times(2)).set(anyString(), anyString());
        // One outbox row PER SEAT (seat.state-changed events are per-seat for Notification
        // Service granularity — each seat's state change is an independent event).
        verify(outboxRepository, times(2)).save(any(OutboxEntity.class));
        verify(transitionRepository, times(2)).save(any(SeatStateTransitionEntity.class));
    }

    // ── Hold not found ────────────────────────────────────────────────────────

    @Test
    @DisplayName("CommitSeats: booking ID not found returns HOLD_NOT_FOUND")
    void commitSeats_holdNotFound_returnsHoldNotFound() {
        when(seatHoldRepository.findByBookingId(BOOKING_ID))
                .thenReturn(Optional.empty());

        final CommitSeatsService.CommitResult result =
                commitSeatsService.commitSeats(BOOKING_ID, EVENT_ID, List.of(SEAT_ID_A));

        // CommitResult is an enum — check enum value directly
        assertThat(result).isEqualTo(CommitSeatsService.CommitResult.HOLD_NOT_FOUND);
        verify(valueOperations, never()).set(anyString(), anyString());
        verify(outboxRepository, never()).save(any());
    }

    // ── Seat state changed since hold ─────────────────────────────────────────

    @Test
    @DisplayName("CommitSeats: seat no longer HELD returns HOLD_NOT_FOUND (concurrent cancellation)")
    void commitSeats_seatNotHeld_returnsHoldNotFound() {
        // Arrange — seat has been moved to AVAILABLE concurrently
        final SeatHoldEntity hold = SeatHoldEntity.create(
                UUID.randomUUID(), BOOKING_ID, EVENT_ID, UUID.randomUUID(),
                "GRPC", Instant.now().plusSeconds(600), 1);

        // Seat is AVAILABLE — bookingId will be null, heldCount will be 0
        final SeatStateEntity stateA = SeatStateEntity.create(
                UUID.randomUUID(), EVENT_ID, SEAT_ID_A,
                UUID.randomUUID(), "A", "1", "STANDARD",
                new BigDecimal("50.0000"), "INR");
        // stateA.getState() == AVAILABLE (default from create), bookingId == null

        when(seatHoldRepository.findByBookingId(BOOKING_ID))
                .thenReturn(Optional.of(hold));
        when(seatStateRepository.findByEventIdAndSeatIdIn(EVENT_ID, List.of(SEAT_ID_A)))
                .thenReturn(List.of(stateA));

        final CommitSeatsService.CommitResult result =
                commitSeatsService.commitSeats(BOOKING_ID, EVENT_ID, List.of(SEAT_ID_A));

        // heldCount (0) != seatIds.size() (1) → HOLD_NOT_FOUND
        assertThat(result).isEqualTo(CommitSeatsService.CommitResult.HOLD_NOT_FOUND);
        verify(seatStateRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build a SeatStateEntity in HELD state with the given bookingId.
     * Uses SeatStateEntity.create() (protected constructor) then markHeld().
     * Required for the row-count guard in CommitSeatsService:
     *   heldCount = seats filtered by state==HELD && bookingId==bookingId
     */
    private SeatStateEntity buildHeldSeat(
            final UUID seatId, final UUID eventId, final UUID bookingId) {
        final SeatStateEntity entity = SeatStateEntity.create(
                UUID.randomUUID(), eventId, seatId,
                UUID.randomUUID(), "A", "1", "STANDARD",
                new BigDecimal("50.0000"), "INR");
        entity.markHeld(bookingId, UUID.randomUUID(), Instant.now().plusSeconds(600));
        return entity;
    }
}