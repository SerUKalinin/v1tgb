package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.outbox.OutboxStatus;
import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository
        extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findByStatus(
            OutboxStatus status
    );

    /**
     * Claims only events whose predecessors for the same aggregate
     * have already been PROCESSED.
     *
     * This enforces causal ordering:
     *
     * sequence 1 -> must be processed
     * before
     * sequence 2 -> can be claimed.
     *
     * Different aggregates remain independently processable.
     */
    @Query(value = """
        SELECT e.*
        FROM outbox_events e
        WHERE (
            e.status = 'NEW'
            OR (
                e.status = 'FAILED'
                AND (
                    e.next_attempt_at IS NULL
                    OR e.next_attempt_at <= :now
                )
            )
            OR (
                e.status = 'PROCESSING'
                AND e.locked_until < :now
            )
        )
        AND NOT EXISTS (
            SELECT 1
            FROM outbox_events predecessor
            WHERE predecessor.aggregate_id = e.aggregate_id
              AND predecessor.sequence_number < e.sequence_number
              AND predecessor.status != 'PROCESSED'
        )
        ORDER BY
            e.aggregate_id,
            e.sequence_number ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEventEntity> claimBatchWithLock(
            @Param("limit") int limit,
            @Param("now") Instant now
    );

    /**
     * Legacy batch claim.
     *
     * Kept for compatibility with existing callers.
     * New processing path uses claimBatchWithLock().
     */
    @Deprecated
    @Query("""
        SELECT e
        FROM OutboxEventEntity e
        WHERE e.status IN (
            com.tradingbot.infrastructure.outbox.OutboxStatus.NEW,
            com.tradingbot.infrastructure.outbox.OutboxStatus.FAILED
        )
        ORDER BY e.aggregateId, e.sequenceNumber ASC
        """)
    List<OutboxEventEntity> claimBatch(
            org.springframework.data.domain.Pageable pageable
    );

    /**
     * Detect stale PROCESSING events after a worker crash.
     */
    @Query("""
        SELECT e
        FROM OutboxEventEntity e
        WHERE e.status =
              com.tradingbot.infrastructure.outbox.OutboxStatus.PROCESSING
          AND e.updatedAt < :threshold
        """)
    List<OutboxEventEntity> findStaleProcessingEvents(
            @Param("threshold") Instant threshold
    );

    /**
     * Retry control is handled in OutboxProcessor.
     */
    List<OutboxEventEntity> findByStatusIn(
            List<OutboxStatus> statuses
    );

    long countByStatus(
            OutboxStatus status
    );

    /**
     * Cleanup of successfully processed outbox events.
     */
    @Modifying
    @Query("""
        DELETE FROM OutboxEventEntity e
        WHERE e.status =
              com.tradingbot.infrastructure.outbox.OutboxStatus.PROCESSED
          AND e.processedAt < :threshold
        """)
    int deleteProcessedOlderThan(
            @Param("threshold") Instant threshold
    );

    /**
     * Runtime causal-ordering guard.
     *
     * Returns true when an earlier event of the same aggregate
     * is still not PROCESSED.
     */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1
            FROM outbox_events
            WHERE aggregate_id = :aggregateId
              AND sequence_number < :sequenceNumber
              AND status != 'PROCESSED'
        )
        """, nativeQuery = true)
    boolean existsUnprocessedBefore(
            @Param("aggregateId") UUID aggregateId,
            @Param("sequenceNumber") long sequenceNumber
    );

    /**
     * Returns next sequence number for an aggregate.
     */
    @Query(value = """
        SELECT COALESCE(MAX(sequence_number), 0) + 1
        FROM outbox_events
        WHERE aggregate_id = :aggregateId
        """, nativeQuery = true)
    long getNextSequenceNumber(
            @Param("aggregateId") UUID aggregateId
    );
}