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
        SELECT *
        FROM outbox_events
        WHERE status IN ('NEW', 'FAILED')
        ORDER BY created_at
        FOR UPDATE SKIP LOCKED
        LIMIT 50
        """, nativeQuery = true)
    List<OutboxEventEntity> claimBatch();

    /**
     * Detect stuck processing events (crash recovery)
     */
    @Query(value = """
        SELECT *
        FROM outbox_events
        WHERE status = 'PROCESSING'
          AND updated_at < now() - interval '30 seconds'
        """, nativeQuery = true)
    List<OutboxEventEntity> findStaleProcessingEvents();

    /**
     * Retry control handled in service layer, not SQL
     */
    List<OutboxEventEntity> findByStatusIn(List<OutboxStatus> statuses);
}