package dev.stagepass.seatinventory.repository;

import dev.stagepass.seatinventory.entity.SeatHoldSeatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeatHoldSeatRepository extends JpaRepository<SeatHoldSeatEntity, UUID> {

    // All seats in a hold (CommitSeats / ReleaseSeats)
    List<SeatHoldSeatEntity> findByHoldId(UUID holdId);

    // All active holds for a specific seat (reconciliation)
    List<SeatHoldSeatEntity> findBySeatIdAndStatus(UUID seatId, String status);
}
