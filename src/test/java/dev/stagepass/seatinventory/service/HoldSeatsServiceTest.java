package dev.stagepass.seatinventory.service;

import dev.stagepass.seatinventory.entity.SeatStateEntity;
import dev.stagepass.seatinventory.entity.SeatStateEnum;
import dev.stagepass.seatinventory.redis.SeatHoldLuaScript;
import dev.stagepass.seatinventory.repository.IdempotencyKeyRepository;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// Shared @BeforeEach stubs are intentional — LENIENT allows tests that don't use them.
// Phase 3 build log §10: shared stubs in @BeforeEach require LENIENT.
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("HoldSeatsService")
class HoldSeatsServiceTest {

    @Mock private SeatHoldLuaScript luaScript;
    @Mock private SeatStateRepository seatStateRepo;
    @Mock private SeatHoldRepository seatHoldRepo;
    @Mock private SeatHoldSeatRepository seatHoldSeatRepo;
    @Mock private SeatStateTransitionRepository transitionRepo;
    @Mock private OutboxRepository outboxRepo;
    @Mock private IdempotencyKeyRepository idempotencyRepo;

    private HoldSeatsService service;

    private final UUID bookingId  = UUID.randomUUID();
    private final UUID eventId    = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID seatId1    = UUID.randomUUID();
    private final UUID seatId2    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new HoldSeatsService(luaScript, seatStateRepo, seatHoldRepo,
                seatHoldSeatRepo, transitionRepo, outboxRepo, idempotencyRepo);

        // Shared stub: no idempotency hit by default
        given(idempotencyRepo.findByIdempotencyKeyAndOperation(
                bookingId.toString(), "HOLD_SEATS"))
                .willReturn(Optional.empty());

        // Shared stub: Lua returns success by default
        given(luaScript.executeHoldSeats(anyList(), any(UUID.class), anyLong()))
                .willReturn(List.of(1L));

        // Shared stub: seat state lookup
        given(seatStateRepo.findByEventIdAndSeatIdIn(eventId, List.of(seatId1, seatId2)))
                .willReturn(List.of(
                        makeSeat(seatId1, eventId),
                        makeSeat(seatId2, eventId)));
    }

    @Test
    @DisplayName("returns SUCCESS when Lua SETNX acquires all seats")
    void holdSeats_luaSuccess_returnsSuccess() {
        final HoldSeatsService.HoldResult result = service.holdSeats(
                bookingId, eventId, customerId, List.of(seatId1, seatId2), "GRPC");

        assertThat(result.success()).isTrue();
        assertThat(result.holdId()).isNotNull();
        assertThat(result.heldUntil()).isNotNull();
        assertThat(result.unavailableSeats()).isEmpty();
    }

    @Test
    @DisplayName("returns UNAVAILABLE when Lua SETNX rejects one seat")
    void holdSeats_luaUnavailable_returnsUnavailable() {
        // Lua returns {0, "seat:eventId:seatId1"} — seatId1 is unavailable
        final String unavailableKey = "seat:" + eventId + ":" + seatId1;
        given(luaScript.executeHoldSeats(anyList(), any(UUID.class), anyLong()))
                .willReturn(List.of(0L, unavailableKey));

        final HoldSeatsService.HoldResult result = service.holdSeats(
                bookingId, eventId, customerId, List.of(seatId1, seatId2), "GRPC");

        assertThat(result.success()).isFalse();
        assertThat(result.unavailableSeats()).hasSize(1);
        assertThat(result.unavailableSeats()).contains(seatId1);
    }

    @Test
    @DisplayName("returns cached result when idempotency key exists (retry scenario)")
    void holdSeats_idempotencyHit_returnsCachedResult() {
        // Simulate: hold already exists in DB
        final dev.stagepass.seatinventory.entity.SeatHoldEntity hold =
                dev.stagepass.seatinventory.entity.SeatHoldEntity.create(
                        UUID.randomUUID(), bookingId, eventId, customerId,
                        "GRPC", java.time.Instant.now().plusSeconds(600), 2);
        given(idempotencyRepo.findByIdempotencyKeyAndOperation(
                bookingId.toString(), "HOLD_SEATS"))
                .willReturn(Optional.of(new dev.stagepass.seatinventory.entity.IdempotencyKeyEntity()));
        given(seatHoldRepo.findByBookingId(bookingId))
                .willReturn(Optional.of(hold));

        final HoldSeatsService.HoldResult result = service.holdSeats(
                bookingId, eventId, customerId, List.of(seatId1, seatId2), "GRPC");

        assertThat(result.success()).isTrue();
        // Lua script must NOT be called on idempotency hit
        verify(luaScript, never()).executeHoldSeats(anyList(), any(), anyLong());
    }

    // Helper: create a valid SeatStateEntity in AVAILABLE state
    private SeatStateEntity makeSeat(final UUID seatId, final UUID eventId) {
        return SeatStateEntity.create(UUID.randomUUID(), eventId, seatId,
                UUID.randomUUID(), "A", "1", "STANDARD",
                BigDecimal.valueOf(100, 2), "INR");
    }
}
