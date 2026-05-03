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
        WHERE (status = 'NEW')
           OR (status = 'FAILED' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
           OR (status = 'PROCESSING' AND locked_until < :now)
        ORDER BY aggregate_id, sequence_number ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)    List<OutboxEventEntity> claimBatchWithLock(            @org.springframework.data.repository.query.Param("limit") int limit,
            @org.springframework.data.repository.query.Param("now") java.time.Instant now
    );

    @Deprecated
    @Query("""
        SELECT e FROM OutboxEventEntity e
        WHERE e.status IN (
            com.tradingbot.infrastructure.outbox.OutboxStatus.NEW,
            com.tradingbot.infrastructure.outbox.OutboxStatus.FAILED
        )
        ORDER BY e.aggregateId, e.sequenceNumber ASC
        """)
    List<OutboxEventEntity> claimBatch(org.springframework.data.domain.Pageable pageable);
    /**
     * Detect stuck processing events (crash recovery)
     */
    @Query("""
        SELECT e
        FROM OutboxEventEntity e
        WHERE e.status = com.tradingbot.infrastructure.outbox.OutboxStatus.PROCESSING
          AND e.updatedAt < :threshold
        """)
    List<OutboxEventEntity> findStaleProcessingEvents(java.time.Instant threshold);

    /**
     * Retry control handled in service layer, not SQL
     */
    List<OutboxEventEntity> findByStatusIn(List<OutboxStatus> statuses);

    long countByStatus(OutboxStatus status);

    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        DELETE FROM OutboxEventEntity e
        WHERE e.status = com.tradingbot.infrastructure.outbox.OutboxStatus.PROCESSED
        AND e.processedAt < :threshold
        """)
    int deleteProcessedOlderThan(@org.springframework.data.repository.query.Param("threshold") java.time.Instant threshold);

    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM outbox_events
            WHERE aggregate_id = :aggregateId
              AND sequence_number < :sequenceNumber
              AND status != 'PROCESSED'
        )
        """, nativeQuery = true)
    boolean existsUnprocessedBefore(@org.springframework.data.repository.query.Param("aggregateId") UUID aggregateId,
                                   @org.springframework.data.repository.query.Param("sequenceNumber") long sequenceNumber);

    @Query(value = "SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM outbox_events WHERE aggregate_id = :aggregateId FOR UPDATE", nativeQuery = true)    long getNextSequenceNumber(@org.springframework.data.repository.query.Param("aggregateId") UUID aggregateId);}
