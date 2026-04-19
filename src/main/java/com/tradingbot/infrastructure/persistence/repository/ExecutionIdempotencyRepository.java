package com.tradingbot.infrastructure.persistence.repository;

import com.tradingbot.infrastructure.persistence.entity.ExecutionIdempotencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface ExecutionIdempotencyRepository extends JpaRepository<ExecutionIdempotencyEntity, String> {

    @Modifying
    @Query(value = "INSERT INTO execution_idempotency (client_order_id, order_id, status, created_at, updated_at) " +
                   "VALUES (:clientOrderId, :orderId, 'IN_PROGRESS', :now, :now) " +
                   "ON CONFLICT (client_order_id) DO NOTHING", nativeQuery = true)
    int tryInsertIdempotency(@Param("clientOrderId") String clientOrderId, 
                             @Param("orderId") String orderId, 
                             @Param("now") Instant now);
}
