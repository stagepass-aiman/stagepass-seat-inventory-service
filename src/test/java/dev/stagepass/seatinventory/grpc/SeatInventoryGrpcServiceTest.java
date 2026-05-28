package dev.stagepass.seatinventory.grpc;

import dev.stagepass.seatinventory.grpc.proto.CommitSeatsRequest;
import dev.stagepass.seatinventory.grpc.proto.CommitSeatsResponse;
import dev.stagepass.seatinventory.grpc.proto.CommitSeatsStatus;
import dev.stagepass.seatinventory.grpc.proto.HoldSeatsRequest;
import dev.stagepass.seatinventory.grpc.proto.HoldSeatsResponse;
import dev.stagepass.seatinventory.grpc.proto.HoldSeatsStatus;
import dev.stagepass.seatinventory.grpc.proto.ReleaseSeatsRequest;
import dev.stagepass.seatinventory.grpc.proto.ReleaseSeatsResponse;
import dev.stagepass.seatinventory.grpc.proto.ReleaseSeatsStatus;
import dev.stagepass.seatinventory.service.CheckAvailabilityService;
import dev.stagepass.seatinventory.service.CommitSeatsService;
import dev.stagepass.seatinventory.service.ExtendHoldService;
import dev.stagepass.seatinventory.service.HoldSeatsService;
import dev.stagepass.seatinventory.service.ReleaseSeatsService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the gRPC service layer.
 *
 * These tests verify that SeatInventoryGrpcService correctly:
 *   - Delegates to the appropriate service class
 *   - Translates service results into gRPC response messages
 *   - Calls onCompleted() after onNext() (gRPC unary contract)
 *   - Returns appropriate gRPC Status on failure
 *
 * The service layer (HoldSeatsService, CommitSeatsService, etc.) is mocked.
 * Business logic is tested in the service unit tests and integration tests.
 *
 * Pattern note: gRPC unary RPCs use StreamObserver — the server MUST call
 * observer.onNext(response) then observer.onCompleted() in that order.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeatInventoryGrpcServiceTest {

    @Mock private CheckAvailabilityService checkAvailabilityService;
    @Mock private HoldSeatsService holdSeatsService;
    @Mock private ExtendHoldService extendHoldService;
    @Mock private CommitSeatsService commitSeatsService;
    @Mock private ReleaseSeatsService releaseSeatsService;

    @Mock private StreamObserver<HoldSeatsResponse> holdObserver;
    @Mock private StreamObserver<CommitSeatsResponse> commitObserver;
    @Mock private StreamObserver<ReleaseSeatsResponse> releaseObserver;

    private SeatInventoryGrpcService grpcService;

    private static final UUID EVENT_UUID    = UUID.randomUUID();
    private static final UUID BOOKING_UUID  = UUID.randomUUID();
    private static final UUID CUSTOMER_UUID = UUID.randomUUID();
    private static final UUID SEAT_UUID     = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Constructor order matches SeatInventoryGrpcService declaration:
        // (CheckAvailabilityService, HoldSeatsService, ExtendHoldService,
        //  CommitSeatsService, ReleaseSeatsService)
        grpcService = new SeatInventoryGrpcService(
                checkAvailabilityService,
                holdSeatsService,
                extendHoldService,
                commitSeatsService,
                releaseSeatsService
        );
    }

    // ── HoldSeats ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HoldSeats: success path calls onNext(SUCCESS) then onCompleted()")
    void holdSeats_success_sendsSuccessResponseAndCompletes() {
        // Arrange — HoldResult is a record: (boolean success, UUID holdId,
        //            Instant heldUntil, List<UUID> unavailableSeats)
        final UUID holdId = UUID.randomUUID();
        final HoldSeatsService.HoldResult successResult =
                new HoldSeatsService.HoldResult(
                        true,
                        holdId,
                        Instant.now().plusSeconds(600),
                        List.of());

        when(holdSeatsService.holdSeats(
                any(UUID.class), any(UUID.class), any(UUID.class),
                anyList(), anyString()))
                .thenReturn(successResult);

        final HoldSeatsRequest request = HoldSeatsRequest.newBuilder()
                .setBookingId(BOOKING_UUID.toString())
                .setEventId(EVENT_UUID.toString())
                .setCustomerId(CUSTOMER_UUID.toString())
                .addSeatIds(SEAT_UUID.toString())
                .build();

        // Act
        grpcService.holdSeats(request, holdObserver);

        // Assert — gRPC unary contract: onNext then onCompleted
        final ArgumentCaptor<HoldSeatsResponse> captor =
                ArgumentCaptor.forClass(HoldSeatsResponse.class);
        verify(holdObserver).onNext(captor.capture());
        verify(holdObserver).onCompleted();

        assertThat(captor.getValue().getStatus()).isEqualTo(HoldSeatsStatus.SUCCESS);
        assertThat(captor.getValue().getHoldId()).isEqualTo(holdId.toString());
    }

    @Test
    @DisplayName("HoldSeats: unavailable seats returns UNAVAILABLE status (not exception)")
    void holdSeats_unavailable_sendsUnavailableResponseNotException() {
        // Arrange — Redis Lua returned contention on one seat
        final HoldSeatsService.HoldResult failResult =
                new HoldSeatsService.HoldResult(
                        false,
                        null,
                        null,
                        List.of(SEAT_UUID));

        when(holdSeatsService.holdSeats(
                any(UUID.class), any(UUID.class), any(UUID.class),
                anyList(), anyString()))
                .thenReturn(failResult);

        final HoldSeatsRequest request = HoldSeatsRequest.newBuilder()
                .setBookingId(BOOKING_UUID.toString())
                .setEventId(EVENT_UUID.toString())
                .setCustomerId(CUSTOMER_UUID.toString())
                .addSeatIds(SEAT_UUID.toString())
                .build();

        // Act
        grpcService.holdSeats(request, holdObserver);

        // Assert — UNAVAILABLE is a business-level response, NOT a gRPC error status.
        final ArgumentCaptor<HoldSeatsResponse> captor =
                ArgumentCaptor.forClass(HoldSeatsResponse.class);
        verify(holdObserver).onNext(captor.capture());
        verify(holdObserver).onCompleted();

        assertThat(captor.getValue().getStatus()).isEqualTo(HoldSeatsStatus.UNAVAILABLE);
        assertThat(captor.getValue().getUnavailableSeatsList())
                .containsExactly(SEAT_UUID.toString());
    }

    // ── CommitSeats ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("CommitSeats: success maps to COMMIT_SUCCESS status")
    void commitSeats_success_sendsCommitSuccessStatus() {
        // Arrange — CommitResult is an enum: SUCCESS | HOLD_NOT_FOUND | SERIALIZATION_FAILURE
        when(commitSeatsService.commitSeats(
                any(UUID.class), any(UUID.class), anyList()))
                .thenReturn(CommitSeatsService.CommitResult.SUCCESS);

        final CommitSeatsRequest request = CommitSeatsRequest.newBuilder()
                .setBookingId(BOOKING_UUID.toString())
                .setEventId(EVENT_UUID.toString())
                .addSeatIds(SEAT_UUID.toString())
                .build();

        // Act
        grpcService.commitSeats(request, commitObserver);

        // Assert
        final ArgumentCaptor<CommitSeatsResponse> captor =
                ArgumentCaptor.forClass(CommitSeatsResponse.class);
        verify(commitObserver).onNext(captor.capture());
        verify(commitObserver).onCompleted();

        // SUCCESS maps to COMMIT_SUCCESS (see SeatInventoryGrpcService switch)
        assertThat(captor.getValue().getStatus())
                .isEqualTo(CommitSeatsStatus.COMMIT_SUCCESS);
    }

    @Test
    @DisplayName("CommitSeats: HOLD_NOT_FOUND maps to HOLD_NOT_FOUND status")
    void commitSeats_holdNotFound_sendsHoldNotFoundStatus() {
        when(commitSeatsService.commitSeats(
                any(UUID.class), any(UUID.class), anyList()))
                .thenReturn(CommitSeatsService.CommitResult.HOLD_NOT_FOUND);

        final CommitSeatsRequest request = CommitSeatsRequest.newBuilder()
                .setBookingId(BOOKING_UUID.toString())
                .setEventId(EVENT_UUID.toString())
                .addSeatIds(SEAT_UUID.toString())
                .build();

        grpcService.commitSeats(request, commitObserver);

        final ArgumentCaptor<CommitSeatsResponse> captor =
                ArgumentCaptor.forClass(CommitSeatsResponse.class);
        verify(commitObserver).onNext(captor.capture());
        verify(commitObserver).onCompleted();

        assertThat(captor.getValue().getStatus())
                .isEqualTo(CommitSeatsStatus.HOLD_NOT_FOUND);
    }

    // ── ReleaseSeats ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("ReleaseSeats: always returns RELEASE_SUCCESS (release errors are silenced)")
    void releaseSeats_success_sendsReleaseSuccessStatus() {
        // ReleaseSeats gRPC implementation always responds RELEASE_SUCCESS
        // (release errors are logged but not propagated — idempotent by design)
        when(releaseSeatsService.releaseSeats(
                any(UUID.class), any(UUID.class), anyList(), anyString()))
                .thenReturn(ReleaseSeatsService.ReleaseResult.SUCCESS);

        final ReleaseSeatsRequest request = ReleaseSeatsRequest.newBuilder()
                .setBookingId(BOOKING_UUID.toString())
                .setEventId(EVENT_UUID.toString())
                .addSeatIds(SEAT_UUID.toString())
                .setReason("SAGA_COMPENSATION")
                .build();

        // Act
        grpcService.releaseSeats(request, releaseObserver);

        // Assert
        final ArgumentCaptor<ReleaseSeatsResponse> captor =
                ArgumentCaptor.forClass(ReleaseSeatsResponse.class);
        verify(releaseObserver).onNext(captor.capture());
        verify(releaseObserver).onCompleted();

        assertThat(captor.getValue().getStatus())
                .isEqualTo(ReleaseSeatsStatus.RELEASE_SUCCESS);
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    @DisplayName("HoldSeats: unexpected exception translates to gRPC INTERNAL status")
    void holdSeats_unexpectedException_callsOnError() {
        // Arrange — simulate an unexpected infrastructure failure (e.g. Redis connection lost)
        when(holdSeatsService.holdSeats(
                any(UUID.class), any(UUID.class), any(UUID.class),
                anyList(), anyString()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        final HoldSeatsRequest request = HoldSeatsRequest.newBuilder()
                .setBookingId(BOOKING_UUID.toString())
                .setEventId(EVENT_UUID.toString())
                .setCustomerId(CUSTOMER_UUID.toString())
                .addSeatIds(SEAT_UUID.toString())
                .build();

        // Act
        grpcService.holdSeats(request, holdObserver);

        // Assert — infrastructure failures must call onError with INTERNAL status
        final ArgumentCaptor<StatusRuntimeException> errorCaptor =
                ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(holdObserver).onError(errorCaptor.capture());

        assertThat(errorCaptor.getValue().getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }
}