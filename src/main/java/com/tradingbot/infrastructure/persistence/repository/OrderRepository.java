package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.common.enums.OrderStatus;
import com.tradingbot.infrastructure.persistence.entity.OrderEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    Optional<OrderEntity> findByClientOrderId(String clientOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(
                    name = "jakarta.persistence.lock.timeout",
                    value = "-2"
            )
    })
    @Query("SELECT o FROM OrderEntity o WHERE o.id = :id")
    Optional<OrderEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Пессимистически блокирует ордер только если он всё ещё
     * находится в указанном статусе.
     *
     * Это важно для конкурентного reconciliation:
     *
     * worker A:
     *     UNKNOWN -> RECOVERING
     *
     * worker B:
     *     ждёт lock
     *     после commit A повторно проверяет WHERE status = UNKNOWN
     *     и получает пустой результат.
     *
     * Таким образом второй worker не пытается обновить уже изменённую
     * сущность и не получает StaleObjectStateException.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(
                    name = "jakarta.persistence.lock.timeout",
                    value = "-2"
            )
    })
    @Query("""
            SELECT o
            FROM OrderEntity o
            WHERE o.id = :id
              AND o.status = :status
            """)
    Optional<OrderEntity> findByIdForUpdateAndStatus(
            @Param("id") UUID id,
            @Param("status") OrderStatus status
    );

    @Query("""
            SELECT o
            FROM OrderEntity o
            WHERE o.status IN :statuses
              AND o.createdAt < :threshold
            """)
    List<OrderEntity> findStuckOrdersInStatuses(
            @Param("statuses")
            java.util.Collection<com.tradingbot.common.enums.OrderStatus> statuses,
            @Param("threshold")
            Instant threshold
    );

    @Query("""
        SELECT o
        FROM OrderEntity o
        WHERE o.status = :status
          AND o.executionStartedAt IS NOT NULL
          AND o.executionStartedAt < :threshold
        """)
    List<OrderEntity> findStuckOrders(
            @Param("status")
            com.tradingbot.common.enums.OrderStatus status,
            @Param("threshold")
            Instant threshold
    );

    @Query("""
            SELECT o.id
            FROM OrderEntity o
            WHERE o.status IN :statuses
            """)
    Set<UUID> findOrderIdsByStatusIn(
            @Param("statuses")
            Set<com.tradingbot.common.enums.OrderStatus> statuses
    );

    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query("""
    UPDATE OrderEntity o
       SET o.version = o.version + 1,
           o.updatedAt = :updatedAt
     WHERE o.id = :orderId
       AND o.version = :expectedVersion
       AND o.status IN :statuses
    """)
    int tryClaimForReconciliation(
            @Param("orderId") UUID orderId,
            @Param("expectedVersion") Long expectedVersion,
            @Param("statuses") Set<OrderStatus> statuses,
            @Param("updatedAt") Instant updatedAt
    );

    Optional<OrderEntity> findBySignalId(UUID signalId);

    long countBySignalId(UUID signalId);
}