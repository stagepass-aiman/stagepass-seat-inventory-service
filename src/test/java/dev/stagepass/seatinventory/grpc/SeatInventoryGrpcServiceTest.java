package dev.stagepass.seatinventory.grpc;

import dev.stagepass.seatinventory.grpc.proto.CommitSeatsRequest;
import dev.stagepass.seatinventory.grpc.proto.CommitSeatsResponse;
import dev.stagepass.seatinventory.grpc.proto.HoldSeatsRequest;
import dev.stagepass.seatinventory.grpc.proto.HoldSeatsResponse;
import dev.stagepass.seatinventory.grpc.proto.HoldStatus;
import dev.stagepass.seatinventory.grpc.proto.ReleaseSeatsRequest;
import dev.stagepass.seatinventory.grpc.proto.ReleaseSeatsResponse;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * Calling onCompleted() without onNext() sends an empty response.
 * Calling onNext() without onCompleted() leaks the stream.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeatInventoryGrpcServiceTest {

    @Mock private HoldSeatsService holdSeatsService;
    @Mock private CommitSeatsService commitSeatsService;
    @Mock private ReleaseSeatsService releaseSeatsService;
    @Mock private ExtendHoldService extendHoldService;
    @Mock private CheckAvailabilityService checkAvailabilityService;

    // StreamObserver mocks capture what the gRPC service sends back.
    @Mock private StreamObserver<HoldSeatsResponse> holdObserver;
    @Mock private StreamObserver<CommitSeatsResponse> commitObserver;
    @Mock private StreamObserver<ReleaseSeatsResponse> releaseObserver;

    private SeatInventoryGrpcService grpcService;

    private static final String EVENT_ID = UUID.randomUUID().toString();
    private static final String BOOKING_ID = UUID.randomUUID().toString();
    private static final String SEAT_ID = UUID.randomUUID().toString();
    private static final String IDEMPOTENCY_KEY = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        grpcService = new SeatInventoryGrpcService(
                holdSeatsService,
                commitSeatsService,
                releaseSeatsService,
                extendHoldService,
                checkAvailabilityService
        );
    }

    // ── HoldSeats ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HoldSeats: success path calls onNext(HELD) then onCompleted()")
    void holdSeats_success_sendsHeldResponseAndCompletes() {
        // Arrange
        HoldSeatsService.HoldResult successResult =
                HoldSeatsService.HoldResult.success(BOOKING_ID, List.of(SEAT_ID));
        when(holdSeatsService.holdSeats(any())).thenReturn(successResult);

        HoldSeatsRequest request = HoldSeatsRequest.newBuilder()
                .setEventId(EVENT_ID)
                .setBookingId(BOOKING_ID)
                .addSeatIds(SEAT_ID)
                .setIdempotencyKey(IDEMPOTENCY_KEY)
                .build();

        // Act
        grpcService.holdSeats(request, holdObserver);

        // Assert — verify the gRPC unary contract: onNext then onCompleted
        ArgumentCaptor<HoldSeatsResponse> responseCaptor =
                ArgumentCaptor.forClass(HoldSeatsResponse.class);
        verify(holdObserver).onNext(responseCaptor.capture());
        verify(holdObserver).onCompleted();

        HoldSeatsResponse response = responseCaptor.getValue();
        assertThat(response.getStatus()).isEqualTo(HoldStatus.HELD);
    }

    @Test
    @DisplayName("HoldSeats: unavailable seats returns UNAVAILABLE status (no exception)")
    void holdSeats_unavailable_sendsUnavailableResponseNotException() {
        // Arrange — Redis Lua returned contention on one seat
        HoldSeatsService.HoldResult failResult =
                HoldSeatsService.HoldResult.unavailable(List.of(SEAT_ID));
        when(holdSeatsService.holdSeats(any())).thenReturn(failResult);

        HoldSeatsRequest request = HoldSeatsRequest.newBuilder()
                .setEventId(EVENT_ID)
                .setBookingId(BOOKING_ID)
                .addSeatIds(SEAT_ID)
                .setIdempotencyKey(IDEMPOTENCY_KEY)
                .build();

        // Act
        grpcService.holdSeats(request, holdObserver);

        // Assert — UNAVAILABLE is a business-level response, NOT a gRPC error status.
        // The gRPC error status path (onError) is for infrastructure failures only.
        ArgumentCaptor<HoldSeatsResponse> captor =
                ArgumentCaptor.forClass(HoldSeatsResponse.class);
        verify(holdObserver).onNext(captor.capture());
        verify(holdObserver).onCompleted();

        assertThat(captor.getValue().getStatus()).isEqualTo(HoldStatus.UNAVAILABLE);
    }

    // ── CommitSeats ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("CommitSeats: success delegates to CommitSeatsService and sends COMMITTED")
    void commitSeats_success_sendsCommittedResponse() {
        // Arrange
        when(commitSeatsService.commitSeats(BOOKING_ID))
                .thenReturn(CommitSeatsService.CommitResult.success());

        CommitSeatsRequest request = CommitSeatsRequest.newBuilder()
                .setBookingId(BOOKING_ID)
                .build();

        // Act
        grpcService.commitSeats(request, commitObserver);

        // Assert
        ArgumentCaptor<CommitSeatsResponse> captor =
                ArgumentCaptor.forClass(CommitSeatsResponse.class);
        verify(commitObserver).onNext(captor.capture());
        verify(commitObserver).onCompleted();
        assertThat(captor.getValue().getSuccess()).isTrue();
    }

    // ── ReleaseSeats ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("ReleaseSeats: success path completes observer correctly")
    void releaseSeats_success_completesObserver() {
        // Arrange
        when(releaseSeatsService.releaseSeats(any()))
                .thenReturn(ReleaseSeatsService.ReleaseResult.success());

        ReleaseSeatsRequest request = ReleaseSeatsRequest.newBuilder()
                .setBookingId(BOOKING_ID)
                .build();

        // Act
        grpcService.releaseSeats(request, releaseObserver);

        // Assert
        ArgumentCaptor<ReleaseSeatsResponse> captor =
                ArgumentCaptor.forClass(ReleaseSeatsResponse.class);
        verify(releaseObserver).onNext(captor.capture());
        verify(releaseObserver).onCompleted();
        assertThat(captor.getValue().getSuccess()).isTrue();
    }

    @Test
    @DisplayName("HoldSeats: unexpected exception translates to gRPC INTERNAL status")
    void holdSeats_unexpectedException_callsOnError() {
        // Arrange — simulate an unexpected infrastructure failure (e.g. Redis connection lost)
        when(holdSeatsService.holdSeats(any()))
                .thenThrow(new RuntimeException("Redis connection refused"));

        HoldSeatsRequest request = HoldSeatsRequest.newBuilder()
                .setEventId(EVENT_ID)
                .setBookingId(BOOKING_ID)
                .addSeatIds(SEAT_ID)
                .setIdempotencyKey(IDEMPOTENCY_KEY)
                .build();

        // Act
        grpcService.holdSeats(request, holdObserver);

        // Assert — infrastructure failures must call onError with INTERNAL status,
        // not onNext with a failure response. This allows the gRPC client (Booking
        // Service) to trigger retry logic correctly.
        ArgumentCaptor<StatusRuntimeException> errorCaptor =
                ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(holdObserver).onError(errorCaptor.capture());

        assertThat(errorCaptor.getValue().getStatus().getCode())
                .isEqualTo(Status.Code.INTERNAL);
    }
}
