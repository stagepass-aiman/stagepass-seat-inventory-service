package dev.stagepass.seatinventory.repository;

import dev.stagepass.seatinventory.entity.SeatStateTransitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeatStateTransitionRepository extends JpaRepository<SeatStateTransitionEntity, Long> {

    List<SeatStateTransitionEntity> findBySeatIdOrderByOccurredAtDesc(UUID seatId);

    List<SeatStateTransitionEntity> findByBookingId(UUID bookingId);

    List<SeatStateTransitionEntity> findByEventIdOrderByOccurredAtDesc(UUID eventId);
}
