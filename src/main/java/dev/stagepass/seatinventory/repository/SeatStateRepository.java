package dev.stagepass.seatinventory.repository;

import dev.stagepass.seatinventory.entity.SeatStateEntity;
import dev.stagepass.seatinventory.entity.SeatStateEnum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatStateRepository extends JpaRepository<SeatStateEntity, UUID> {

    // Seat map initial load for an event
    List<SeatStateEntity> findByEventId(UUID eventId);

    // CheckSeatAvailability: read Redis first; fall back to PG for BLOCKED check
    Optional<SeatStateEntity> findByEventIdAndSeatId(UUID eventId, UUID seatId);

    // CommitSeats / ReleaseSeats: look up seats by bookingId
    List<SeatStateEntity> findByBookingId(UUID bookingId);

    // Availability query for a specific set of seats (guard check)
    @Query("SELECT s FROM SeatStateEntity s WHERE s.eventId = :eventId AND s.seatId IN :seatIds")
    List<SeatStateEntity> findByEventIdAndSeatIdIn(
            @Param("eventId") UUID eventId,
            @Param("seatIds") List<UUID> seatIds);

    // Reconciliation: find all HELD seats for event (expired hold detection)
    List<SeatStateEntity> findByEventIdAndState(UUID eventId, SeatStateEnum state);
}
