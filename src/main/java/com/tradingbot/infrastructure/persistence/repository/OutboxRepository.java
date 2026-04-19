package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.OutboxEventEntity;
import com.tradingbot.infrastructure.persistence.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// ИЗМЕНЕНО: JpaRepository<OutboxEventEntity, String> → UUID
public interface OutboxRepository extends JpaRepository<OutboxEventEntity, UUID> {

    // ИЗМЕНЕНО: String → OutboxStatus enum
    List<OutboxEventEntity> findByStatus(OutboxStatus status);

    // Основной poll-запрос: PENDING события с истёкшим next_retry_at (для backoff)
    @Query("""
        SELECT o FROM OutboxEventEntity o
        WHERE o.status = com.tradingbot.infrastructure.persistence.entity.OutboxStatus.PENDING
          AND o.nextRetryAt <= :now
          AND o.retryCount < o.maxRetries
        ORDER BY o.createdAt ASC
    """)
    List<OutboxEventEntity> findPendingForProcessing(Instant now);
}