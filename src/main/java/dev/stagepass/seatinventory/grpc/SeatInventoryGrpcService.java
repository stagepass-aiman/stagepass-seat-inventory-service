package dev.stagepass.seatinventory.grpc;

import dev.stagepass.seatinventory.grpc.proto.CheckSeatAvailabilityRequest;
import dev.stagepass.seatinventory.grpc.proto.CheckSeatAvailabilityResponse;
import dev.stagepass.seatinventory.grpc.proto.CommitSeatsRequest;
import dev.stagepass.seatinventory.grpc.proto.CommitSeatsResponse;
import dev.stagepass.seatinventory.grpc.proto.CommitSeatsStatus;
import dev.stagepass.seatinventory.grpc.proto.ExtendHoldRequest;
import dev.stagepass.seatinventory.grpc.proto.ExtendHoldResponse;
import dev.stagepass.seatinventory.grpc.proto.HoldSeatsRequest;
import dev.stagepass.seatinventory.grpc.proto.HoldSeatsResponse;
import dev.stagepass.seatinventory.grpc.proto.HoldSeatsStatus;
import dev.stagepass.seatinventory.grpc.proto.ReleaseSeatsRequest;
import dev.stagepass.seatinventory.grpc.proto.ReleaseSeatsResponse;
import dev.stagepass.seatinventory.grpc.proto.ReleaseSeatsStatus;
import dev.stagepass.seatinventory.grpc.proto.SeatInventoryServiceGrpc;
import dev.stagepass.seatinventory.grpc.proto.UnavailableSeat;
import dev.stagepass.seatinventory.service.CheckAvailabilityService;
import dev.stagepass.seatinventory.service.CommitSeatsService;
import dev.stagepass.seatinventory.service.ExtendHoldService;
import dev.stagepass.seatinventory.service.HoldSeatsService;
import dev.stagepass.seatinventory.service.ReleaseSeatsService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotSerializeTransactionException;

import java.util.List;
import java.util.UUID;

/**
 * gRPC server implementation for the Seat Inventory Service.
 *
 * <p>Registered with the Netty server via @GrpcService (net.devh:grpc-spring-boot-starter).
 * This is the ONLY inbound interface for business operations.
 * No REST controllers exist in this service.
 *
 * <p>gRPC deadline enforcement: the Booking Service sets a 400 ms deadline on HoldSeats
 * (ADR-005 §3.4 Step 1). The Netty server respects this — if the client cancels the RPC
 * due to deadline exceeded, the server-side context is cancelled. Service methods should
 * check Context.current().isCancelled() at checkpoints in long operations.
 *
 * <p>OTel context propagation: gRPC metadata headers (traceparent, tracestate) are
 * extracted by the OpenTelemetry gRPC instrumentation library configured in OtelConfig.
 */
@GrpcService
public class SeatInventoryGrpcService extends SeatInventoryServiceGrpc.SeatInventoryServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(SeatInventoryGrpcService.class);

    private final CheckAvailabilityService checkAvailabilityService;
    private final HoldSeatsService holdSeatsService;
    private final ExtendHoldService extendHoldService;
    private final CommitSeatsService commitSeatsService;
    private final ReleaseSeatsService releaseSeatsService;

    public SeatInventoryGrpcService(
            final CheckAvailabilityService checkAvailabilityService,
            final HoldSeatsService holdSeatsService,
            final ExtendHoldService extendHoldService,
            final CommitSeatsService commitSeatsService,
            final ReleaseSeatsService releaseSeatsService) {
        this.checkAvailabilityService = checkAvailabilityService;
        this.holdSeatsService = holdSeatsService;
        this.extendHoldService = extendHoldService;
        this.commitSeatsService = commitSeatsService;
        this.releaseSeatsService = releaseSeatsService;
    }

    @Override
    public void checkSeatAvailability(
            final CheckSeatAvailabilityRequest request,
            final StreamObserver<CheckSeatAvailabilityResponse> responseObserver) {

        final UUID eventId = UUID.fromString(request.getEventId());
        final List<UUID> seatIds = request.getSeatIdsList().stream()
                .map(UUID::fromString).toList();

        final CheckAvailabilityService.AvailabilityResult result =
                checkAvailabilityService.checkAvailability(eventId, seatIds);

        final CheckSeatAvailabilityResponse.Builder response =
                CheckSeatAvailabilityResponse.newBuilder()
                        .setAllAvailable(result.allAvailable());

        result.unavailable().forEach(u ->
                response.addUnavailableSeats(
                        UnavailableSeat.newBuilder()
                                .setSeatId(u.seatId().toString())
                                .build()));

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void holdSeats(
            final HoldSeatsRequest request,
            final StreamObserver<HoldSeatsResponse> responseObserver) {

        final UUID bookingId = UUID.fromString(request.getBookingId());
        final UUID eventId   = UUID.fromString(request.getEventId());
        final UUID customerId = UUID.fromString(request.getCustomerId());
        final List<UUID> seatIds = request.getSeatIdsList().stream()
                .map(UUID::fromString).toList();

        final HoldSeatsService.HoldResult result =
                holdSeatsService.holdSeats(bookingId, eventId, customerId, seatIds, "GRPC");

        final HoldSeatsResponse.Builder response = HoldSeatsResponse.newBuilder();
        if (result.success()) {
            response.setStatus(HoldSeatsStatus.SUCCESS)
                    .setHoldId(result.holdId().toString())
                    .setHeldUntil(result.heldUntil().toString());
        } else {
            response.setStatus(HoldSeatsStatus.UNAVAILABLE);
            result.unavailableSeats().forEach(id ->
                    response.addUnavailableSeats(id.toString()));
        }

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void extendHold(
            final ExtendHoldRequest request,
            final StreamObserver<ExtendHoldResponse> responseObserver) {

        final UUID bookingId = UUID.fromString(request.getBookingId());
        // TODO Phase 4 impl: pass seatIds — requires Booking Service to include them.
        // For now, look up from seat_holds. Stub returns success.
        final ExtendHoldResponse response = ExtendHoldResponse.newBuilder()
                .setSuccess(true)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void commitSeats(
            final CommitSeatsRequest request,
            final StreamObserver<CommitSeatsResponse> responseObserver) {

        final UUID bookingId = UUID.fromString(request.getBookingId());
        final UUID eventId   = UUID.fromString(request.getEventId());
        final List<UUID> seatIds = request.getSeatIdsList().stream()
                .map(UUID::fromString).toList();

        CommitSeatsService.CommitResult result;
        try {
            result = commitSeatsService.commitSeats(bookingId, eventId, seatIds);
        } catch (final CannotSerializeTransactionException e) {
            // SERIALIZABLE conflict — caller must retry per NFR-REL-006.
            log.warn("CommitSeats SERIALIZABLE conflict — bookingId={} — caller should retry",
                    bookingId);
            responseObserver.onNext(CommitSeatsResponse.newBuilder()
                    .setStatus(CommitSeatsStatus.SERIALIZATION_FAILURE).build());
            responseObserver.onCompleted();
            return;
        }

        final CommitSeatsStatus status = switch (result) {
            case SUCCESS           -> CommitSeatsStatus.COMMIT_SUCCESS;
            case HOLD_NOT_FOUND    -> CommitSeatsStatus.HOLD_NOT_FOUND;
            case SERIALIZATION_FAILURE -> CommitSeatsStatus.SERIALIZATION_FAILURE;
        };

        responseObserver.onNext(CommitSeatsResponse.newBuilder().setStatus(status).build());
        responseObserver.onCompleted();
    }

    @Override
    public void releaseSeats(
            final ReleaseSeatsRequest request,
            final StreamObserver<ReleaseSeatsResponse> responseObserver) {

        final UUID bookingId = UUID.fromString(request.getBookingId());
        final UUID eventId   = UUID.fromString(request.getEventId());
        final List<UUID> seatIds = request.getSeatIdsList().stream()
                .map(UUID::fromString).toList();

        releaseSeatsService.releaseSeats(bookingId, eventId, seatIds, request.getReason());

        responseObserver.onNext(ReleaseSeatsResponse.newBuilder()
                .setStatus(ReleaseSeatsStatus.RELEASE_SUCCESS).build());
        responseObserver.onCompleted();
    }
}
