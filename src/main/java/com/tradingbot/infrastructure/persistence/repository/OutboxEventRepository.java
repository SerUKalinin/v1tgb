package com.tradingbot.infrastructure.persistence.repository;


import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository
        extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findByStatus(OutboxStatus status);

    @Query(value = """
        SELECT * FROM outbox_events
        WHERE status IN ('NEW', 'FAILED')
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEventEntity> claimBatchWithLock(@org.springframework.data.repository.query.Param("limit") int limit);

    @Deprecated
    @Query("""
        SELECT e FROM OutboxEventEntity e
        WHERE e.status IN (
            com.tradingbot.infrastructure.outbox.OutboxStatus.NEW,
            com.tradingbot.infrastructure.outbox.OutboxStatus.FAILED
        )
        ORDER BY e.createdAt ASC
        """)
    List<OutboxEventEntity> claimBatch(org.springframework.data.domain.Pageable pageable);    /**
     * Detect stuck processing events (crash recovery)
     */
    @Query("""
        SELECT e
        FROM OutboxEventEntity e
        WHERE e.status = com.tradingbot.infrastructure.outbox.OutboxStatus.PROCESSING
          AND e.updatedAt < :threshold
        """)
    List<OutboxEventEntity> findStaleProcessingEvents(java.time.Instant threshold);    /**
     * Retry control handled in service layer, not SQL
     */
    List<OutboxEventEntity> findByStatusIn(List<OutboxStatus> statuses);

    long countByStatus(OutboxStatus status);
}