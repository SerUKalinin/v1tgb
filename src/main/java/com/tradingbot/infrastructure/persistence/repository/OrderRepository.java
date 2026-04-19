package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.domain.model.OrderEntity;
import com.tradingbot.common.enums.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// ИЗМЕНЕНО: JpaRepository<OrderEntity, String> → JpaRepository<OrderEntity, Long>
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    Optional<OrderEntity> findByClientOrderId(String clientOrderId);

    boolean existsByClientOrderId(String clientOrderId);

    @Query("""
        SELECT o FROM OrderEntity o
        WHERE o.status = com.tradingbot.common.enums.OrderStatus.PENDING_EXECUTION
           OR (o.status = com.tradingbot.common.enums.OrderStatus.EXECUTING
               AND o.executionExpiresAt < :now)
    """)
    List<OrderEntity> findOrdersToRecover(@Param("now") Instant now, Pageable pageable);

    // ИЗМЕНЕНО: @Param("orderId") String → Long
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE OrderEntity o
        SET o.status            = com.tradingbot.common.enums.OrderStatus.EXECUTING,
            o.executionOwner    = :owner,
            o.executionExpiresAt = :expiresAt,
            o.updatedAt         = :now
        WHERE o.id = :orderId
          AND (
              o.status = com.tradingbot.common.enums.OrderStatus.PENDING_EXECUTION
              OR (o.status = com.tradingbot.common.enums.OrderStatus.EXECUTING
                  AND o.executionExpiresAt < :now)
          )
    """)
    int claimForExecution(
            @Param("orderId") Long orderId,
            @Param("owner") String owner,
            @Param("expiresAt") Instant expiresAt,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE OrderEntity o
        SET o.executionExpiresAt = :newExpiresAt,
            o.updatedAt          = :now
        WHERE o.id             = :orderId
          AND o.executionOwner  = :owner
          AND o.status          = com.tradingbot.common.enums.OrderStatus.EXECUTING
    """)
    int extendLease(
            @Param("orderId") Long orderId,
            @Param("owner") String owner,
            @Param("newExpiresAt") Instant newExpiresAt,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE OrderEntity o
        SET o.status         = :newStatus,
            o.exchangeOrderId = :exchangeOrderId,
            o.updatedAt       = :now
        WHERE o.id            = :orderId
          AND o.status         = com.tradingbot.common.enums.OrderStatus.EXECUTING
          AND o.executionOwner = :owner
    """)
    int updateStatusWithFencing(
            @Param("orderId") Long orderId,
            @Param("newStatus") OrderStatus newStatus,
            @Param("exchangeOrderId") String exchangeOrderId,
            @Param("owner") String owner,
            @Param("now") Instant now
    );
}
