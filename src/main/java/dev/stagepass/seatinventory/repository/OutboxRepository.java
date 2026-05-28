package dev.stagepass.seatinventory.repository;

import dev.stagepass.seatinventory.entity.OutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {

    // Publisher hot path: SKIP LOCKED prevents concurrent publisher threads
    // from processing the same row. ORDER BY created_at ASC preserves causal order.
    // The FOR UPDATE SKIP LOCKED is done at the transaction level in OutboxPublisher.
    @Query(value = """
            SELECT * FROM outbox
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEntity> findPendingForPublishing(@Param("limit") int limit);
}
