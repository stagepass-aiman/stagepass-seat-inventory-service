package dev.stagepass.seatinventory.repository;

import dev.stagepass.seatinventory.entity.SeatHoldEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatHoldRepository extends JpaRepository<SeatHoldEntity, UUID> {

    Optional<SeatHoldEntity> findByBookingId(UUID bookingId);

    // Reconciliation: expired HELD holds needing PostgreSQL sync
    // (Redis TTL has expired but PG row still shows HELD)
    @Query("SELECT h FROM SeatHoldEntity h WHERE h.status = 'HELD' AND h.expiresAt < :now")
    List<SeatHoldEntity> findExpiredHeldHolds(@Param("now") Instant now);

    // All holds for an event in a given status
    List<SeatHoldEntity> findByEventIdAndStatus(UUID eventId, String status);
}
