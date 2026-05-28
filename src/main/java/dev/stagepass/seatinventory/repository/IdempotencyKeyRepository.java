package dev.stagepass.seatinventory.repository;

import dev.stagepass.seatinventory.entity.IdempotencyKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, UUID> {

    Optional<IdempotencyKeyEntity> findByIdempotencyKeyAndOperation(
            String idempotencyKey, String operation);

    // Cleanup: remove expired keys (runs every hour via scheduler)
    @Query("SELECT i FROM IdempotencyKeyEntity i WHERE i.expiresAt < :now")
    List<IdempotencyKeyEntity> findExpired(@Param("now") Instant now);
}
